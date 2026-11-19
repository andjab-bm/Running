package dev.wellbeing.phone.sync

import dev.wellbeing.phone.data.PlanDao
import dev.wellbeing.phone.data.PlanRouteEntity
import dev.wellbeing.phone.data.PlanWorkoutEntity
import dev.wellbeing.phone.data.RunDao
import dev.wellbeing.phone.data.RunEntity
import dev.wellbeing.phone.data.ServerActivityEntity
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SyncState
import java.util.TimeZone

/** How a sync attempt ended. */
enum class SyncResult {
    /** Everything that could be sent was sent. */
    DONE,
    /** No server address or no profile chosen yet. */
    NOT_CONFIGURED,
    /** The server did not answer: runs stay pending and will be retried. */
    UNREACHABLE,
    /** Something else answers at the address. */
    NOT_OUR_SERVER,
    /** The chosen profile no longer exists on the server: the runner must choose another. */
    PROFILE_MISSING,
    /** The phone is on a metered connection: nothing is sent. */
    METERED,
}

data class SyncReport(
    val result: SyncResult,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    /** Sessions and routes on the phone after the attempt; null when the plan was not reached. */
    val plan: PlanCount? = null,
)

data class PlanCount(val workouts: Int, val routes: Int)

/** Sessions and routes attached to the run being prepared or recorded: never replaced or removed under it. */
data class InUse(val workoutId: Long? = null, val routeId: Long? = null)

/**
 * One sync attempt: check the server, refresh the profile, upload pending runs oldest first,
 * apply notes, refresh the cached list of the profile's activities, then bring the plan — the
 * sessions and routes marked for the phone — up to date and tell the server what the phone holds.
 *
 * Retries are safe because the server refuses a run it already holds and says which activity it
 * is: a duplicate is treated as uploaded.
 */
class SyncEngine(
    private val dao: RunDao,
    private val settings: suspend () -> Settings,
    private val updateSettings: suspend ((Settings) -> Settings) -> Unit,
    private val client: (baseUrl: String) -> ServerClient,
    private val readFit: (path: String) -> ByteArray?,
    private val plan: PlanDao? = null,
    /** How the phone names itself in its receipts. */
    private val device: String = "phone",
    private val inUse: () -> InUse = { InUse() },
) {
    suspend fun run(): SyncReport {
        val s = settings()
        val profileId = s.profileId
        if (s.serverUrl.isBlank() || profileId == null) return SyncReport(SyncResult.NOT_CONFIGURED, pending = dao.toSync().size)
        var uploaded = 0
        var failed = 0
        planCount = null
        try {
            val api = client(s.serverUrl)
            api.health()
            val profile = api.profile(profileId) ?: return SyncReport(SyncResult.PROFILE_MISSING, pending = dao.toSync().size)
            updateSettings { it.copy(profileName = profile.name, units = profile.units, zones = profile.zones) }

            for (queued in dao.toSync()) {
                var run = queued
                if (run.sync == SyncState.PENDING) {
                    val bytes = run.fitPath?.let(readFit)
                    if (bytes == null) {
                        dao.updateRun(run.copy(sync = SyncState.FAILED, failReason = MISSING_FILE))
                        failed++
                        continue
                    }
                    val outcome = api.importFit(profileId, "run-${run.startT}.fit", bytes, TimeZone.getDefault().getOffset(run.startT) / 60_000)
                    if (outcome.status == ImportOutcome.FAILED || outcome.activityId == null) {
                        // An unusable file will not become usable: it is kept, not retried.
                        dao.updateRun(run.copy(sync = SyncState.FAILED, failReason = outcome.reason ?: UNKNOWN_REASON))
                        failed++
                        continue
                    }
                    // The upload is recorded before the notes are sent, so an interruption
                    // between the two never uploads the file a second time.
                    run = run.copy(sync = SyncState.SYNCED, serverActivityId = outcome.activityId, failReason = null, journalDirty = run.hasNotes())
                    dao.updateRun(run)
                    uploaded++
                }
                val activityId = run.serverActivityId
                if (run.journalDirty && activityId != null) {
                    api.patchJournal(activityId, run.title.ifBlank { null }, run.note, run.rpe, run.context)
                    dao.updateRun(run.copy(journalDirty = false))
                }
            }

            val list = api.activities(profileId)
            dao.clearServerActivities(profileId)
            dao.insertServerActivities(list.map { ServerActivityEntity(it.id, profileId, it.startT, it.title, it.distance, it.movingMs, it.avgHr, it.gain) })
            planCount = plan?.let { syncPlan(api, it, profileId) }
        } catch (e: ApiException) {
            val result = when (e.kind) {
                ApiException.Kind.NOT_OUR_SERVER -> SyncResult.NOT_OUR_SERVER
                ApiException.Kind.PROFILE_MISSING -> SyncResult.PROFILE_MISSING
                else -> SyncResult.UNREACHABLE
            }
            return SyncReport(result, uploaded, failed, dao.toSync().size)
        }
        return SyncReport(SyncResult.DONE, uploaded, failed, dao.toSync().size, planCount)
    }

    private var planCount: PlanCount? = null

    /**
     * Applies the profile's plan: new and changed sessions and routes come in (a route's points only
     * when its version is new), unmarked ones and those of another profile go, except what the run
     * being prepared or recorded uses. Then the server is told what the phone holds.
     */
    private suspend fun syncPlan(api: ServerClient, dao: PlanDao, profileId: Long): PlanCount {
        val manifest = api.plan(profileId)
        updateSettings { it.copy(mapStyleUrl = manifest.mapStyleUrl, mapStyleUrlDark = manifest.mapStyleUrlDark) }
        val used = inUse()
        val wanted = manifest.workouts.associateBy { it.id }
        for (w in manifest.workouts) {
            if (w.id == used.workoutId) continue
            dao.putWorkout(PlanWorkoutEntity(w.id, profileId, w.version, w.name, w.note, w.steps))
        }
        for (held in dao.workouts()) if (held.id !in wanted && held.id != used.workoutId) dao.deleteWorkout(held.id)

        val wantedRoutes = manifest.routes.associateBy { it.id }
        for (r in manifest.routes) {
            if (r.id == used.routeId) continue
            val held = dao.route(r.id)
            val points = if (held != null && held.version == r.version) held.points else api.routePoints(r.id).json
            dao.putRoute(PlanRouteEntity(r.id, profileId, r.version, r.name, r.distance, r.gain, r.loss, r.isLoop, r.hasElevation, points))
        }
        for (held in dao.routes()) if (held.id !in wantedRoutes && held.id != used.routeId) dao.deleteRoute(held.id)

        val workouts = dao.workouts().filter { it.profileId == profileId }
        val routes = dao.routes().filter { it.profileId == profileId }
        api.planReceipt(
            profileId, device,
            workouts.map { HeldItem(HeldItem.WORKOUT, it.id, it.version) } + routes.map { HeldItem(HeldItem.ROUTE, it.id, it.version) },
        )
        return PlanCount(workouts.size, routes.size)
    }

    private fun RunEntity.hasNotes() = title.isNotBlank() || note.isNotBlank() || rpe != null || context != null

    companion object {
        const val MISSING_FILE = "Le fichier de la sortie est introuvable sur le téléphone."
        const val UNKNOWN_REASON = "Le serveur a refusé le fichier."
    }
}
