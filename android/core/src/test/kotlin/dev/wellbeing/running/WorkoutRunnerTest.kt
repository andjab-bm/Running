package dev.wellbeing.running

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkoutRunnerTest {
    private val zones = listOf(95, 114, 133, 152, 171, 190)

    private fun step(kind: StepKind, end: StepEnd, target: StepTarget = StepTarget.None) = WorkoutStep(kind, end, target)
    private fun session(vararg items: WorkoutItem) = Workout(1, "Séance", items.toList())
    private fun single(kind: StepKind, end: StepEnd, target: StepTarget = StepTarget.None) = WorkoutItem.Single(step(kind, end, target))

    /** Feeds a scripted run live, as the recording service would, and keeps everything it produced. */
    private class Run(workout: Workout, zones: List<Int>, config: SessionEngine.Config = SessionEngine.Config()) {
        val coach = Coach(config, workout, zones)
        val cues = mutableListOf<Cue>()
        val log = mutableListOf<Event>()
        val signals = mutableListOf<Signal>()

        fun feed(events: List<Event>) = events.forEach { e ->
            val out = coach.on(e)
            log += e
            log += out.issued
            cues += out.cues
            signals += out.signal
        }
    }

    private fun run(workout: Workout, script: Script, config: SessionEngine.Config = SessionEngine.Config()) =
        Run(workout, zones, config).also { it.feed(script.events) }

    @Test
    fun `the run starts in the first step and a free run has none`() {
        val r = run(session(single(StepKind.WARMUP, StepEnd.Time(600.0)), single(StepKind.EFFORT, StepEnd.Time(60.0))), Script().start().move(10))
        assertEquals(0, r.coach.runner!!.index)
        val first = r.cues.filterIsInstance<Cue.StepStarted>().single()
        assertEquals(StepKind.WARMUP, first.step.step.kind)

        val free = Coach()
        Script().start().move(10).events.forEach { free.on(it) }
        assertEquals(null, free.runner)
    }

    @Test
    fun `a distance step ends at its distance and the recovery starts`() {
        val w = session(single(StepKind.EFFORT, StepEnd.Distance(400.0)), single(StepKind.RECOVERY, StepEnd.Time(90.0)))
        val r = run(w, Script().start().move(200))
        val runner = r.coach.runner!!
        assertEquals(1, runner.index)
        val lap = r.coach.engine.recorded().laps.first()
        assertTrue(abs(lap.distance - 400) < 3, "lap of ${lap.distance} m")
        assertEquals(StepKind.RECOVERY, r.cues.filterIsInstance<Cue.StepStarted>().last().step.step.kind)
    }

    @Test
    fun `pressing lap ends an open-ended recovery, or any step early`() {
        val w = session(
            single(StepKind.WARMUP, StepEnd.Time(600.0)),
            single(StepKind.EFFORT, StepEnd.Time(60.0)),
            single(StepKind.RECOVERY, StepEnd.Lap),
            single(StepKind.EFFORT, StepEnd.Time(60.0)),
        )
        // Two minutes into a ten-minute warm-up, lap: the effort starts.
        val s = Script().start().move(120).lap()
        val r = run(w, s)
        assertEquals(1, r.coach.runner!!.index)
        // The effort ends by itself; the recovery waits for the lap, whatever its length.
        r.feed(Script(t0 = s.t).move(60 + 200).events.drop(1))
        assertEquals(2, r.coach.runner!!.index)
        val lapAt = r.log.last().t
        r.feed(listOf(Event.Lap(lapAt)))
        assertEquals(3, r.coach.runner!!.index)
        assertEquals(StepKind.EFFORT, r.cues.filterIsInstance<Cue.StepStarted>().last().step.step.kind)
    }

    @Test
    fun `an open-ended step shows the time spent in it`() {
        val w = session(single(StepKind.RECOVERY, StepEnd.Lap))
        val r = run(w, Script().start().move(42))
        val p = r.coach.runner!!.progress(r.coach.snapshot(r.log.last().t))!!
        assertEquals(42_000, p.movingMs)
        assertEquals(null, p.remainingS)
        assertEquals(null, p.remainingM)
    }

    @Test
    fun `a 40-second traffic light does not consume a 1-30 recovery`() {
        val w = session(single(StepKind.RECOVERY, StepEnd.Time(90.0)), single(StepKind.EFFORT, StepEnd.Time(600.0)))
        val r = run(w, Script().start().move(30).stand(40).move(80))
        assertTrue(Signal.AUTO_PAUSED in r.signals)
        val lap = r.coach.engine.recorded().laps.first()
        assertTrue(abs(lap.movingMs - 90_000) <= 1_000, "recovery of ${lap.movingMs} ms of moving time")
        assertTrue(lap.endT - lap.startT >= 120_000, "the pause lengthened the recovery on the clock")
    }

    @Test
    fun `a manual pause does not consume a step either`() {
        val w = session(single(StepKind.RECOVERY, StepEnd.Time(90.0)), single(StepKind.EFFORT, StepEnd.Time(600.0)))
        val r = run(w, Script().start().move(30).pause().stand(60).resume().move(30))
        assertEquals(0, r.coach.runner!!.index)
        val p = r.coach.runner!!.progress(r.coach.snapshot(r.log.last().t))!!
        assertTrue(abs(p.remainingS!! - 30) <= 1, "${p.remainingS} s left")
    }

    @Test
    fun `a short effort is never auto-paused, a long one is`() {
        val sprint = run(session(single(StepKind.EFFORT, StepEnd.Distance(200.0))), Script().start().move(20).stand(20).move(60))
        assertFalse(Signal.AUTO_PAUSED in sprint.signals)
        val long = run(session(single(StepKind.EFFORT, StepEnd.Distance(3000.0))), Script().start().move(20).stand(20).move(60))
        assertTrue(Signal.AUTO_PAUSED in long.signals)
    }

    @Test
    fun `a countdown is given five seconds before a 1-30 recovery ends, not before a short one`() {
        val w = session(single(StepKind.RECOVERY, StepEnd.Time(90.0)), single(StepKind.RECOVERY, StepEnd.Time(20.0)), single(StepKind.EFFORT, StepEnd.Time(600.0)))
        val r = run(w, Script().start().move(115))
        val countdowns = r.cues.filterIsInstance<Cue.Countdown>()
        assertEquals(1, countdowns.size)
        assertEquals(5, countdowns[0].seconds)
        assertEquals(85_000, countdowns[0].t - r.log.first { it is Event.Start }.t)
    }

    @Test
    fun `too fast for 15 seconds gives one alert, repeated at most every 30 seconds, none once back in range`() {
        val target = StepTarget.Pace(300.0, 315.0)
        val w = session(single(StepKind.EFFORT, StepEnd.Time(600.0), target))
        // 4:40 per km, then 5:07.
        val r = run(w, Script().start().move(60, speed = 1000 / 280.0).move(60, speed = 1000 / 307.0))
        val alerts = r.cues.filterIsInstance<Cue.TargetOut>()
        assertTrue(alerts.isNotEmpty())
        assertTrue(alerts.all { it.state == TargetState.TOO_FAST })
        assertTrue(alerts.zipWithNext().all { (a, b) -> b.t - a.t >= 30_000 })
        assertTrue(alerts.size <= 2, "${alerts.size} alerts in one minute too fast")
        val p = r.coach.runner!!.progress(r.coach.snapshot(r.log.last().t))!!
        assertEquals(TargetState.IN, p.target)
        assertTrue(alerts.last().t < r.log.last().t - 40_000)
    }

    @Test
    fun `target alerts can be switched off`() {
        val w = session(single(StepKind.EFFORT, StepEnd.Time(600.0), StepTarget.Pace(300.0, 315.0)))
        val coach = Coach(workout = w, zoneBounds = zones, runnerConfig = WorkoutRunner.Config(targetAlerts = false))
        val cues = Script().start().move(90, speed = 1000 / 280.0).events.flatMap { coach.on(it).cues }
        assertTrue(cues.none { it is Cue.TargetOut })
    }

    @Test
    fun `a zone target without heart rate shows no feedback and gives no alert`() {
        val w = session(single(StepKind.EFFORT, StepEnd.Time(600.0), StepTarget.HrZone(2)))
        val r = run(w, Script(withHr = false).start().move(90))
        assertTrue(r.cues.none { it is Cue.TargetOut })
        assertEquals(TargetState.UNKNOWN, r.coach.runner!!.progress(r.coach.snapshot(r.log.last().t))!!.target)
    }

    @Test
    fun `a zone target compares the heart rate with the profile's bounds`() {
        val w = session(single(StepKind.EFFORT, StepEnd.Time(600.0), StepTarget.HrZone(2)))
        val r = run(w, Script().start().move(30))
        val t = r.log.last().t
        r.feed(listOf(Event.HeartRate(t, 150)))
        assertEquals(TargetState.ABOVE, r.coach.runner!!.progress(r.coach.snapshot(t))!!.target)
        r.feed(listOf(Event.HeartRate(t, 120)))
        assertEquals(TargetState.IN, r.coach.runner!!.progress(r.coach.snapshot(t))!!.target)
    }

    @Test
    fun `the end of the session is announced and the run goes on as a free run`() {
        val w = session(single(StepKind.EFFORT, StepEnd.Time(60.0)), single(StepKind.COOLDOWN, StepEnd.Time(60.0)))
        val r = run(w, Script().start().move(200))
        val runner = r.coach.runner!!
        assertTrue(runner.complete)
        assertEquals(1, r.cues.count { it is Cue.SessionComplete })
        val s = r.coach.snapshot(r.log.last().t)
        assertTrue(abs(s.movingMs - 200_000) <= 1_000)
        assertEquals(null, runner.progress(s))
        // Laps after the end are ordinary laps.
        r.feed(listOf(Event.Lap(r.log.last().t)))
        assertEquals(1, r.cues.count { it is Cue.SessionComplete })
    }

    @Test
    fun `each executed step is a lap of the run`() {
        val w = Workout(
            1, "6 × 400 m",
            listOf(
                WorkoutItem.Single(step(StepKind.WARMUP, StepEnd.Time(120.0))),
                WorkoutItem.Repeat(6, false, listOf(step(StepKind.EFFORT, StepEnd.Distance(400.0)), step(StepKind.RECOVERY, StepEnd.Time(60.0)))),
                WorkoutItem.Single(step(StepKind.COOLDOWN, StepEnd.Time(120.0))),
            ),
        )
        val r = run(w, Script().start().move(120 + 6 * (143 + 60) + 125).stop())
        assertTrue(r.coach.runner!!.complete)
        val laps = r.coach.engine.recorded().laps
        // Fourteen steps, then the stretch after the cool-down closed by the stop.
        assertEquals(15, laps.size)
        assertEquals(14, r.log.count { it is Event.Lap })
        assertTrue(laps.subList(1, 13).filterIndexed { i, _ -> i % 2 == 0 }.all { abs(it.distance - 400) < 3 })
    }

    @Test
    fun `a time step ends on the clock tick even without a position`() {
        val w = session(single(StepKind.RECOVERY, StepEnd.Time(30.0)), single(StepKind.EFFORT, StepEnd.Time(60.0)))
        val coach = Coach(workout = w, zoneBounds = zones)
        val s = Script().start().move(20)
        s.events.forEach { coach.on(it) }
        // GPS lost: no fix, only the clock.
        val out = coach.tick(s.t + 10_500)
        assertEquals(listOf<Event>(Event.Lap(s.t + 10_500)), out.issued)
        assertEquals(1, coach.runner!!.index)
    }

    @Test
    fun `replaying the log of a killed run resumes the same step at the same progress`() {
        val w = Workout(
            1, "6 × 400 m",
            listOf(
                WorkoutItem.Single(step(StepKind.WARMUP, StepEnd.Time(120.0))),
                WorkoutItem.Repeat(6, false, listOf(step(StepKind.EFFORT, StepEnd.Distance(400.0)), step(StepKind.RECOVERY, StepEnd.Time(60.0)))),
            ),
        )
        val live = run(w, Script().start().move(120 + 3 * 203 + 70))
        // Killed during the fourth effort: the log so far is replayed into a new coach.
        val again = Coach.replay(Coach(workout = w, zoneBounds = zones), live.log)
        val t = live.log.last().t
        assertEquals(live.coach.runner!!.index, again.runner!!.index)
        assertEquals(7, again.runner!!.index)
        val a = live.coach.runner!!.progress(live.coach.snapshot(t))!!
        val b = again.runner!!.progress(again.snapshot(t))!!
        assertNotNull(b.remainingM)
        assertTrue(abs(a.distance - b.distance) < 1 && abs(a.movingMs - b.movingMs) <= 10_000)
    }
}
