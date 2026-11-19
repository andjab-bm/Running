package dev.wellbeing.running

/** One recorded point of a run. Times are Unix epoch milliseconds, distances metres. */
data class Sample(
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Smoothed elevation above mean sea level, when known. */
    val ele: Double?,
    /** Cumulative distance since the start. */
    val dist: Double,
    /** Steps per minute, both legs, when the phone measures steps. */
    val cadence: Int?,
    /** Metres per second over the last few seconds. */
    val speed: Double?,
)

data class HrSample(val t: Long, val bpm: Int)

data class LapRecord(
    val startT: Long,
    val endT: Long,
    val distance: Double,
    val movingMs: Long,
    /** False for the lap closed by the end of the run. */
    val manual: Boolean,
)

/** A timer start (run or resume) or stop (pause, auto-pause, end). */
data class TimerEvent(val t: Long, val start: Boolean)

/** Everything the engine recorded for one run; heart rate is kept apart because it arrives late. */
data class RecordedRun(
    val startT: Long,
    val endT: Long,
    val samples: List<Sample>,
    val laps: List<LapRecord>,
    val timer: List<TimerEvent>,
    val distance: Double,
    val movingMs: Long,
    val gain: Double,
)

enum class Phase { READY, RUNNING, PAUSED, AUTO_PAUSED, STOPPED }

/** Inputs of the session engine. Replaying the same events gives the same run. */
sealed interface Event {
    val t: Long

    data class Start(override val t: Long) : Event
    data class Pause(override val t: Long) : Event
    data class Resume(override val t: Long) : Event
    data class Lap(override val t: Long) : Event
    data class Stop(override val t: Long) : Event

    data class Location(
        override val t: Long,
        val lat: Double,
        val lon: Double,
        /** Metres above mean sea level. */
        val altitude: Double? = null,
        /** Horizontal accuracy radius in metres, as reported by the device. */
        val accuracy: Float? = null,
    ) : Event

    /** [count] steps detected at [t]. */
    data class Steps(override val t: Long, val count: Int) : Event

    /** Live heart rate, for display only: the saved run uses the stored heart-rate samples. */
    data class HeartRate(override val t: Long, val bpm: Int) : Event

    /**
     * What the run is done with, written first in its log so that a replay restores the coaching.
     * The session is kept whole (as it was that day); the route by identity, its points being on the phone.
     */
    data class Plan(override val t: Long, val workout: Workout?, val route: RouteRef?, val pacer: PacerTarget?) : Event
}

/** A route by identity: the phone keeps its points, and the activity keeps its name. */
data class RouteRef(val id: Long, val version: Long, val name: String)

/** What an event did, so the caller can vibrate, speak or redraw. */
enum class Signal { NONE, STARTED, PAUSED, RESUMED, LAP, STOPPED, AUTO_PAUSED, AUTO_RESUMED }

/** Figures shown live. Paces are seconds per kilometre; null means "not available". */
data class Snapshot(
    val phase: Phase,
    val elapsedMs: Long,
    val movingMs: Long,
    val distance: Double,
    val pace: Double?,
    val avgPace: Double?,
    val lapNumber: Int,
    val lapMovingMs: Long,
    val lapDistance: Double,
    val lapPace: Double?,
    val lastLap: LapRecord?,
    /** Moving time of each completed split (one unit of distance), oldest first. */
    val splitsMs: List<Long>,
    /** Pace of the split in progress, once it is long enough to mean something. */
    val currentSplitPace: Double?,
    val heartRate: Int?,
    val cadence: Int?,
    val gain: Double,
    val gpsAccuracy: Float?,
    val gpsReady: Boolean,
)
