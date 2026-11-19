package dev.wellbeing.phone.record

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.wellbeing.phone.MainActivity
import dev.wellbeing.phone.R
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Signal
import dev.wellbeing.running.WatchProtocol.Refusal

/** The grants a start from the wrist needs, besides the option itself. */
data class StartGrants(val fineLocation: Boolean, val backgroundLocation: Boolean, val batteryExempt: Boolean) {
    val all get() = fineLocation && backgroundLocation && batteryExempt
}

/**
 * Whether the phone can start a run the watch asks for, and why not. Without a session, a run is
 * prepared and started; a session prepared on the ready screen is simply started; a run in progress
 * or waiting on its summary is left alone.
 */
fun startRefusal(enabled: Boolean, grants: StartGrants, locationOn: Boolean, phase: Phase?): Refusal = when {
    !enabled -> Refusal.OTHER
    !grants.fineLocation || !grants.backgroundLocation -> Refusal.PERMISSION
    !grants.batteryExempt -> Refusal.BATTERY_OPTIMISATION
    !locationOn -> Refusal.LOCATION_OFF
    phase != null && phase != Phase.READY -> Refusal.BUSY
    else -> Refusal.NONE
}

/** The sentence the phone and the watch show for a refusal. */
fun refusalText(r: Refusal) = when (r) {
    Refusal.NONE -> ""
    Refusal.PERMISSION -> "la localisation « tout le temps » n'est plus autorisée"
    Refusal.BATTERY_OPTIMISATION -> "l'optimisation de la batterie est revenue pour l'application"
    Refusal.LOCATION_OFF -> "la localisation du téléphone est désactivée"
    Refusal.BUSY -> "une course est déjà en cours sur le téléphone"
    Refusal.OTHER -> "le téléphone n'a pas pu démarrer la course"
}

/**
 * The start from the wrist (design Decision 11): with "allow all the time" location and the
 * exemption from battery optimisation, the listener service woken by the watch's message may start
 * the location foreground service with the app closed. Whether that holds on the owner's phone is
 * task 15.1; a failure is answered with its reason and a notification that opens the ready screen.
 */
object WatchStart {
    private const val CHANNEL = "watch"
    private const val NOTIFICATION_ID = 7

    fun grants(context: Context): StartGrants {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return StartGrants(
            has(Manifest.permission.ACCESS_FINE_LOCATION),
            has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    private fun locationOn(context: Context) = context.getSystemService(LocationManager::class.java).isLocationEnabled

    /** What the phone advertises to the watch: the option is on and its grants still hold. */
    fun available(context: Context, settings: Settings) = settings.startFromWatch && grants(context).all

    /** Starts a free run with the usual settings; NONE when it is running, the reason otherwise. Main thread. */
    fun start(context: Context, settings: Settings): Refusal {
        val refusal = startRefusal(settings.startFromWatch, grants(context), locationOn(context), RunController.phase)
        if (refusal != Refusal.NONE) return refusal.also { notifyFailure(context, it) }
        return try {
            RunController.prepare(context)
            if (RunController.command(Command.START) == Signal.NONE) Refusal.OTHER.also { notifyFailure(context, it) } else Refusal.NONE
        } catch (e: IllegalStateException) {
            // Android refused the foreground service from the background (ForegroundServiceStartNotAllowedException).
            RunController.leaveReady()
            Refusal.OTHER.also { notifyFailure(context, it) }
        } catch (e: SecurityException) {
            RunController.leaveReady()
            Refusal.PERMISSION.also { notifyFailure(context, it) }
        }
    }

    private fun notifyFailure(context: Context, r: Refusal) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_watch_channel), NotificationManager.IMPORTANCE_HIGH))
        // A notification tap is itself an allowed start: it opens the ready screen.
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_watch_title))
            .setContentText("${refusalText(r).replaceFirstChar { it.uppercase() }}. Touche pour démarrer ici.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}
