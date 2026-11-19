package dev.wellbeing.running

import kotlin.math.floor

/**
 * The regular distance announcement: every [Config.every] kilometres, or miles for an imperial
 * profile, a [Cue.Split] with the distance, the total moving time and the pace of the stretch just
 * completed. Off by default. Fed with the engine's snapshots; when one position jumps over several
 * marks only the last is announced, with the pace of everything since the previous one.
 */
class Announcer(val config: Config) {
    data class Config(
        val enabled: Boolean = false,
        /** In kilometres, or miles for an imperial profile: one of [INTERVALS]. */
        val every: Double = 1.0,
        val units: Units = Units.METRIC,
        val avgPace: Boolean = false,
        val heartRate: Boolean = false,
    ) {
        val meters get() = every * if (units == Units.IMPERIAL) Format.METERS_PER_MILE else 1000.0
    }

    companion object {
        val INTERVALS = listOf(0.5, 1.0, 2.0, 5.0)
    }

    private var marks = 0
    private var markMovingMs = 0L

    fun update(s: Snapshot, t: Long): Cue.Split? {
        if (!config.enabled) return null
        val step = config.meters
        val n = floor(s.distance / step + 1e-9).toInt()
        if (n <= marks) return null
        val covered = (n - marks) * step
        val pace = (s.movingMs - markMovingMs) / 1000.0 / (covered / 1000)
        marks = n
        markMovingMs = s.movingMs
        return Cue.Split(
            t, n * step, s.movingMs, pace.takeIf { it > 0 },
            avgPace = if (config.avgPace) s.avgPace else null,
            heartRate = if (config.heartRate) s.heartRate else null,
            interval = step,
        )
    }
}
