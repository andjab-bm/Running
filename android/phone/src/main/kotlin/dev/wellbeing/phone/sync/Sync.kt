package dev.wellbeing.phone.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Entry point of syncing: the background work, the manual action and what the screens show. */
object Sync {
    private const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"
    /** The server is looked for this often on an unmetered network (WorkManager's minimum). */
    private const val PERIOD_MIN = 15L

    private val mutex = Mutex()
    private val _last = MutableStateFlow<SyncReport?>(null)
    /** Result of the most recent attempt, for the history screen. */
    val last: StateFlow<SyncReport?> = _last
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Uploads only happen on an unmetered network: the home Wi-Fi, never mobile data. */
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Runs one attempt now. Used by the worker and by the "sync now" button. */
    suspend fun run(context: Context): SyncReport = mutex.withLock {
        val app = context.applicationContext
        _running.value = true
        try {
            val report = if (!isUnmetered(app)) {
                SyncReport(SyncResult.METERED, pending = AppDb.get(app).runs().toSync().size)
            } else withContext(Dispatchers.IO) {
                val store = SettingsStore(app)
                SyncEngine(
                    dao = AppDb.get(app).runs(),
                    settings = { store.flow.first() },
                    updateSettings = { store.update(it) },
                    client = { ServerApi(it) },
                    readFit = { path -> File(app.filesDir, path).takeIf(File::exists)?.readBytes() },
                    plan = AppDb.get(app).plan(),
                    device = android.os.Build.MODEL.ifBlank { "Android" },
                    inUse = { dev.wellbeing.phone.record.RunController.inUse() },
                ).run()
            }
            _last.value = report
            report
        } finally {
            _running.value = false
        }
    }

    /**
     * Asks for a sync as soon as the phone is on an unmetered network, and keeps looking for the
     * server every quarter of an hour: joining a Wi-Fi that is not home must not postpone the
     * upload for hours.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MIN, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }

    internal fun stopPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = Sync.run(applicationContext)
        // Without a server there is nothing to look for: wait until one is configured. Otherwise the
        // quarter-hourly look-up also brings sessions and routes marked on the web in the meantime.
        if (report.result == SyncResult.NOT_CONFIGURED) Sync.stopPeriodic(applicationContext)
        return Result.success()
    }
}
