package dev.wellbeing.running

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacerTest {
    private val t0 = 1_794_760_000_000L

    private fun snap(distance: Double, movingS: Double, phase: Phase = Phase.RUNNING) = Snapshot(
        phase = phase, elapsedMs = (movingS * 1000).toLong(), movingMs = (movingS * 1000).toLong(), distance = distance,
        pace = null, avgPace = null, lapNumber = 1, lapMovingMs = 0, lapDistance = 0.0, lapPace = null, lastLap = null,
        splitsMs = emptyList(), currentSplitPace = null, heartRate = null, cadence = null, gain = 0.0, gpsAccuracy = 5f, gpsReady = true,
    )

    private fun onRoute(along: Double, length: Double, arrived: Boolean = false, off: Boolean = false, joinedAt: Double = 0.0) =
        RouteTracker.State(joined = true, joinedAt = joinedAt, along = along, remaining = length - along, lateral = if (off) 120.0 else 4.0, offRoute = off, arrived = arrived)

    /** 10 km north, climbing 100 m over the first 2 km, then flat. */
    private val hilly = Plane.route(0.0 to 0.0, 0.0 to 10_000.0) { d -> 50 + 100 * minOf(d, 2_000.0) / 2_000.0 }
    private val flat = Plane.route(0.0 to 0.0, 0.0 to 10_000.0) { 50.0 }

    @Test
    fun `the grade factor matches the server's within 0,1 percent`() {
        val text = File(System.getProperty("repo.root"), "server/tests/fixtures/grade-factor.json").readText()
        val rows = Regex("\"grade\": (-?[0-9.]+), \"factor\": ([0-9.]+)").findAll(text).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertEquals(81, rows.size)
        for ((g, f) in rows) assertTrue(abs(Grade.factor(g) / f - 1) < 0.001, "grade $g: ${Grade.factor(g)} against $f")
    }

    @Test
    fun `3,00 km in 17-40 against 6-00 per km is 20 seconds ahead`() {
        val pacer = Pacer(PacerTarget.Pace(360.0), PacePlan.fixedPace(360.0))
        pacer.update(t0, snap(3_000.0, 17 * 60 + 40.0))
        assertEquals(20.0, pacer.state.gapS!!, 0.01)
        assertEquals(360.0, pacer.state.targetPace, 0.01)
    }

    @Test
    fun `a pause does not change the gap, and there is no gap before any distance`() {
        val pacer = Pacer(PacerTarget.Pace(360.0), PacePlan.fixedPace(360.0))
        pacer.update(t0, snap(0.0, 4.0))
        assertNull(pacer.state.gapS)
        val coach = Coach()
        val s = Script().start().move(120).pause().stand(60)
        s.events.forEach { coach.on(it) }
        pacer.update(s.t, coach.snapshot(s.t))
        val before = pacer.state.gapS!!
        s.resume()
        coach.on(s.events.last())
        pacer.update(s.t, coach.snapshot(s.t))
        assertEquals(before, pacer.state.gapS!!, 0.01)
    }

    @Test
    fun `following the adjusted target up a climb is neither ahead nor behind, though slower than average`() {
        val plan = PacePlan.gradeAdjusted(hilly, 58 * 60.0)
        val pacer = Pacer(PacerTarget.Time(58 * 60.0, gradeAdjusted = true), plan)
        for (d in listOf(500.0, 1_000.0, 1_900.0)) {
            pacer.update(t0, snap(d, plan.timeAt(d)), onRoute(d, hilly.length))
            assertEquals(0.0, pacer.state.gapS!!, 0.01)
            assertTrue(pacer.state.targetPace > 348.0, "target ${pacer.state.targetPace} s/km on the climb")
        }
        // Past the climb, the flat is faster than the average to make up for it.
        assertTrue(plan.paceAt(5_000.0) < 348.0)
    }

    @Test
    fun `the whole route at the adjusted target finishes on time`() {
        val plan = PacePlan.gradeAdjusted(hilly, 58 * 60.0)
        assertEquals(58 * 60.0, plan.timeAt(hilly.length), 1.0)
    }

    @Test
    fun `a flat route gets the even pace throughout, a route without elevation only the even plan`() {
        val plan = PacePlan.gradeAdjusted(flat, 58 * 60.0)
        for (d in listOf(50.0, 3_000.0, 9_950.0)) assertEquals(348.0, plan.paceAt(d), 0.01)
        val bare = Plane.route(0.0 to 0.0, 0.0 to 10_000.0)
        val p = PacePlan.of(PacerTarget.Time(58 * 60.0, gradeAdjusted = true), bare)
        assertEquals(348.0, p.paceAt(1_000.0), 0.01)
    }

    @Test
    fun `the preview of a hilly 10 km lists ten splits ending at 58-00, slower on the climb`() {
        val splits = PacePlan.gradeAdjusted(hilly, 58 * 60.0).splits(1_000.0)
        assertEquals(10, splits.size)
        assertEquals(58 * 60.0, splits.last().time, 1.0)
        assertTrue(splits[0].pace > splits[5].pace && splits[1].pace > splits[5].pace)
        assertEquals(splits[4].pace, splits[8].pace, 0.5)
    }

    @Test
    fun `the target pace becomes slower entering a climb`() {
        val road = Plane.route(0.0 to 0.0, 0.0 to 6_000.0) { d -> if (d < 3_000) 50.0 else 50 + (d - 3_000) * 0.06 }
        val plan = PacePlan.gradeAdjusted(road, 36 * 60.0)
        assertTrue(plan.paceAt(3_500.0) > plan.paceAt(2_500.0) + 30)
    }

    @Test
    fun `reaching the end 35 seconds ahead shows the final result, which then stays`() {
        val plan = PacePlan.even(10_000.0, 3_600.0)
        val pacer = Pacer(PacerTarget.Time(3_600.0, gradeAdjusted = false), plan)
        pacer.update(t0, snap(5_000.0, 1_790.0), onRoute(5_000.0, 10_000.0))
        pacer.update(t0, snap(9_990.0, 3_565.0), onRoute(9_985.0, 10_000.0, arrived = true))
        assertTrue(pacer.state.finished)
        assertEquals(35.0, pacer.state.gapS!!, 0.01)
        pacer.update(t0, snap(10_300.0, 3_700.0), onRoute(10_000.0, 10_000.0, arrived = true))
        assertEquals(35.0, pacer.state.gapS!!, 0.01)
    }

    @Test
    fun `a shortcut off the route is paced from distance covered and marked approximate`() {
        val plan = PacePlan.even(10_000.0, 3_600.0)
        val pacer = Pacer(PacerTarget.Time(3_600.0, gradeAdjusted = false), plan)
        pacer.update(t0, snap(2_000.0, 720.0), onRoute(2_000.0, 10_000.0))
        assertFalse(pacer.state.approximate)
        pacer.update(t0, snap(2_400.0, 864.0), onRoute(2_000.0, 10_000.0, off = true))
        assertTrue(pacer.state.approximate)
        assertEquals(0.0, pacer.state.gapS!!, 0.01)
        pacer.update(t0, snap(2_700.0, 972.0), onRoute(3_100.0, 10_000.0))
        assertFalse(pacer.state.approximate)
        assertEquals(144.0, pacer.state.gapS!!, 0.01)
    }

    @Test
    fun `joining the route partway paces from there`() {
        val plan = PacePlan.even(10_000.0, 3_600.0)
        val pacer = Pacer(PacerTarget.Time(3_600.0, gradeAdjusted = false), plan)
        pacer.update(t0, snap(10.0, 3.0), onRoute(1_000.0, 10_000.0, joinedAt = 1_000.0))
        pacer.update(t0, snap(1_010.0, 360.0), onRoute(2_000.0, 10_000.0, joinedAt = 1_000.0))
        assertEquals(0.0, pacer.state.gapS!!, 0.01)
    }

    @Test
    fun `falling 30 seconds behind gives one alert, repeated at most once a minute`() {
        val pacer = Pacer(PacerTarget.Pace(360.0), PacePlan.fixedPace(360.0), Pacer.Config(alerts = true))
        val alerts = (0..90).flatMap { i -> pacer.update(t0 + i * 1000L, snap(3_000.0 + i, 1_110.0 + i * 1.0)) }
        assertEquals(2, alerts.size)
        val first = alerts[0] as Cue.Pacer
        assertTrue(first.gapS <= -30 && first.gapS > -31)
        assertTrue(alerts[1].t - alerts[0].t >= 60_000)
        // No alert while paused, none with alerts off.
        val paused = Pacer(PacerTarget.Pace(360.0), PacePlan.fixedPace(360.0), Pacer.Config(alerts = true))
        assertTrue(paused.update(t0, snap(3_000.0, 1_200.0, Phase.PAUSED)).isEmpty())
        assertTrue(Pacer(PacerTarget.Pace(360.0), PacePlan.fixedPace(360.0)).update(t0, snap(3_000.0, 1_200.0)).isEmpty())
    }
}
