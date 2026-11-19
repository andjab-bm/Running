package dev.wellbeing.running

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoachTest {
    private val zones = listOf(95, 114, 133, 152, 171, 190)

    // The scripted runs head north from the origin of the plane the routes are drawn on.
    private val route = Plane.route(0.0 to 0.0, 0.0 to 6_000.0) { d -> 40 + 30 * minOf(d, 1_500.0) / 1_500.0 }.copy(id = 7, version = 3, name = "Le long du canal")
    private val workout = Workout(
        12, "3 × 800 m",
        listOf(
            WorkoutItem.Single(WorkoutStep(StepKind.WARMUP, StepEnd.Time(300.0))),
            WorkoutItem.Repeat(3, true, listOf(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(800.0), StepTarget.Pace(300.0, 330.0)), WorkoutStep(StepKind.RECOVERY, StepEnd.Time(90.0)))),
            WorkoutItem.Single(WorkoutStep(StepKind.COOLDOWN, StepEnd.Lap)),
        ),
    )
    private val settings = Coach.Settings(zoneBounds = zones)

    /** A live run: the plan first, then each event and what the coach issued, as the phone logs them. */
    private fun live(coach: Coach, script: Script): MutableList<Event> {
        val log = mutableListOf<Event>(coach.planEvent(script.t0))
        for (e in script.events) {
            log += e
            log += coach.on(e).issued
        }
        return log
    }

    /** Through the stored form and back, as after the app was killed. */
    private fun stored(log: List<Event>) = log.mapNotNull(EventCodec::encode).map { EventCodec.decode(it)!! }

    @Test
    fun `the plan survives the log`() {
        val plan = Event.Plan(1L, workout, RouteRef(7, 3, "Le long du canal"), PacerTarget.Time(2_100.0, gradeAdjusted = true))
        assertEquals(plan, EventCodec.decode(EventCodec.encode(plan)!!))
        val free = Event.Plan(1L, null, null, PacerTarget.Pace(345.0))
        assertEquals(free, EventCodec.decode(EventCodec.encode(free)!!))
    }

    @Test
    fun `a truncated log of a guided, paced, routed run gives the same step, pacer and route state`() {
        val coach = Coach.of(Event.Plan(0, workout, RouteRef(7, 3, ""), PacerTarget.Time(2_100.0, gradeAdjusted = true)), route, settings)
        val script = Script().start().move(300).move(290, speed = 3.3).move(90, speed = 2.2).move(130, speed = 3.3)
        val log = live(coach, script)
        // Killed in the second effort; everything logged so far is read back.
        val rebuilt = Coach.fromLog(stored(log), settings) { ref -> route.takeIf { ref.id == it.id } }
        val t = log.last().t
        assertEquals(coach.runner!!.index, rebuilt.runner!!.index)
        assertEquals(3, rebuilt.runner!!.index)
        val a = coach.runner!!.progress(coach.snapshot(t))!!
        val b = rebuilt.runner!!.progress(rebuilt.snapshot(t))!!
        assertEquals(a.distance, b.distance, 0.01)
        assertEquals(coach.tracker!!.state, rebuilt.tracker!!.state)
        assertNotNull(coach.pacer!!.state.gapS)
        assertEquals(coach.pacer!!.state, rebuilt.pacer!!.state)

        // Both go on alike: the same laps are issued at the same moments.
        val rest = Script(t0 = script.t).move(400, speed = 3.3)
        val more = rest.events.drop(1)
        val issuedLive = more.flatMap { coach.on(it).issued }
        val issuedRebuilt = more.flatMap { rebuilt.on(it).issued }
        assertTrue(issuedLive.isNotEmpty())
        assertEquals(issuedLive, issuedRebuilt)
        assertEquals(coach.runner!!.index, rebuilt.runner!!.index)
    }

    @Test
    fun `a lap logged just before the app was killed is not issued twice`() {
        val w = Workout(1, "Deux temps", listOf(WorkoutItem.Single(WorkoutStep(StepKind.EFFORT, StepEnd.Time(60.0))), WorkoutItem.Single(WorkoutStep(StepKind.RECOVERY, StepEnd.Time(60.0)))))
        val coach = Coach.of(Event.Plan(0, w, null, null), null, settings)
        val log = live(coach, Script().start().move(61))
        assertEquals(1, log.count { it is Event.Lap })
        val rebuilt = Coach.fromLog(stored(log), settings) { null }
        assertEquals(1, rebuilt.runner!!.index)
        assertTrue(rebuilt.tick(log.last().t + 500).issued.isEmpty())
        assertEquals(2, rebuilt.engine.recorded().laps.size + 1)
    }

    @Test
    fun `a route no longer on the phone leaves the run without route following`() {
        val coach = Coach.of(Event.Plan(0, null, RouteRef(7, 3, "x"), PacerTarget.Pace(330.0)), route, settings)
        val log = live(coach, Script().start().move(60))
        val rebuilt = Coach.fromLog(stored(log), settings) { null }
        assertEquals(null, rebuilt.tracker)
        assertTrue(abs(rebuilt.snapshot(log.last().t).distance - coach.snapshot(log.last().t).distance) < 0.01)
    }

    @Test
    fun `following the route on the scripted run reports progress along it`() {
        val coach = Coach.of(Event.Plan(0, null, RouteRef(7, 3, "x"), PacerTarget.Time(2_100.0, gradeAdjusted = false)), route, settings)
        val log = live(coach, Script().start().move(600))
        val state = coach.tracker!!.state
        assertTrue(state.joined && !state.offRoute, "$state")
        assertTrue(abs(state.along - coach.snapshot(log.last().t).distance) < 20, "along ${state.along}")
        // 2.8 m/s is 5:57 per km against an even 5:50: about 12 seconds behind after 1.7 km.
        val gap = coach.pacer!!.state.gapS!!
        assertTrue(gap in -16.0..-8.0, "gap $gap s")
    }
}
