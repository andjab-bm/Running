package dev.wellbeing.phone.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wellbeing.running.Announcer
import dev.wellbeing.running.Coach
import dev.wellbeing.running.Pacer
import dev.wellbeing.running.RouteTracker
import dev.wellbeing.running.SessionEngine
import dev.wellbeing.running.Units
import dev.wellbeing.running.WorkoutRunner
import dev.wellbeing.running.Zones
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** A figure that can be placed in a field of the main live screen. */
enum class Field {
    AVG_PACE, PACE, LAP_PACE, HEART_RATE, CADENCE, TIME, ELAPSED, DISTANCE, GAIN, CLOCK,
    /** Time ahead of or behind the pace target, and the target pace at this point; placeholders without a pacer. */
    PACER_GAP, TARGET_PACE,
    /** Total time, distance and the mini-map area; bottom field only. */
    COMBINED;

    companion object {
        /** Top, middle left, middle right, bottom — as approved on the prototype. */
        val defaults = listOf(AVG_PACE, CADENCE, HEART_RATE, COMBINED)
    }
}

enum class ThemeChoice { LIGHT, DARK, SYSTEM }

data class Settings(
    val serverUrl: String = "",
    val profileId: Long? = null,
    val profileName: String = "",
    val units: Units = Units.METRIC,
    val zones: Zones = Zones.default,
    val fields: List<Field> = Field.defaults,
    val autoPause: Boolean = true,
    val keepScreenOn: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Map styles of the home server, as last retrieved; empty when it has none. */
    val mapStyleUrl: String = "",
    val mapStyleUrlDark: String = "",
    /** The pace target last used, in seconds per kilometre, proposed again on the ready screen. */
    val pacerPace: Double = 360.0,
    /** The target time last entered for a route, in seconds; null before the first one. */
    val pacerTime: Double? = null,
    /** Channels of the cues: all three on, as approved on the prototype. */
    val cueVoice: Boolean = true,
    val cueSound: Boolean = true,
    val cueVibration: Boolean = true,
    /** The regular distance announcement, off by default, every [announceEvery] km or mi. */
    val announce: Boolean = false,
    val announceEvery: Double = 1.0,
    val announceAvgPace: Boolean = false,
    val announceHeartRate: Boolean = false,
    val alertTarget: Boolean = true,
    val alertPacer: Boolean = false,
    /** Seconds ahead or behind before a pacer alert: 15, 30 or 60. */
    val pacerMargin: Int = 30,
    val alertOffRoute: Boolean = true,
    /** Load the basemap of the server's map style during the run; on by default, by the owner's decision. */
    val basemap: Boolean = true,
    /** Vibrate the watch at step changes and alerts; on by default. */
    val watchVibrations: Boolean = true,
    /** Start a free run when the watch asks, app closed; off until the runner grants what it needs. */
    val startFromWatch: Boolean = false,
) {
    val splitMeters get() = if (units == Units.IMPERIAL) 1609.344 else 1000.0
}

/** The pacer alert margins offered, in seconds. */
val PACER_MARGINS = listOf(15, 30, 60)

/** The coaching engines' settings that come from the phone's: alerts and the regular announcement. */
fun Settings.coach(engine: SessionEngine.Config) = Coach.Settings(
    engine = engine,
    zoneBounds = zones.bounds.map { it.roundToInt() },
    runner = WorkoutRunner.Config(targetAlerts = alertTarget),
    tracker = RouteTracker.Config(offRouteAlerts = alertOffRoute),
    pacer = Pacer.Config(alerts = alertPacer, marginS = pacerMargin.toDouble()),
    announcer = Announcer.Config(announce, announceEvery, units, announceAvgPace, announceHeartRate),
)

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val serverUrl = stringPreferencesKey("server_url")
        val profileId = longPreferencesKey("profile_id")
        val profileName = stringPreferencesKey("profile_name")
        val units = stringPreferencesKey("units")
        val zones = stringPreferencesKey("zones")
        val fields = stringPreferencesKey("fields")
        val autoPause = booleanPreferencesKey("auto_pause")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val theme = stringPreferencesKey("theme")
        val mapStyle = stringPreferencesKey("map_style_url")
        val mapStyleDark = stringPreferencesKey("map_style_url_dark")
        val pacerPace = doublePreferencesKey("pacer_pace")
        val pacerTime = doublePreferencesKey("pacer_time")
        val cueVoice = booleanPreferencesKey("cue_voice")
        val cueSound = booleanPreferencesKey("cue_sound")
        val cueVibration = booleanPreferencesKey("cue_vibration")
        val announce = booleanPreferencesKey("announce")
        val announceEvery = doublePreferencesKey("announce_every")
        val announceAvgPace = booleanPreferencesKey("announce_avg_pace")
        val announceHeartRate = booleanPreferencesKey("announce_heart_rate")
        val alertTarget = booleanPreferencesKey("alert_target")
        val alertPacer = booleanPreferencesKey("alert_pacer")
        val pacerMargin = longPreferencesKey("pacer_margin")
        val alertOffRoute = booleanPreferencesKey("alert_off_route")
        val basemap = booleanPreferencesKey("basemap")
        val watchVibrations = booleanPreferencesKey("watch_vibrations")
        val startFromWatch = booleanPreferencesKey("start_from_watch")
    }

    val flow: Flow<Settings> = context.store.data.map(::read)

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            serverUrl = p[K.serverUrl] ?: d.serverUrl,
            profileId = p[K.profileId],
            profileName = p[K.profileName] ?: d.profileName,
            units = p[K.units]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: d.units,
            zones = p[K.zones]?.split(',')?.mapNotNull(String::toDoubleOrNull)?.takeIf { it.size == 6 }?.let(::Zones) ?: d.zones,
            fields = p[K.fields]?.split(',')?.mapNotNull { runCatching { Field.valueOf(it) }.getOrNull() }?.takeIf { it.size == 4 } ?: d.fields,
            autoPause = p[K.autoPause] ?: d.autoPause,
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            theme = p[K.theme]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: d.theme,
            mapStyleUrl = p[K.mapStyle] ?: d.mapStyleUrl,
            mapStyleUrlDark = p[K.mapStyleDark] ?: d.mapStyleUrlDark,
            pacerPace = p[K.pacerPace] ?: d.pacerPace,
            pacerTime = p[K.pacerTime],
            cueVoice = p[K.cueVoice] ?: d.cueVoice,
            cueSound = p[K.cueSound] ?: d.cueSound,
            cueVibration = p[K.cueVibration] ?: d.cueVibration,
            announce = p[K.announce] ?: d.announce,
            announceEvery = p[K.announceEvery]?.takeIf { it in dev.wellbeing.running.Announcer.INTERVALS } ?: d.announceEvery,
            announceAvgPace = p[K.announceAvgPace] ?: d.announceAvgPace,
            announceHeartRate = p[K.announceHeartRate] ?: d.announceHeartRate,
            alertTarget = p[K.alertTarget] ?: d.alertTarget,
            alertPacer = p[K.alertPacer] ?: d.alertPacer,
            pacerMargin = p[K.pacerMargin]?.toInt()?.takeIf { it in PACER_MARGINS } ?: d.pacerMargin,
            alertOffRoute = p[K.alertOffRoute] ?: d.alertOffRoute,
            basemap = p[K.basemap] ?: d.basemap,
            watchVibrations = p[K.watchVibrations] ?: d.watchVibrations,
            startFromWatch = p[K.startFromWatch] ?: d.startFromWatch,
        )
    }

    suspend fun update(change: (Settings) -> Settings) {
        context.store.edit { p ->
            val s = change(read(p))
            p[K.serverUrl] = s.serverUrl
            if (s.profileId == null) p.remove(K.profileId) else p[K.profileId] = s.profileId
            p[K.profileName] = s.profileName
            p[K.units] = s.units.name
            p[K.zones] = s.zones.bounds.joinToString(",")
            p[K.fields] = s.fields.joinToString(",") { it.name }
            p[K.autoPause] = s.autoPause
            p[K.keepScreenOn] = s.keepScreenOn
            p[K.theme] = s.theme.name
            p[K.mapStyle] = s.mapStyleUrl
            p[K.mapStyleDark] = s.mapStyleUrlDark
            p[K.pacerPace] = s.pacerPace
            if (s.pacerTime == null) p.remove(K.pacerTime) else p[K.pacerTime] = s.pacerTime
            p[K.cueVoice] = s.cueVoice
            p[K.cueSound] = s.cueSound
            p[K.cueVibration] = s.cueVibration
            p[K.announce] = s.announce
            p[K.announceEvery] = s.announceEvery
            p[K.announceAvgPace] = s.announceAvgPace
            p[K.announceHeartRate] = s.announceHeartRate
            p[K.alertTarget] = s.alertTarget
            p[K.alertPacer] = s.alertPacer
            p[K.pacerMargin] = s.pacerMargin.toLong()
            p[K.alertOffRoute] = s.alertOffRoute
            p[K.basemap] = s.basemap
            p[K.watchVibrations] = s.watchVibrations
            p[K.startFromWatch] = s.startFromWatch
        }
    }
}
