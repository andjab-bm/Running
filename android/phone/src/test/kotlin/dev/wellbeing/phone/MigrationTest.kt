package dev.wellbeing.phone

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.PlanWorkoutEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The database of a phone already in use moves to version 2 without losing its runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    // An absolute path: under Robolectric, Room's test helper does not resolve a bare name the way it opens it.
    private val name = ApplicationProvider.getApplicationContext<android.content.Context>().getDatabasePath("migration-test.db").absolutePath

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDb::class.java)

    @Test
    fun `version 1 runs and an interrupted run's log survive the move to version 2`() {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO runs (id, state, startT, endT, distance, movingMs, gain, title, note, autoPause, splitMeters, sync, journalDirty, fitPath) " +
                    "VALUES (1, 'saved', 1000, 2000, 5000.0, 1800000, 12.0, 'Sortie du dimanche', '', 1, 1000.0, 'synced', 0, 'runs/1.fit')",
            )
            execSQL(
                "INSERT INTO runs (id, state, startT, endT, distance, movingMs, gain, title, note, autoPause, splitMeters, sync, journalDirty) " +
                    "VALUES (2, 'recording', 3000, 0, 0.0, 0, 0.0, '', '', 1, 1000.0, 'pending', 0)",
            )
            execSQL("INSERT INTO events (runId, type, t, a, b) VALUES (2, 5, 3100, 48.85, 2.35)")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, AppDb.MIGRATION_1_2).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java, name)
            .addMigrations(AppDb.MIGRATION_1_2).allowMainThreadQueries().build()
        runBlocking {
            val runs = db.runs()
            assertEquals("Sortie du dimanche", runs.run(1)!!.title)
            assertNull(runs.run(1)!!.context)
            assertEquals(2L, runs.interrupted()!!.id)
            val e = runs.events(2).single()
            assertEquals(48.85, e.a!!, 0.0)
            assertNull(e.text)
            db.plan().putWorkout(PlanWorkoutEntity(5, 2, 1, "6 × 400 m", "", "[]"))
            assertEquals(1, db.plan().watchWorkouts(2).first().size)
        }
        db.close()
    }
}
