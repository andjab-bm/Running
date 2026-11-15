package dev.wellbeing.phone.sync

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

data class SyncReport(val result: SyncResult, val uploaded: Int = 0, val failed: Int = 0, val pending: Int = 0)

/**
 * One sync attempt: check the server, refresh the profile, upload pending runs oldest first,
 * apply notes, then refresh the cached list of the profile's activities.
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
) {
    suspend fun run(): SyncReport {
        val s = settings()
        val profileId = s.profileId
        if (s.serverUrl.isBlank() || profileId == null) return SyncReport(SyncResult.NOT_CONFIGURED, pending = dao.toSync().size)
        var uploaded = 0
        var failed = 0
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
                    api.patchJournal(activityId, run.title.ifBlank { null }, run.note, run.rpe)
                    dao.updateRun(run.copy(journalDirty = false))
                }
            }

            val list = api.activities(profileId)
            dao.clearServerActivities(profileId)
            dao.insertServerActivities(list.map { ServerActivityEntity(it.id, profileId, it.startT, it.title, it.distance, it.movingMs, it.avgHr, it.gain) })
        } catch (e: ApiException) {
            val result = when (e.kind) {
                ApiException.Kind.NOT_OUR_SERVER -> SyncResult.NOT_OUR_SERVER
                ApiException.Kind.PROFILE_MISSING -> SyncResult.PROFILE_MISSING
                else -> SyncResult.UNREACHABLE
            }
            return SyncReport(result, uploaded, failed, dao.toSync().size)
        }
        return SyncReport(SyncResult.DONE, uploaded, failed, dao.toSync().size)
    }

    private fun RunEntity.hasNotes() = title.isNotBlank() || note.isNotBlank() || rpe != null

    companion object {
        const val MISSING_FILE = "Le fichier de la sortie est introuvable sur le téléphone."
        const val UNKNOWN_REASON = "Le serveur a refusé le fichier."
    }
}
