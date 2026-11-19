package dev.wellbeing.running

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The recording logic of a run, free of any Android dependency.
 *
 * It is driven only by [Event]s and their timestamps, never by a clock of its own, so a run can
 * be rebuilt exactly by replaying its events (after the app was killed) and tested on a desktop.
 */
class SessionEngine(private val config: Config = Config()) {

    data class Config(
        val autoPause: Boolean = true,
        /** Length of a split: 1 000 m, or 1 609.344 m with imperial units. */
        val splitMeters: Double = 1000.0,
    )

    companion object {
        /** A fix less accurate than this is ignored. */
        const val MAX_ACCURACY_M = 25f
        /** A fix is good enough to start with when at least this accurate. */
        const val READY_ACCURACY_M = 20f
        /** A fix implying more than this from the previous one is a jump, not movement. */
        const val MAX_SPEED_MPS = 12.0
        /** After this many jumps in a row the new position is trusted (without adding distance). */
        const val MAX_CONSECUTIVE_JUMPS = 3
        /** Below this speed the runner is standing: no distance is added. */
        const val AUTO_PAUSE_ENTER_MPS = 0.6
        /** Above this speed the runner is moving again. */
        const val AUTO_PAUSE_EXIT_MPS = 1.2
        const val AUTO_PAUSE_ENTER_HOLD_MS = 5_000L
        const val AUTO_PAUSE_EXIT_HOLD_MS = 2_000L
        /** Speed used for auto-pause is measured over this window. */
        const val AUTO_PAUSE_WINDOW_MS = 3_000L
        /** Displayed pace is measured over this window. */
        const val PACE_WINDOW_MS = 10_000L
        /** Cadence is the number of steps over this window. */
        const val CADENCE_WINDOW_MS = 10_000L
        /** Cadence is shown once steps span the window minus this slack. */
        const val CADENCE_WARMUP_SLACK_MS = 2_000L
        /** Elevation must move this far from the last reference to count as climb. */
        const val ELE_HYSTERESIS_M = 2.0
        /** Weight of a new altitude reading in the smoothed elevation. */
        const val ELE_SMOOTHING = 0.2
        /** A live value (pace, heart rate) older than this is no longer shown. */
        const val STALE_MS = 15_000L
        const val EARTH_RADIUS_M = 6_371_000.0

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        /** Rebuilds an engine from a stored event log. */
        fun replay(config: Config, events: Iterable<Event>): SessionEngine =
            SessionEngine(config).also { e -> events.forEach(e::on) }
    }

    private class Fix(val t: Long, val lat: Double, val lon: Double)
    private class Mark(val t: Long, val value: Double)

    var phase = Phase.READY
        private set

    /**
     * Set by the coaching layer while a short effort is under way: the runner must not be
     * auto-paused in a 200 m sprint whatever the measured speed. Not part of the log: it is
     * derived again from the same events on replay.
     */
    var suppressAutoPause = false

    /** Whether the last position received was used (accurate, not a jump, during the run). */
    var lastFixAccepted = false
        private set

    private var startT = 0L
    private var endT = 0L
    private var lastT = 0L
    private var movingMs = 0L
    private var dist = 0.0
    /** Displacement between all accepted fixes, counted or not; used to measure speed. */
    private var raw = 0.0
    private var prev: Fix? = null
    private var jumps = 0
    private val rawMarks = ArrayDeque<Mark>()
    private val distMarks = ArrayDeque<Mark>()
    private var belowSince: Long? = null
    private var aboveSince: Long? = null
    private var ele: Double? = null
    private var eleRef: Double? = null
    private var gain = 0.0
    private val steps = ArrayDeque<Long>()
    private var hr: Int? = null
    private var hrT = 0L
    private var accuracy: Float? = null
    private var lastFixT = 0L

    private var lapStartT = 0L
    private var lapDist0 = 0.0
    private var lapMoving0 = 0L
    /** The lap the stop closed, and where it had started, so that a resume can reopen it. */
    private var closedByStop: Triple<Long, Double, Long>? = null
    private val laps = mutableListOf<LapRecord>()
    private val splits = mutableListOf<Long>()
    private var splitMoving0 = 0L
    private val samples = mutableListOf<Sample>()
    private val timer = mutableListOf<TimerEvent>()

    private val active get() = phase == Phase.RUNNING || phase == Phase.PAUSED || phase == Phase.AUTO_PAUSED

    fun on(e: Event): Signal = when (e) {
        is Event.Start -> if (phase != Phase.READY) Signal.NONE else {
            phase = Phase.RUNNING
            startT = e.t; lastT = e.t; lapStartT = e.t
            timer += TimerEvent(e.t, true)
            rawMarks.clear(); distMarks.clear()
            Signal.STARTED
        }
        is Event.Pause -> if (phase != Phase.RUNNING && phase != Phase.AUTO_PAUSED) Signal.NONE else {
            advance(e.t)
            if (phase == Phase.RUNNING) timer += TimerEvent(e.t, false)
            phase = Phase.PAUSED
            Signal.PAUSED
        }
        is Event.Resume -> if (phase != Phase.PAUSED && phase != Phase.AUTO_PAUSED && phase != Phase.STOPPED) Signal.NONE else {
            if (phase == Phase.STOPPED) reopen()
            advance(e.t)
            resume(e.t)
            Signal.RESUMED
        }
        is Event.Lap -> if (!active) Signal.NONE else {
            advance(e.t)
            closeLap(e.t, manual = true)
            Signal.LAP
        }
        is Event.Stop -> if (!active) Signal.NONE else {
            advance(e.t)
            if (phase == Phase.RUNNING) timer += TimerEvent(e.t, false)
            val open = Triple(lapStartT, lapDist0, lapMoving0)
            val before = laps.size
            closeLap(e.t, manual = false)
            closedByStop = if (laps.size > before) open else null
            phase = Phase.STOPPED
            endT = e.t
            Signal.STOPPED
        }
        is Event.Steps -> {
            repeat(e.count.coerceIn(0, 20)) { steps.addLast(e.t) }
            while (steps.isNotEmpty() && steps.first() < e.t - CADENCE_WINDOW_MS) steps.removeFirst()
            Signal.NONE
        }
        is Event.Plan -> Signal.NONE
        is Event.HeartRate -> {
            if (e.t >= hrT) { hr = e.bpm; hrT = e.t }
            Signal.NONE
        }
        is Event.Location -> onFix(e)
    }

    private fun advance(t: Long) {
        if (t <= lastT) return
        if (phase == Phase.RUNNING) movingMs += t - lastT
        lastT = t
    }

    /**
     * Undoes the end of the run, not its time: the stop's timer event stays and the resume adds a
     * start, so the gap is a pause in the file; the lap the stop closed goes on; and the position is
     * forgotten, so walking about while stopped adds no distance.
     */
    private fun reopen() {
        closedByStop?.let { (t, d, m) ->
            laps.removeAt(laps.lastIndex)
            lapStartT = t; lapDist0 = d; lapMoving0 = m
        }
        closedByStop = null
        endT = 0
        prev = null
        jumps = 0
    }

    private fun resume(t: Long) {
        phase = Phase.RUNNING
        timer += TimerEvent(t, true)
        belowSince = null; aboveSince = null
        rawMarks.clear(); distMarks.clear()
    }

    private fun closeLap(t: Long, manual: Boolean) {
        val d = dist - lapDist0
        val m = movingMs - lapMoving0
        // A lap marked just before stopping must not leave an empty last lap behind.
        if (!manual && laps.isNotEmpty() && d < 1.0 && m < 1000) return
        laps += LapRecord(lapStartT, t, d, m, manual)
        lapStartT = t; lapDist0 = dist; lapMoving0 = movingMs
    }

    private fun onFix(e: Event.Location): Signal {
        lastFixAccepted = false
        accuracy = e.accuracy
        lastFixT = e.t
        if (phase == Phase.STOPPED) return Signal.NONE
        if (e.accuracy != null && e.accuracy > MAX_ACCURACY_M) return Signal.NONE
        val fix = Fix(e.t, e.lat, e.lon)
        val p = prev
        if (p == null || phase == Phase.READY) { prev = fix; return Signal.NONE }
        val dt = (e.t - p.t) / 1000.0
        if (dt <= 0) return Signal.NONE
        advance(e.t)
        val d = haversine(p.lat, p.lon, e.lat, e.lon)
        if (d / dt > MAX_SPEED_MPS) {
            if (++jumps >= MAX_CONSECUTIVE_JUMPS) { jumps = 0; prev = fix }
            return Signal.NONE
        }
        jumps = 0
        prev = fix
        lastFixAccepted = true
        raw += d
        rawMarks.addLast(Mark(e.t, raw))
        while (rawMarks.first().t < e.t - PACE_WINDOW_MS) rawMarks.removeFirst()
        val speed = windowSpeed(rawMarks, e.t, AUTO_PAUSE_WINDOW_MS, minSpanMs = 1500) ?: (d / dt)

        if (e.altitude != null) ele = ele?.let { it + ELE_SMOOTHING * (e.altitude - it) } ?: e.altitude

        when (phase) {
            Phase.RUNNING -> {
                val still = speed < AUTO_PAUSE_ENTER_MPS
                if (!still) {
                    dist += d
                    while (dist >= (splits.size + 1) * config.splitMeters) {
                        splits += movingMs - splitMoving0
                        splitMoving0 = movingMs
                    }
                    climb()
                }
                distMarks.addLast(Mark(e.t, dist))
                while (distMarks.first().t < e.t - PACE_WINDOW_MS) distMarks.removeFirst()
                samples += Sample(e.t, e.lat, e.lon, ele, dist, cadenceAt(e.t), speed)
                if (still && config.autoPause && !suppressAutoPause) {
                    val since = belowSince ?: e.t.also { belowSince = it }
                    if (e.t - since >= AUTO_PAUSE_ENTER_HOLD_MS) {
                        // The pause is dated from the moment the runner stopped, not from its detection.
                        movingMs = max(lapMoving0, movingMs - (e.t - since))
                        timer += TimerEvent(since, false)
                        phase = Phase.AUTO_PAUSED
                        belowSince = null; aboveSince = null
                        return Signal.AUTO_PAUSED
                    }
                } else belowSince = null
            }
            Phase.AUTO_PAUSED -> {
                if (speed > AUTO_PAUSE_EXIT_MPS) {
                    val since = aboveSince ?: e.t.also { aboveSince = it }
                    if (e.t - since >= AUTO_PAUSE_EXIT_HOLD_MS) {
                        resume(e.t)
                        return Signal.AUTO_RESUMED
                    }
                } else aboveSince = null
            }
            else -> Unit
        }
        return Signal.NONE
    }

    private fun climb() {
        val now = ele ?: return
        val ref = eleRef
        when {
            ref == null -> eleRef = now
            now - ref >= ELE_HYSTERESIS_M -> { gain += now - ref; eleRef = now }
            ref - now >= ELE_HYSTERESIS_M -> eleRef = now
        }
    }

    private fun windowSpeed(marks: ArrayDeque<Mark>, now: Long, windowMs: Long, minSpanMs: Long): Double? {
        val last = marks.lastOrNull() ?: return null
        val first = marks.firstOrNull { it.t >= now - windowMs } ?: return null
        val span = last.t - first.t
        if (span < minSpanMs) return null
        return (last.value - first.value) / (span / 1000.0)
    }

    private fun cadenceAt(t: Long): Int? {
        // Until steps cover most of the window (start of the run, after a pause) the count would
        // under-report, so no value is given.
        val oldest = steps.firstOrNull() ?: return null
        if (oldest > t - CADENCE_WINDOW_MS + CADENCE_WARMUP_SLACK_MS) return null
        val n = steps.count { it > t - CADENCE_WINDOW_MS && it <= t }
        return if (n < 5) null else (n * 60_000L / CADENCE_WINDOW_MS).toInt()
    }

    private fun paceOf(ms: Long, meters: Double): Double? =
        if (meters < 20 || ms <= 0) null else (ms / 1000.0) / (meters / 1000.0)

    /** The figures to display at [now]; does not change the engine. */
    fun snapshot(now: Long): Snapshot {
        val t = max(now, lastT)
        val moving = movingMs + if (phase == Phase.RUNNING) t - lastT else 0
        val elapsed = when {
            startT == 0L -> 0L
            phase == Phase.STOPPED -> endT - startT
            else -> t - startT
        }
        val fresh = phase == Phase.RUNNING && t - lastFixT <= STALE_MS
        val speed = if (fresh) windowSpeed(distMarks, lastFixT, PACE_WINDOW_MS, minSpanMs = 3000) else null
        val splitDist = dist - splits.size * config.splitMeters
        return Snapshot(
            phase = phase,
            elapsedMs = elapsed,
            movingMs = moving,
            distance = dist,
            pace = speed?.takeIf { it >= AUTO_PAUSE_ENTER_MPS }?.let { 1000 / it },
            avgPace = paceOf(moving, dist),
            lapNumber = laps.size + 1,
            lapMovingMs = moving - lapMoving0,
            lapDistance = dist - lapDist0,
            lapPace = paceOf(moving - lapMoving0, dist - lapDist0),
            lastLap = laps.lastOrNull(),
            splitsMs = splits.toList(),
            currentSplitPace = paceOf(moving - splitMoving0, splitDist)?.takeIf { splitDist >= 30 },
            heartRate = hr?.takeIf { t - hrT <= STALE_MS },
            cadence = if (fresh) cadenceAt(lastFixT) else null,
            gain = gain,
            gpsAccuracy = accuracy,
            gpsReady = accuracy?.let { it <= READY_ACCURACY_M } ?: false,
        )
    }

    /** What has been recorded so far; complete once the run is stopped. */
    fun recorded(): RecordedRun = RecordedRun(
        startT = startT,
        endT = if (phase == Phase.STOPPED) endT else lastT,
        samples = samples.toList(),
        laps = laps.toList(),
        timer = timer.toList(),
        distance = dist,
        movingMs = movingMs,
        gain = gain,
    )
}
