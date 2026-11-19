package dev.wellbeing.running

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** Energy cost of running on a slope relative to flat ground (Minetti et al., 2002), as on the server. */
object Grade {
    /** Grades beyond ±30 % count as ±30 % (`docs/metrics.md` §5). */
    const val CLAMP = 0.30

    fun factor(grade: Double): Double {
        val g = grade.coerceIn(-CLAMP, CLAMP)
        return (155.4 * g.pow(5) - 30.4 * g.pow(4) - 43.3 * g.pow(3) + 46.3 * g * g + 19.5 * g + 3.6) / 3.6
    }
}

/** What the runner asked the pacer for. */
sealed interface PacerTarget {
    /** A fixed pace, in seconds per kilometre. */
    data class Pace(val secondsPerKm: Double) : PacerTarget
    /** A finishing time for the route, evenly or adjusted to its gradient. */
    data class Time(val seconds: Double, val gradeAdjusted: Boolean) : PacerTarget
}

/**
 * A pace plan: the moving time at which each distance should be reached. A monotone table,
 * interpolated linearly; past its end it goes on at its average pace.
 */
class PacePlan private constructor(private val d: DoubleArray, private val time: DoubleArray) {

    val length get() = d.last()
    val total get() = time.last()

    /** Planned moving time, in seconds, at [distance] metres. */
    fun timeAt(distance: Double): Double {
        if (distance <= 0) return 0.0
        if (distance >= length) return total + (distance - length) * total / length
        val i = slice(distance)
        val f = (distance - d[i]) / (d[i + 1] - d[i])
        return time[i] + f * (time[i + 1] - time[i])
    }

    /** Target pace at [distance], in seconds per kilometre. */
    fun paceAt(distance: Double): Double {
        if (distance >= length) return total / length * 1000
        val i = slice(distance.coerceAtLeast(0.0))
        return (time[i + 1] - time[i]) / (d[i + 1] - d[i]) * 1000
    }

    private fun slice(distance: Double): Int {
        var lo = 0
        var hi = d.size - 2
        while (lo < hi) {
            val m = (lo + hi + 1) / 2
            if (d[m] <= distance) lo = m else hi = m - 1
        }
        return lo
    }

    /** One split of the strategy preview: where it ends, its target pace and the planned time there. */
    data class Split(val end: Double, val pace: Double, val time: Double)

    /** The preview of the route, one split per [unit] metres (a kilometre or a mile), the last one partial. */
    fun splits(unit: Double): List<Split> {
        val out = mutableListOf<Split>()
        var from = 0.0
        while (from < length - 1e-6) {
            val to = min(from + unit, length)
            val t = timeAt(to)
            out += Split(to, (t - timeAt(from)) / (to - from) * 1000, t)
            from = to
        }
        return out
    }

    companion object {
        /** Slice length of a gradient-adjusted plan. */
        const val SLICE_M = 100.0
        /** A fixed pace has no end: the table runs far enough for any run. */
        private const val OPEN_ENDED_M = 1_000_000.0

        fun fixedPace(secondsPerKm: Double) = PacePlan(doubleArrayOf(0.0, OPEN_ENDED_M), doubleArrayOf(0.0, OPEN_ENDED_M / 1000 * secondsPerKm))

        fun even(length: Double, seconds: Double) = PacePlan(doubleArrayOf(0.0, length), doubleArrayOf(0.0, seconds))

        /**
         * Slower uphill, faster downhill, for an even effort: each 100 m slice costs its length
         * times the energy factor of its grade, and the costs are scaled so the total is [seconds].
         */
        fun gradeAdjusted(route: RoutePath, seconds: Double): PacePlan {
            val ele = route.ele ?: return even(route.length, seconds)
            fun eleAt(at: Double): Double {
                var i = 0
                while (i < route.dist.size - 2 && route.dist[i + 1] < at) i++
                val span = route.dist[i + 1] - route.dist[i]
                val f = if (span <= 0) 0.0 else ((at - route.dist[i]) / span).coerceIn(0.0, 1.0)
                return ele[i] + f * (ele[i + 1] - ele[i])
            }
            val ends = generateSequence(0.0) { (it + SLICE_M).takeIf { n -> n < route.length - 1e-6 } }.toMutableList()
            ends += route.length
            val costs = DoubleArray(ends.size - 1) { i ->
                val len = ends[i + 1] - ends[i]
                len * Grade.factor((eleAt(ends[i + 1]) - eleAt(ends[i])) / len)
            }
            val scale = seconds / costs.sum()
            val times = DoubleArray(ends.size)
            for (i in costs.indices) times[i + 1] = times[i] + costs[i] * scale
            return PacePlan(ends.toDoubleArray(), times)
        }

        fun of(target: PacerTarget, route: RoutePath?): PacePlan = when (target) {
            is PacerTarget.Pace -> fixedPace(target.secondsPerKm)
            is PacerTarget.Time -> {
                requireNotNull(route) { "A target time needs a route." }
                if (target.gradeAdjusted && route.ele != null) gradeAdjusted(route, target.seconds) else even(route.length, target.seconds)
            }
        }
    }
}

/**
 * The virtual pacer: how far ahead of or behind the plan the runner is, on moving time, at their
 * current progress — along the route when following one, else the distance covered.
 */
class Pacer(val target: PacerTarget, val plan: PacePlan, private val config: Config = Config()) {

    data class Config(
        /** Alert when the gap exceeds [marginS], at most once a minute while it lasts. */
        val alerts: Boolean = false,
        val marginS: Double = 30.0,
    )

    companion object {
        const val ALERT_REPEAT_MS = 60_000L
    }

    data class State(
        /** Seconds ahead of the plan (negative when behind); null until there is progress to compare. */
        val gapS: Double?,
        /** Target pace at the current position, in seconds per kilometre. */
        val targetPace: Double,
        /** Progress taken from distance covered rather than along the route. */
        val approximate: Boolean,
        /** The end of the route was reached: [gapS] is the final result and no longer changes. */
        val finished: Boolean,
    )

    private var gap: Double? = null
    private var position = 0.0
    private var approximate = false
    private var finished = false
    private var lastAlert: Long? = null

    // Along a route, progress counts from where the runner joined it; off it, from where they left it.
    private var joinedAt = 0.0
    private var leftAt: Double? = null
    /** Distance covered at the last position matched on the route. */
    private var distanceOnRoute = 0.0

    val state get() = State(gap, plan.paceAt(position), approximate, finished)

    /** Planned moving time to the current position, from where the runner joined the plan. */
    private fun planned() = plan.timeAt(position) - plan.timeAt(joinedAt)

    /**
     * Updates the gap from the engine's snapshot and, when following a route, the tracker's state.
     * Returns an alert when one is due.
     */
    fun update(t: Long, s: Snapshot, route: RouteTracker.State? = null): List<Cue> {
        if (finished) return emptyList()
        val progress: Double
        if (route == null) {
            position = s.distance
            approximate = false
            progress = s.distance
        } else if (!route.joined) {
            // Still on the way to the route: nothing to compare yet.
            gap = null
            return emptyList()
        } else {
            joinedAt = route.joinedAt
            if (route.offRoute) {
                val left = leftAt ?: position.also { leftAt = it }
                position = left + (s.distance - distanceOnRoute)
                approximate = true
            } else {
                leftAt = null
                distanceOnRoute = s.distance
                position = route.along
                approximate = false
            }
            progress = position - joinedAt
            if (route.arrived) {
                finished = true
                position = plan.length
                gap = plan.total - plan.timeAt(joinedAt) - s.movingMs / 1000.0
                return emptyList()
            }
        }
        if (progress <= 0.0 || s.distance <= 0.0) {
            gap = null
            return emptyList()
        }
        val g = planned() - s.movingMs / 1000.0
        gap = g
        if (!config.alerts || s.phase != Phase.RUNNING) return emptyList()
        if (abs(g) < config.marginS) { lastAlert = null; return emptyList() }
        val last = lastAlert
        if (last != null && t - last < ALERT_REPEAT_MS) return emptyList()
        lastAlert = t
        return listOf(Cue.Pacer(t, g))
    }
}
