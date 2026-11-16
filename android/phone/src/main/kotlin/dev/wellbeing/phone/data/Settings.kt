package dev.wellbeing.phone.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wellbeing.running.Units
import dev.wellbeing.running.Zones
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A figure that can be placed in a field of the main live screen. */
enum class Field {
    AVG_PACE, PACE, LAP_PACE, HEART_RATE, CADENCE, TIME, ELAPSED, DISTANCE, GAIN, CLOCK,
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
) {
    val splitMeters get() = if (units == Units.IMPERIAL) 1609.344 else 1000.0
}

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
        }
    }
}
