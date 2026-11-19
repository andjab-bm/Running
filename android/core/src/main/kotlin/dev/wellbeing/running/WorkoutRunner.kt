package dev.wellbeing.running

/**
 * Follows a structured session during a run, beside [SessionEngine].
 *
 * Progress in a step is measured from the engine's moving time and distance, so pauses (manual or
 * automatic) do not consume a step. A step ends on its own condition — the caller then issues a
 * lap to the engine at that instant — or on a lap press: every lap ends the current step, so the
 * laps of a guided run are its steps. Like the engine, the runner has no clock of its own: fed the
 * same events, it reaches the same state, which is what lets a run resume after the app was killed.
 */
class WorkoutRunner(
    val workout: Workout,
    /** The profile's six heart-rate zone bounds, for zone targets. */
    private val zoneBounds: List<Int>,
    private val config: Config = Config(),
) {
    data class Config(
        /** Alert when the runner stays outside the target. */
        val targetAlerts: Boolean = true,
    )

    companion object {
        /** Outside the target this long before an alert. */
        const val OUT_HOLD_MS = 15_000L
        /** At most one target alert in this interval. */
        const val ALERT_INTERVAL_MS = 30_000L
        /** The countdown is given this many seconds before a step ends. */
        const val COUNTDOWN_S = 5
        /** Steps at least this long, in time or distance, get a countdown. */
        const val COUNTDOWN_MIN_S = 30.0
        const val COUNTDOWN_MIN_M = 200.0
        /** An effort expected to last less than this is not interrupted by auto-pause. */
        const val SHORT_EFFORT_S = 120.0
        /** Pace assumed to estimate an effort's length when it has no pace target, in s/km. */
        const val EFFORT_PACE_S_PER_KM = 300.0
    }

    /** What the screens show about the current step. */
    data class Progress(
        val index: Int,
        val count: Int,
        val planned: PlannedStep,
        val movingMs: Long,
        val distance: Double,
        /** Seconds left in a timed step; null otherwise. */
        val remainingS: Double?,
        /** Metres left in a distance step; null otherwise. */
        val remainingM: Double?,
        val target: TargetState,
        val next: PlannedStep?,
    )

    /** What one update decided. */
    data class Update(
        /** The current step's condition is met: issue a lap now, then call [onLap]. */
        val endStep: Boolean,
        val cues: List<Cue>,
    )

    val steps: List<PlannedStep> = workout.unfold()

    /** Index of the current step; equals the number of steps once the session is complete. */
    var index = 0
        private set
    var started = false
        private set
    val complete get() = index >= steps.size

    private var startMoving = 0L
    private var startDist = 0.0
    private var countdownGiven = false
    private var outSince: Long? = null
    private var lastAlert: Long? = null
    private var target = TargetState.NONE

    /** The run has started: the first step begins. */
    fun start(s: Snapshot, t: Long): List<Cue> {
        if (started) return emptyList()
        started = true
        begin(s)
        return listOf(Cue.StepStarted(t, steps[0], 0, steps.size))
    }

    /** A lap was recorded (pressed, or issued at the end of a step): the next step begins. */
    fun onLap(s: Snapshot, t: Long): List<Cue> {
        if (!started || complete) return emptyList()
        index++
        begin(s)
        return if (complete) listOf(Cue.SessionComplete(t)) else listOf(Cue.StepStarted(t, steps[index], index, steps.size))
    }

    private fun begin(s: Snapshot) {
        startMoving = s.movingMs
        startDist = s.distance
        countdownGiven = false
        outSince = null
        lastAlert = null
        target = TargetState.NONE
    }

    /** Checks the end of the step, the countdown and the target after each event or clock tick. */
    fun update(s: Snapshot, t: Long): Update {
        if (!started || complete) return Update(false, emptyList())
        val step = steps[index].step
        val moving = s.movingMs - startMoving
        val dist = s.distance - startDist
        when (val e = step.end) {
            is StepEnd.Time -> if (moving >= e.seconds * 1000) return Update(true, emptyList())
            is StepEnd.Distance -> if (dist >= e.meters) return Update(true, emptyList())
            StepEnd.Lap -> Unit
        }
        val cues = mutableListOf<Cue>()
        val running = s.phase == Phase.RUNNING
        if (running && !countdownGiven) {
            val left = secondsLeft(step, moving, dist, s.pace)
            if (left != null && left <= COUNTDOWN_S && countsDown(step)) {
                countdownGiven = true
                cues += Cue.Countdown(t, maxOf(1, Math.round(left).toInt()))
            }
        }
        target = targetState(step.target, s)
        if (!running || !target.out) {
            // Paused runs stay quiet, and coming back in range ends the episode.
            outSince = null
        } else {
            val since = outSince ?: t.also { outSince = it }
            val last = lastAlert
            if (config.targetAlerts && t - since >= OUT_HOLD_MS && (last == null || t - last >= ALERT_INTERVAL_MS)) {
                lastAlert = t
                cues += Cue.TargetOut(t, target, step.target)
            }
        }
        return Update(false, cues)
    }

    fun progress(s: Snapshot): Progress? {
        if (!started || complete) return null
        val planned = steps[index]
        val moving = s.movingMs - startMoving
        val dist = s.distance - startDist
        return Progress(
            index = index,
            count = steps.size,
            planned = planned,
            movingMs = moving,
            distance = dist,
            remainingS = (planned.step.end as? StepEnd.Time)?.let { (it.seconds - moving / 1000.0).coerceAtLeast(0.0) },
            remainingM = (planned.step.end as? StepEnd.Distance)?.let { (it.meters - dist).coerceAtLeast(0.0) },
            target = targetState(planned.step.target, s),
            next = steps.getOrNull(index + 1),
        )
    }

    /** True while the current step is an effort too short to be cut by an automatic pause. */
    val suppressAutoPause: Boolean
        get() {
            if (!started || complete) return false
            val step = steps[index].step
            if (step.kind != StepKind.EFFORT) return false
            val pace = (step.target as? StepTarget.Pace)?.slow ?: EFFORT_PACE_S_PER_KM
            val seconds = when (val e = step.end) {
                is StepEnd.Time -> e.seconds
                is StepEnd.Distance -> e.meters / 1000 * pace
                StepEnd.Lap -> return false
            }
            return seconds < SHORT_EFFORT_S
        }

    private fun countsDown(step: WorkoutStep) = when (val e = step.end) {
        is StepEnd.Time -> e.seconds >= COUNTDOWN_MIN_S
        is StepEnd.Distance -> e.meters >= COUNTDOWN_MIN_M
        StepEnd.Lap -> false
    }

    private fun secondsLeft(step: WorkoutStep, movingMs: Long, dist: Double, pace: Double?): Double? = when (val e = step.end) {
        is StepEnd.Time -> e.seconds - movingMs / 1000.0
        is StepEnd.Distance -> pace?.let { (e.meters - dist) / 1000 * it }
        StepEnd.Lap -> null
    }

    private fun targetState(target: StepTarget, s: Snapshot): TargetState = when (target) {
        StepTarget.None -> TargetState.NONE
        is StepTarget.Pace -> when (val p = s.pace) {
            null -> TargetState.UNKNOWN
            else -> if (p < target.fast) TargetState.TOO_FAST else if (p > target.slow) TargetState.TOO_SLOW else TargetState.IN
        }
        is StepTarget.HrZone -> {
            val hr = s.heartRate
            val lo = zoneBounds.getOrNull(target.zone - 1)
            val hi = zoneBounds.getOrNull(target.zone)
            if (hr == null || lo == null || hi == null) TargetState.UNKNOWN
            else if (hr < lo) TargetState.BELOW else if (hr >= hi) TargetState.ABOVE else TargetState.IN
        }
    }
}
