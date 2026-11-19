package dev.wellbeing.running

/**
 * What the coaching engines want the runner to know. Cues are values: the engines emit them,
 * [CueQueue] orders and filters them, and the phone and the watch only render them.
 */
enum class CueKind(
    /** Lower is more important: step changes, then alerts, then distance announcements. */
    val priority: Int,
) {
    PAUSE(0),
    STEP(0),
    COUNTDOWN(0),
    COMPLETE(0),
    TARGET(1),
    PACER(1),
    OFF_ROUTE(1),
    BACK_ON_ROUTE(1),
    ARRIVAL(1),
    SPLIT(2),
}

/** Where the current value stands against a step's target. */
enum class TargetState {
    /** The step has no target. */
    NONE,
    /** There is a target but no value to compare (no pace yet, no heart rate). */
    UNKNOWN,
    IN,
    TOO_FAST,
    TOO_SLOW,
    /** Heart rate below the zone. */
    BELOW,
    /** Heart rate above the zone. */
    ABOVE;

    val out get() = this == TOO_FAST || this == TOO_SLOW || this == BELOW || this == ABOVE
}

sealed interface Cue {
    val kind: CueKind
    /** When the cue was raised, in Unix milliseconds. */
    val t: Long

    data class StepStarted(override val t: Long, val step: PlannedStep, val index: Int, val count: Int) : Cue {
        override val kind get() = CueKind.STEP
    }

    /** The current step ends in [seconds]. */
    data class Countdown(override val t: Long, val seconds: Int) : Cue {
        override val kind get() = CueKind.COUNTDOWN
    }

    data class SessionComplete(override val t: Long) : Cue {
        override val kind get() = CueKind.COMPLETE
    }

    data class TargetOut(override val t: Long, val state: TargetState, val target: StepTarget) : Cue {
        override val kind get() = CueKind.TARGET
    }

    /** [gapS] seconds ahead of the pacer (negative when behind). */
    data class Pacer(override val t: Long, val gapS: Double) : Cue {
        override val kind get() = CueKind.PACER
    }

    data class OffRoute(override val t: Long, val distanceM: Double) : Cue {
        override val kind get() = CueKind.OFF_ROUTE
    }

    data class BackOnRoute(override val t: Long) : Cue {
        override val kind get() = CueKind.BACK_ON_ROUTE
    }

    data class Arrival(override val t: Long) : Cue {
        override val kind get() = CueKind.ARRIVAL
    }

    /** A distance mark: [distance] covered, in [movingMs], the last interval at [splitPace] s/km. */
    data class Split(
        override val t: Long,
        val distance: Double,
        val movingMs: Long,
        val splitPace: Double?,
        val avgPace: Double? = null,
        val heartRate: Int? = null,
    ) : Cue {
        override val kind get() = CueKind.SPLIT
    }

    data class Paused(override val t: Long, val auto: Boolean) : Cue {
        override val kind get() = CueKind.PAUSE
    }

    data class Resumed(override val t: Long) : Cue {
        override val kind get() = CueKind.PAUSE
    }
}

/**
 * Vibration patterns, one per kind, as the alternating off/on milliseconds that Android's
 * `VibrationEffect.createWaveform` takes. Shared by the phone and the watch so a cue feels the same
 * on both; each pattern differs in rhythm, not only in length, so it can be told apart in a pocket.
 */
object VibrationPatterns {
    fun of(kind: CueKind): LongArray = when (kind) {
        CueKind.STEP -> longArrayOf(0, 450, 150, 450)
        CueKind.COUNTDOWN -> longArrayOf(0, 90)
        CueKind.COMPLETE -> longArrayOf(0, 450, 150, 450, 150, 900)
        CueKind.TARGET -> longArrayOf(0, 120, 90, 120, 90, 120)
        CueKind.PACER -> longArrayOf(0, 300, 120, 120)
        CueKind.OFF_ROUTE -> longArrayOf(0, 700, 200, 700)
        CueKind.BACK_ON_ROUTE -> longArrayOf(0, 120, 120, 300)
        CueKind.ARRIVAL -> longArrayOf(0, 200, 100, 200, 100, 600)
        CueKind.SPLIT -> longArrayOf(0, 220)
        CueKind.PAUSE -> longArrayOf(0, 160, 160, 160)
    }
}
