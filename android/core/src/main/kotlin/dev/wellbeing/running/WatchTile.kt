package dev.wellbeing.running

/**
 * What the watch tile shows, from what the watch app already knows: the last state the phone sent
 * and the last heart rate measured. The tile measures and asks nothing itself; a run the watch has
 * not joined is unknown to it, so it then only offers to open the app.
 */
sealed interface WatchTile {
    data class Run(
        /** "En course", "En pause" or "Arrêtée". */
        val status: String,
        val time: String,
        val distance: String,
        val unit: String,
        /** Heart rate, or the placeholder when none was measured. */
        val heartRate: String,
        /** How old the figures are, in seconds. */
        val ageS: Long,
    ) : WatchTile

    /** No run known to the watch: a start control that opens the app. */
    data object Idle : WatchTile

    companion object {
        /** Older than this, the figures are not shown as a run any more. */
        const val STALE_MS = 2 * 60_000L

        fun of(state: WatchProtocol.State?, stateAt: Long, heartRate: Int?, measuring: Boolean, now: Long): WatchTile {
            val s = state ?: return Idle
            if (!measuring || now - stateAt > STALE_MS) return Idle
            val status = when (s.phase) {
                Phase.RUNNING -> "En course"
                Phase.PAUSED, Phase.AUTO_PAUSED -> "En pause"
                Phase.STOPPED -> if (s.coaching?.ended == false) "Arrêtée" else return Idle
                else -> return Idle
            }
            val u = if (s.imperial) Units.IMPERIAL else Units.METRIC
            return Run(
                status, Format.duration(s.movingMs / 1000.0), Format.distance(s.distance, u), Format.distanceUnit(u),
                heartRate?.toString() ?: Format.PLACEHOLDER, ((now - stateAt) / 1000).coerceAtLeast(0),
            )
        }

        /** "à l'instant", "il y a 25 s", "il y a 1 min". */
        fun age(seconds: Long) = when {
            seconds < 5 -> "à l'instant"
            seconds < 60 -> "il y a $seconds s"
            else -> "il y a ${seconds / 60} min"
        }
    }
}
