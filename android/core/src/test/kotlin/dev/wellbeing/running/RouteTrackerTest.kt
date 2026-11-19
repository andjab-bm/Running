package dev.wellbeing.running

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Routes and runs drawn in metres on a local plane, east (x) and north (y) of a point in Paris. */
object Plane {
    private const val LAT0 = 48.85
    private const val LON0 = 2.35
    private val ky = Math.toRadians(1.0) * SessionEngine.EARTH_RADIUS_M
    private val kx = ky * cos(Math.toRadians(LAT0))

    fun lat(y: Double) = LAT0 + y / ky
    fun lon(x: Double) = LON0 + x / kx

    /** A route through these corners, densified every 10 m. */
    fun route(vararg corners: Pair<Double, Double>, ele: ((Double) -> Double)? = null): RoutePath {
        val pts = mutableListOf(corners[0])
        for ((a, b) in corners.toList().zipWithNext()) {
            val n = maxOf(1, (hypot(b.first - a.first, b.second - a.second) / 10).toInt())
            for (i in 1..n) pts += (a.first + (b.first - a.first) * i / n) to (a.second + (b.second - a.second) * i / n)
        }
        val dist = DoubleArray(pts.size)
        for (i in 1 until pts.size) dist[i] = dist[i - 1] + hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        return RoutePath(
            1, 1, "Parcours",
            DoubleArray(pts.size) { lat(pts[it].second) },
            DoubleArray(pts.size) { lon(pts[it].first) },
            dist,
            ele?.let { f -> DoubleArray(pts.size) { f(dist[it]) } },
        )
    }

    /** Positions every second from [a] to [b] at [speed] m/s. */
    fun walk(a: Pair<Double, Double>, b: Pair<Double, Double>, speed: Double = 3.0): List<Pair<Double, Double>> {
        val n = maxOf(1, (hypot(b.first - a.first, b.second - a.second) / speed).toInt())
        return (1..n).map { i -> (a.first + (b.first - a.first) * i / n) to (a.second + (b.second - a.second) * i / n) }
    }
}

class RouteTrackerTest {
    private val t0 = 1_794_760_000_000L

    /** Feeds positions one second apart; returns the cues with the second they came at. */
    private fun RouteTracker.follow(points: List<Pair<Double, Double>>, from: Int = 0): List<Pair<Int, Cue>> =
        points.flatMapIndexed { i, (x, y) -> onFix(t0 + (from + i) * 1000L, Plane.lat(y), Plane.lon(x)).map { (from + i) to it } }

    @Test
    fun `halfway along a 10 km route, 5 km remain`() {
        val tracker = RouteTracker(Plane.route(0.0 to 0.0, 0.0 to 10_000.0))
        tracker.follow(Plane.walk(0.0 to 0.0, 0.0 to 5_000.0))
        assertTrue(abs(tracker.state.remaining - 5_000) < 15, "${tracker.state.remaining} m remaining")
        assertFalse(tracker.state.offRoute)
    }

    @Test
    fun `a figure of eight is followed in its own order at the crossing`() {
        // The two diagonals cross at (500, 500): first after 707 m, again after 3 121 m.
        val route = Plane.route(0.0 to 0.0, 1000.0 to 1000.0, 1000.0 to 0.0, 0.0 to 1000.0, 0.0 to 0.0)
        val tracker = RouteTracker(route)
        tracker.follow(Plane.walk(0.0 to 0.0, 500.0 to 500.0))
        assertTrue(abs(tracker.state.along - 707) < 15, "first passage at ${tracker.state.along}")
        tracker.follow(Plane.walk(500.0 to 500.0, 1000.0 to 1000.0) + Plane.walk(1000.0 to 1000.0, 1000.0 to 0.0) + Plane.walk(1000.0 to 0.0, 500.0 to 500.0))
        assertTrue(abs(tracker.state.along - 3121) < 15, "second passage at ${tracker.state.along}")
        assertTrue(abs(tracker.state.remaining - 1707) < 15, "${tracker.state.remaining} m remaining")
    }

    @Test
    fun `joining 1 km after the start counts from there`() {
        val tracker = RouteTracker(Plane.route(0.0 to 0.0, 0.0 to 5_000.0))
        tracker.follow(Plane.walk(0.0 to 1_000.0, 0.0 to 1_030.0))
        assertTrue(tracker.state.joined)
        assertTrue(abs(tracker.state.remaining - 3_970) < 15, "${tracker.state.remaining} m remaining")
    }

    @Test
    fun `the distance to the start is known before joining`() {
        val tracker = RouteTracker(Plane.route(0.0 to 0.0, 0.0 to 5_000.0))
        assertTrue(abs(tracker.distanceToStart(Plane.lat(-600.0), Plane.lon(0.0)) - 600) < 1)
        tracker.follow(Plane.walk(0.0 to -600.0, 0.0 to -400.0))
        assertFalse(tracker.state.joined)
        assertFalse(tracker.state.offRoute)
    }

    @Test
    fun `running past a turn gives one alert within 15 seconds, then the return is signalled once`() {
        val route = Plane.route(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0)
        val tracker = RouteTracker(route)
        val out = Plane.walk(0.0 to 0.0, 0.0 to 500.0) + Plane.walk(0.0 to 500.0, 0.0 to 560.0) + Plane.walk(0.0 to 560.0, 0.0 to 600.0)
        val cues = tracker.follow(out)
        val left = out.indexOfFirst { it.second > 540.0 }
        val alerts = cues.filter { it.second is Cue.OffRoute }
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].first - left in 9..15, "alert ${alerts[0].first - left} s after leaving")
        assertTrue(tracker.state.offRoute)

        val back = tracker.follow(Plane.walk(0.0 to 600.0, 0.0 to 500.0) + Plane.walk(0.0 to 500.0, 300.0 to 500.0), from = out.size)
        assertEquals(1, back.count { it.second is Cue.BackOnRoute })
        assertFalse(tracker.state.offRoute)
        assertTrue(abs(tracker.state.remaining - 200) < 15, "${tracker.state.remaining} m remaining")
    }

    @Test
    fun `off route for long, the alert comes back at most once a minute`() {
        val tracker = RouteTracker(Plane.route(0.0 to 0.0, 0.0 to 2_000.0))
        tracker.follow(Plane.walk(0.0 to 0.0, 0.0 to 100.0))
        val cues = tracker.follow(List(150) { 80.0 to 100.0 }, from = 40)
        val alerts = cues.filter { it.second is Cue.OffRoute }
        assertEquals(3, alerts.size)
        assertTrue(alerts.zipWithNext().all { (a, b) -> b.first - a.first >= 60 })
    }

    @Test
    fun `off-route alerts can be switched off`() {
        val tracker = RouteTracker(Plane.route(0.0 to 0.0, 0.0 to 2_000.0), RouteTracker.Config(offRouteAlerts = false))
        tracker.follow(Plane.walk(0.0 to 0.0, 0.0 to 100.0))
        assertTrue(tracker.follow(List(60) { 80.0 to 100.0 }).none { it.second is Cue.OffRoute })
        assertTrue(tracker.state.offRoute)
    }

    @Test
    fun `a position wandering 20 m aside in a narrow street gives no alert`() {
        val tracker = RouteTracker(Plane.route(0.0 to 0.0, 0.0 to 2_000.0))
        val wander = Plane.walk(0.0 to 0.0, 0.0 to 300.0) + List(8) { i -> 20.0 to 300.0 + 3 * i } + Plane.walk(0.0 to 324.0, 0.0 to 600.0)
        assertTrue(tracker.follow(wander).isEmpty())
        assertFalse(tracker.state.offRoute)
    }

    @Test
    fun `arriving within 30 m of the end is announced once and tracking goes on`() {
        val loop = Plane.route(0.0 to 0.0, 0.0 to 800.0, 800.0 to 800.0, 800.0 to 0.0, 0.0 to 0.0)
        val tracker = RouteTracker(loop)
        // A loop: being at its end at the start is not an arrival.
        val start = tracker.follow(Plane.walk(0.0 to 0.0, 0.0 to 200.0))
        assertTrue(start.isEmpty())
        val rest = Plane.walk(0.0 to 200.0, 0.0 to 800.0) + Plane.walk(0.0 to 800.0, 800.0 to 800.0) + Plane.walk(800.0 to 800.0, 800.0 to 0.0) +
            Plane.walk(800.0 to 0.0, 0.0 to 0.0) + Plane.walk(0.0 to 0.0, 0.0 to 100.0)
        val cues = tracker.follow(rest)
        assertEquals(1, cues.count { it.second is Cue.Arrival })
        assertTrue(tracker.state.arrived)
    }
}
