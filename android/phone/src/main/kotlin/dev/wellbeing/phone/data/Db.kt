package dev.wellbeing.phone.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Life of a run on the phone. */
object RunState {
    /** In progress, or interrupted: only its event log exists. */
    const val RECORDING = "recording"
    const val SAVED = "saved"
}

object SyncState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val FAILED = "failed"
}

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val state: String = RunState.RECORDING,
    val startT: Long,
    val endT: Long = 0,
    val distance: Double = 0.0,
    val movingMs: Long = 0,
    val gain: Double = 0.0,
    val avgHr: Int? = null,
    val avgCadence: Int? = null,
    val title: String = "",
    val rpe: Int? = null,
    val note: String = "",
    /** Engine settings of this run, needed to replay its event log identically. */
    val autoPause: Boolean = true,
    val splitMeters: Double = 1000.0,
    val sync: String = SyncState.PENDING,
    val serverActivityId: Long? = null,
    val failReason: String? = null,
    /** Title, effort or note changed since the last upload. */
    val journalDirty: Boolean = false,
    /** Path of the FIT file, relative to the app's files directory. */
    val fitPath: String? = null,
    /**
     * What the run was done with — session as run, route, pace target and final gap — as the JSON
     * fields of the server's journal patch; sent with the notes.
     */
    val context: String? = null,
)

/** Log of the run in progress; deleted when the run is saved. */
@Entity(tableName = "events", indices = [androidx.room.Index("runId")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val type: Int,
    val t: Long,
    val a: Double? = null,
    val b: Double? = null,
    val c: Double? = null,
    val d: Double? = null,
    /** The run's plan (session, route, pace target) as JSON; null for every other event. */
    val text: String? = null,
)

@Entity(tableName = "hr_samples", primaryKeys = ["runId", "t"])
data class HrEntity(val runId: Long, val t: Long, val bpm: Int)

@Entity(tableName = "samples", primaryKeys = ["runId", "t"])
data class SampleEntity(
    val runId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val dist: Double,
    val cadence: Int?,
    val speed: Double?,
)

@Entity(tableName = "laps", primaryKeys = ["runId", "idx"])
data class LapEntity(
    val runId: Long,
    val idx: Int,
    val startT: Long,
    val endT: Long,
    val distance: Double,
    val movingMs: Long,
    val manual: Boolean,
    val avgHr: Int?,
)

@Entity(tableName = "timer_events", primaryKeys = ["runId", "t", "start"])
data class TimerEntity(val runId: Long, val t: Long, val start: Boolean)

/** Last list of the profile's activities retrieved from the server, for offline viewing. */
@Entity(tableName = "server_activities")
data class ServerActivityEntity(
    @PrimaryKey val id: Long,
    val profileId: Long,
    val startT: Long,
    val title: String,
    val distance: Double,
    val movingMs: Long,
    val avgHr: Int?,
    val gain: Double?,
)

/**
 * A session delivered to the phone by the home server, as it was at [version]. [steps] is the
 * session document, read with `dev.wellbeing.running.Workout.parse`.
 */
@Entity(tableName = "plan_workouts")
data class PlanWorkoutEntity(
    @PrimaryKey val id: Long,
    val profileId: Long,
    val version: Long,
    val name: String,
    val note: String,
    val steps: String,
)

/** A route delivered to the phone, with its points as JSON arrays (`lat`, `lon`, `ele`, `dist`). */
@Entity(tableName = "plan_routes")
data class PlanRouteEntity(
    @PrimaryKey val id: Long,
    val profileId: Long,
    val version: Long,
    val name: String,
    val distance: Double,
    val gain: Double?,
    val loss: Double?,
    val isLoop: Boolean,
    val hasElevation: Boolean,
    val points: String,
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_workouts WHERE profileId = :profileId ORDER BY name COLLATE NOCASE") fun watchWorkouts(profileId: Long): Flow<List<PlanWorkoutEntity>>
    @Query("SELECT * FROM plan_routes WHERE profileId = :profileId ORDER BY name COLLATE NOCASE") fun watchRoutes(profileId: Long): Flow<List<PlanRouteEntity>>
    @Query("SELECT * FROM plan_workouts") suspend fun workouts(): List<PlanWorkoutEntity>
    @Query("SELECT * FROM plan_routes") suspend fun routes(): List<PlanRouteEntity>
    @Query("SELECT * FROM plan_workouts WHERE id = :id") suspend fun workout(id: Long): PlanWorkoutEntity?
    @Query("SELECT * FROM plan_routes WHERE id = :id") suspend fun route(id: Long): PlanRouteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWorkout(w: PlanWorkoutEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRoute(r: PlanRouteEntity)
    @Query("DELETE FROM plan_workouts WHERE id = :id") suspend fun deleteWorkout(id: Long)
    @Query("DELETE FROM plan_routes WHERE id = :id") suspend fun deleteRoute(id: Long)
}

@Dao
interface RunDao {
    @Insert suspend fun insertRun(run: RunEntity): Long
    @Update suspend fun updateRun(run: RunEntity)
    @Query("SELECT * FROM runs WHERE id = :id") suspend fun run(id: Long): RunEntity?
    @Query("SELECT * FROM runs WHERE id = :id") fun watchRun(id: Long): Flow<RunEntity?>
    @Query("SELECT * FROM runs WHERE state = 'saved' ORDER BY startT DESC") fun watchSaved(): Flow<List<RunEntity>>
    @Query("SELECT * FROM runs WHERE state = 'recording' ORDER BY id DESC LIMIT 1") suspend fun interrupted(): RunEntity?
    @Query("SELECT * FROM runs WHERE state = 'saved' AND (sync = 'pending' OR journalDirty) ORDER BY startT") suspend fun toSync(): List<RunEntity>

    @Insert suspend fun insertEvents(events: List<EventEntity>)
    @Query("SELECT * FROM events WHERE runId = :runId ORDER BY id") suspend fun events(runId: Long): List<EventEntity>
    @Query("DELETE FROM events WHERE runId = :runId") suspend fun deleteEvents(runId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHr(samples: List<HrEntity>)
    @Query("SELECT * FROM hr_samples WHERE runId = :runId ORDER BY t") suspend fun hr(runId: Long): List<HrEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSamples(samples: List<SampleEntity>)
    @Query("SELECT * FROM samples WHERE runId = :runId ORDER BY t") suspend fun samples(runId: Long): List<SampleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLaps(laps: List<LapEntity>)
    @Query("SELECT * FROM laps WHERE runId = :runId ORDER BY idx") suspend fun laps(runId: Long): List<LapEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTimer(events: List<TimerEntity>)
    @Query("SELECT * FROM timer_events WHERE runId = :runId ORDER BY t") suspend fun timer(runId: Long): List<TimerEntity>

    @Query("DELETE FROM runs WHERE id = :runId") suspend fun deleteRunRow(runId: Long)
    @Query("DELETE FROM hr_samples WHERE runId = :runId") suspend fun deleteHr(runId: Long)
    @Query("DELETE FROM samples WHERE runId = :runId") suspend fun deleteSamples(runId: Long)
    @Query("DELETE FROM laps WHERE runId = :runId") suspend fun deleteLaps(runId: Long)
    @Query("DELETE FROM timer_events WHERE runId = :runId") suspend fun deleteTimer(runId: Long)

    /** Removes a run and everything recorded for it. */
    @Transaction
    suspend fun deleteRun(runId: Long) {
        deleteEvents(runId); deleteHr(runId); deleteSamples(runId); deleteLaps(runId); deleteTimer(runId)
        deleteRunRow(runId)
    }

    /** Stores the finished run and drops its event log, in one step. */
    @Transaction
    suspend fun saveFinished(run: RunEntity, samples: List<SampleEntity>, laps: List<LapEntity>, timer: List<TimerEntity>) {
        insertSamples(samples); insertLaps(laps); insertTimer(timer)
        updateRun(run)
        deleteEvents(run.id)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertServerActivities(items: List<ServerActivityEntity>)
    @Query("DELETE FROM server_activities WHERE profileId = :profileId") suspend fun clearServerActivities(profileId: Long)
    @Query("SELECT * FROM server_activities WHERE profileId = :profileId ORDER BY startT DESC") fun watchServerActivities(profileId: Long): Flow<List<ServerActivityEntity>>
}

@Database(
    entities = [
        RunEntity::class, EventEntity::class, HrEntity::class, SampleEntity::class, LapEntity::class, TimerEntity::class,
        ServerActivityEntity::class, PlanWorkoutEntity::class, PlanRouteEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun runs(): RunDao
    abstract fun plan(): PlanDao

    companion object {
        @Volatile private var instance: AppDb? = null

        /** Version 2 (coaching): the plan of the run in the event log, and the sessions and routes delivered to the phone. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `text` TEXT")
                db.execSQL("ALTER TABLE `runs` ADD COLUMN `context` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_workouts` (`id` INTEGER NOT NULL, `profileId` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `note` TEXT NOT NULL, `steps` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_routes` (`id` INTEGER NOT NULL, `profileId` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `distance` REAL NOT NULL, `gain` REAL, `loss` REAL, `isLoop` INTEGER NOT NULL, " +
                        "`hasElevation` INTEGER NOT NULL, `points` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "wellbeing.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
