package dev.wellbeing.running

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Resume after stopping (spec `run-recording`, change `insights-and-coaching`). */
class ResumeTest {
    @Test
    fun `stopped by mistake at 4 km and resumed two minutes later, the run goes on from 4 km`() {
        val s = Script().start().move(1_429).stop()
        val e = s.engine()
        val stopped = e.snapshot(s.t)
        assertTrue(abs(stopped.distance - 4_000) < 10)
        s.stand(120).resume()
        val e2 = s.engine()
        val resumed = e2.snapshot(s.t)
        assertEquals(Phase.RUNNING, e2.phase)
        assertEquals(stopped.distance, resumed.distance, 0.01)
        assertEquals(stopped.movingMs, resumed.movingMs)
        assertEquals(stopped.elapsedMs + 120_000, resumed.elapsedMs)
        s.move(60)
        assertTrue(s.engine().snapshot(s.t).distance > stopped.distance + 150)
    }

    @Test
    fun `stopped, resumed and stopped again is one run whose stop is a pause`() {
        val run = Script.resumed().engine().recorded()
        // No lap was marked: the stop's lap was reopened, so there is one lap.
        assertEquals(1, run.laps.size)
        assertEquals(listOf(true, false, true, false), run.timer.map { it.start })
        assertTrue(run.endT - run.startT - run.movingMs >= 119_000, "the two minutes on the summary are not moving time")
    }

    @Test
    fun `walking 300 m while stopped adds no distance`() {
        val s = Script().start().move(600).stop()
        val before = s.engine().snapshot(s.t).distance
        s.move(110, speed = 2.8).resume()
        assertEquals(before, s.engine().snapshot(s.t).distance, 0.01)
        s.move(10)
        val after = s.engine().snapshot(s.t).distance
        assertTrue(after - before in 20.0..35.0, "${after - before} m in ten seconds")
    }

    @Test
    fun `a lap marked before the stop stays, the lap in progress is reopened`() {
        val s = Script().start().move(300).lap().move(200).stop()
        assertEquals(2, s.engine().recorded().laps.size)
        s.stand(30).resume().move(100).stop()
        val laps = s.engine().recorded().laps
        assertEquals(2, laps.size)
        assertTrue(abs(laps[1].movingMs - 300_000) <= 1_000, "second lap of ${laps[1].movingMs} ms")
    }

    @Test
    fun `a stopped and resumed log replays to the same run`() {
        val s = Script.resumed()
        val live = SessionEngine()
        s.events.forEach { live.on(it) }
        val rows = s.events.mapNotNull(EventCodec::encode)
        val rebuilt = SessionEngine.replay(SessionEngine.Config(), rows.map { EventCodec.decode(it)!! })
        assertEquals(live.recorded(), rebuilt.recorded())
    }

    @Test
    fun `a session resumed after a stop goes on in the same repeat at the same progress`() {
        val w = Workout(
            1, "6 × 400 m",
            listOf(
                WorkoutItem.Single(WorkoutStep(StepKind.WARMUP, StepEnd.Time(120.0))),
                WorkoutItem.Repeat(6, false, listOf(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(400.0)), WorkoutStep(StepKind.RECOVERY, StepEnd.Time(60.0)))),
            ),
        )
        val coach = Coach(workout = w)
        val s = Script().start().move(120 + 3 * 203 + 70)
        s.events.forEach { e -> coach.on(e) }
        val t = s.t
        val before = coach.runner!!.progress(coach.snapshot(t))!!
        assertEquals(7, before.index)
        val tail = Script(t0 = t).stand(90).events.drop(1)
        coach.on(Event.Stop(t))
        tail.forEach { coach.on(it) }
        val resumeAt = tail.last().t
        val out = coach.on(Event.Resume(resumeAt))
        assertEquals(Signal.RESUMED, out.signal)
        val after = coach.runner!!.progress(coach.snapshot(resumeAt))!!
        assertEquals(7, after.index)
        assertEquals(before.distance, after.distance, 0.01)
        assertEquals(before.movingMs, after.movingMs)
    }
}
