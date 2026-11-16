package dev.wellbeing.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.startExercise
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.wellbeing.running.HrSample
import dev.wellbeing.running.WatchProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Measures heart rate for the run in progress on the phone, through a Health Services exercise:
 * heart rate only, no GPS, so the watch's own sensors batch readings while its screen is off.
 * Started from the visible watch app (Android requires it for a health service) and ended when
 * the run ends or the phone has been out of reach for too long.
 */
class HrService : LifecycleService() {
    private val exercise by lazy { HealthServices.getClient(this).exerciseClient }
    private var running = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            // Readings are dated from the watch's boot; convert to the wall clock the phone uses.
            val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            for (p in update.latestMetrics.getData(DataType.HEART_RATE_BPM)) {
                val bpm = p.value.roundToInt()
                if (bpm in 30..230) WatchSession.onHeartRate(HrSample(bootMs + p.timeDurationFromBoot.toMillis(), bpm))
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) = Unit
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: SecurityException) {
            // Sensor permission missing or the app is not visible: nothing to measure.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            WatchSession.setMeasuring(true)
            lifecycleScope.launch { measure() }
        }
        return START_NOT_STICKY
    }

    private suspend fun measure() {
        val started = runCatching {
            exercise.setUpdateCallback(callback)
            val caps = exercise.getCapabilities()
            val type = if (ExerciseType.RUNNING in caps.supportedExerciseTypes) ExerciseType.RUNNING else ExerciseType.WORKOUT
            val config = ExerciseConfig.builder(type)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
            // Where the watch supports it, heart rate is delivered every five seconds even with
            // the screen off, so the phone's live figure does not lag.
            if (BatchingMode.HEART_RATE_5_SECONDS in caps.supportedBatchingModeOverrides) {
                config.setBatchingModeOverrides(setOf(BatchingMode.HEART_RATE_5_SECONDS))
            }
            exercise.startExercise(config.build())
        }.isSuccess

        if (started) {
            while (true) {
                delay(FLUSH_MS)
                WatchSession.flushHeartRate()
                val silentFor = System.currentTimeMillis() - WatchSession.stateAt.value
                // The run ended on the phone, or the phone has been gone for ten minutes.
                if (!WatchSession.sessionActive || silentFor > WatchProtocol.GIVE_UP_MS) break
            }
            runCatching { exercise.endExercise() }
        }
        WatchSession.setMeasuring(false)
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        // Shows the run on the watch face and in the recents, so one tap comes back to it.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_wave)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.notif_title)).build())
            .build()
            .apply(applicationContext)
        return builder.build()
    }

    override fun onDestroy() {
        WatchSession.setMeasuring(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_MS = 5_000L
    }
}
