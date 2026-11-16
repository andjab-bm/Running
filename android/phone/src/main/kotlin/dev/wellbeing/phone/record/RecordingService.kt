package dev.wellbeing.phone.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.wellbeing.phone.MainActivity
import dev.wellbeing.phone.R
import dev.wellbeing.running.Event
import dev.wellbeing.running.Format
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Units
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the run alive when the screen is off or another app is in front: it owns the position
 * updates, the step and pressure sensors and the notification, and hands every reading to
 * [RunController]. It is always started from the visible ready screen, as Android requires for a
 * location service.
 */
class RecordingService : LifecycleService(), SensorEventListener {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var sensors: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private var started = false

    private var stepsSinceTick = 0
    private var pressureAltitude: Double? = null
    /** Difference between GPS and barometric altitude, tracked slowly so the barometer gives the shape. */
    private var altitudeOffset: Double? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) onLocation(l)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensors = getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        // A new session may be prepared while the previous one is still winding down.
        if (!started || ticker?.isActive != true) {
            ticker?.cancel()
            wakeLock?.takeIf { it.isHeld }?.release()
            begin()
        }
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission") // checked in onStartCommand
    private fun begin() {
        started = true
        fused.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(1000L).build(),
            locationCallback,
            Looper.getMainLooper(),
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        }
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wellbeing:run").apply { acquire(MAX_RUN_MS) }

        ticker = lifecycleScope.launch {
            var n = 0
            while (true) {
                delay(1000)
                val phase = RunController.phase
                if (phase == null || phase == Phase.STOPPED) break
                if (stepsSinceTick > 0) {
                    RunController.dispatch(Event.Steps(System.currentTimeMillis(), stepsSinceTick))
                    stepsSinceTick = 0
                }
                RunController.tick()
                if (++n % FLUSH_EVERY_S == 0) RunController.flush()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onLocation(l: Location) {
        // The fix is dated on the wall clock used for commands, corrected by its age.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        val gpsAltitude = when {
            l.hasMslAltitude() -> l.mslAltitudeMeters
            l.hasAltitude() -> l.altitude
            else -> null
        }
        val baro = pressureAltitude
        val altitude = if (baro != null && gpsAltitude != null) {
            val offset = altitudeOffset?.let { it + OFFSET_TRACKING * (gpsAltitude - baro - it) } ?: (gpsAltitude - baro)
            altitudeOffset = offset
            baro + offset
        } else gpsAltitude
        RunController.dispatch(
            Event.Location(
                t = System.currentTimeMillis() - ageMs.coerceIn(0, 10_000),
                lat = l.latitude,
                lon = l.longitude,
                altitude = altitude,
                accuracy = if (l.hasAccuracy()) l.accuracy else null,
            ),
        )
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> stepsSinceTick++
            Sensor.TYPE_PRESSURE ->
                pressureAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun notification(): Notification {
        val s = RunController.snapshot.value
        val title = getString(
            when (s?.phase) {
                Phase.RUNNING -> R.string.state_running
                Phase.PAUSED -> R.string.state_paused
                Phase.AUTO_PAUSED -> R.string.state_auto_paused
                else -> R.string.state_ready
            },
        )
        val text = if (s == null || s.phase == Phase.READY) getString(R.string.notif_ready)
        else "${Format.duration(s.movingMs / 1000.0)} · ${Format.distance(s.distance, Units.METRIC)} km"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        ticker?.cancel()
        fused.removeLocationUpdates(locationCallback)
        sensors.unregisterListener(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        RunController.flush()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_EVERY_S = 5
        /** The wake lock is released by itself after this long, whatever happens. */
        private const val MAX_RUN_MS = 12 * 60 * 60 * 1000L
        private const val OFFSET_TRACKING = 0.02
    }
}
