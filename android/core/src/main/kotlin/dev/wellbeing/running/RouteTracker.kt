package dev.wellbeing.running

import kotlin.math.cos
import kotlin.math.hypot

/** A route as the phone holds it: parallel arrays, with the cumulative distance the server computed. */
data class RoutePath(
    val id: Long,
    val version: Long,
    val name: String,
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** Cumulative distance along the route, in metres; starts at 0. */
    val dist: DoubleArray,
    /** Elevation in metres, when the route has some. */
    val ele: DoubleArray?,
) {
    init {
        require(lat.size >= 2 && lat.size == lon.size && lat.size == dist.size) { "A route needs at least two points with their distances." }
        require(ele == null || ele.size == lat.size)
    }

    val length get() = dist.last()

    override fun equals(other: Any?) = other is RoutePath && other.id == id && other.version == version
    override fun hashCode() = (id * 31 + version).hashCode()
}

/**
 * Follows a route during a run: where along it the runner is, how far from it, and when they leave
 * it, come back to it or reach its end.
 *
 * Each position is projected on the route only within a window around the last matched point, so
 * a route that crosses itself is followed in its own order; when the runner is lost, a wider
 * search ahead finds the route again. Pure and clock-free, like the session engine.
 */
class RouteTracker(val route: RoutePath, private val config: Config = Config()) {

    data class Config(val offRouteAlerts: Boolean = true)

    companion object {
        /** Farther than this from the route is off it… */
        const val OFF_ROUTE_M = 40.0
        /** …once it has lasted this long. */
        const val OFF_ROUTE_HOLD_MS = 10_000L
        /** At most one off-route alert in this interval. */
        const val OFF_ROUTE_REPEAT_MS = 60_000L
        /** How far behind and ahead of the last match a position is looked for. */
        const val WINDOW_BACK_M = 50.0
        const val WINDOW_AHEAD_M = 300.0
        /** When lost, the route is looked for this far ahead (and the window's length behind). */
        const val REACQUIRE_AHEAD_M = 2_000.0
        /** Within this of the end, having followed the route to its last stretch, is arrival. */
        const val ARRIVAL_M = 30.0
        /** "The last stretch": no farther than this from the end along the route. */
        const val ARRIVAL_ALONG_M = 150.0
    }

    data class State(
        /** False until the runner first comes onto the route; until then, [lateral] is the distance to its start. */
        val joined: Boolean,
        /** Metres along the route where the runner first came onto it (0 from the start). */
        val joinedAt: Double,
        /** Metres along the route at the last match. */
        val along: Double,
        val remaining: Double,
        /** Distance from the route at the last position, in metres. */
        val lateral: Double,
        /** Off the route (past the hold), as the screens show it. */
        val offRoute: Boolean,
        val arrived: Boolean,
    )

    // Local plane around the route's first point; plenty at the scale of a run.
    private val lat0 = route.lat[0]
    private val kx = Math.toRadians(1.0) * SessionEngine.EARTH_RADIUS_M * cos(Math.toRadians(lat0))
    private val ky = Math.toRadians(1.0) * SessionEngine.EARTH_RADIUS_M
    private val xs = DoubleArray(route.lat.size) { (route.lon[it] - route.lon[0]) * kx }
    private val ys = DoubleArray(route.lat.size) { (route.lat[it] - lat0) * ky }

    private var joined = false
    private var joinedAt = 0.0
    private var along = 0.0
    private var lateral = Double.NaN
    private var outSince: Long? = null
    private var offRoute = false
    private var lastAlert: Long? = null
    private var arrived = false

    val state get() = State(joined, joinedAt, along, (route.length - along).coerceAtLeast(0.0), lateral, offRoute, arrived)

    /** Straight-line distance from a position to the start of the route, for the ready screen. */
    fun distanceToStart(lat: Double, lon: Double) = hypot(x(lon), y(lat))

    private fun x(lon: Double) = (lon - route.lon[0]) * kx
    private fun y(lat: Double) = (lat - lat0) * ky

    private class Match(val along: Double, val lateral: Double)

    /** The nearest projection on the route between [from] and [to] metres along it. */
    private fun nearest(px: Double, py: Double, from: Double, to: Double, earliestWithin: Double? = null): Match? {
        var best: Match? = null
        for (i in 0 until xs.size - 1) {
            val d0 = route.dist[i]
            val d1 = route.dist[i + 1]
            if (d1 < from || d0 > to) continue
            val ax = xs[i]; val ay = ys[i]
            val dx = xs[i + 1] - ax; val dy = ys[i + 1] - ay
            val len2 = dx * dx + dy * dy
            val u = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val m = Match(d0 + u * (d1 - d0), hypot(ax + u * dx - px, ay + u * dy - py))
            if (m.along < from || m.along > to) continue
            val b = best
            best = when {
                b == null -> m
                // When joining, the earliest passage within reach wins, not the closest.
                earliestWithin != null && m.lateral <= earliestWithin && b.lateral <= earliestWithin -> if (m.along < b.along) m else b
                earliestWithin != null && m.lateral <= earliestWithin -> m
                earliestWithin != null && b.lateral <= earliestWithin -> b
                m.lateral < b.lateral -> m
                else -> b
            }
        }
        return best
    }

    /** A position accepted by the session engine. */
    fun onFix(t: Long, lat: Double, lon: Double): List<Cue> {
        val px = x(lon)
        val py = y(lat)
        if (!joined) {
            // Not on the route yet (walking to its start, or joining it partway).
            val m = nearest(px, py, 0.0, route.length, earliestWithin = OFF_ROUTE_M)
            if (m == null || m.lateral > OFF_ROUTE_M) {
                lateral = distanceToStart(lat, lon)
                return emptyList()
            }
            joined = true
            joinedAt = m.along
            along = m.along
            lateral = m.lateral
            return arrival(t, lat, lon)
        }
        var m = nearest(px, py, along - WINDOW_BACK_M, along + WINDOW_AHEAD_M)
        if (m == null || m.lateral > OFF_ROUTE_M) {
            // Lost: look further ahead before calling it off route.
            val far = nearest(px, py, along - WINDOW_AHEAD_M, along + REACQUIRE_AHEAD_M)
            if (far != null && far.lateral <= OFF_ROUTE_M) m = far
        }
        val cues = mutableListOf<Cue>()
        if (m == null) return cues
        lateral = m.lateral
        if (m.lateral <= OFF_ROUTE_M) {
            along = m.along
            outSince = null
            if (offRoute) {
                offRoute = false
                cues += Cue.BackOnRoute(t)
            }
            cues += arrival(t, lat, lon)
        } else {
            val since = outSince ?: t.also { outSince = it }
            if (t - since >= OFF_ROUTE_HOLD_MS) {
                offRoute = true
                val last = lastAlert
                if (config.offRouteAlerts && (last == null || t - last >= OFF_ROUTE_REPEAT_MS)) {
                    lastAlert = t
                    cues += Cue.OffRoute(t, m.lateral)
                }
            }
        }
        return cues
    }

    private fun arrival(t: Long, lat: Double, lon: Double): List<Cue> {
        if (arrived || route.length - along > ARRIVAL_ALONG_M) return emptyList()
        val n = route.lat.size - 1
        if (hypot(x(lon) - xs[n], y(lat) - ys[n]) > ARRIVAL_M) return emptyList()
        arrived = true
        return listOf(Cue.Arrival(t))
    }
}
