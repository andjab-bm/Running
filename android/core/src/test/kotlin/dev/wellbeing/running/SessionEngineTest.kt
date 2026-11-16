package dev.wellbeing.running

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenarios of the `run-recording` and `live-run-screens` specs that do not need a device. */
class SessionEngineTest {
    private fun near(actual: Double, expected: Double, tol: Double, what: String) =
        assertTrue(abs(actual - expected) <= tol, "$what: expected $expected ± $tol, got $actual")

    @Test
    fun `pause and resume keep distance and moving time`() {
        val s = Script().start().move(714).pause()
        val before = s.engine().snapshot(s.t)
        near(before.distance, 2000.0, 20.0, "distance at pause")
        s.stand(90).resume()
        val after = s.engine().snapshot(s.t)
        assertEquals(before.distance, after.distance)
        assertEquals(before.movingMs, after.movingMs)
        assertEquals(before.elapsedMs + 90_000, after.elapsedMs)
        assertEquals(Phase.RUNNING, after.phase)
    }

    @Test
    fun `a manual pause is not resumed by movement`() {
        val s = Script().start().move(60).pause().move(60, speed = 1.5)
        assertEquals(Phase.PAUSED, s.engine().phase)
    }

    @Test
    fun `a position jump adds no distance`() {
        val s = Script().start().move(60)
        val before = s.engine().snapshot(s.t).distance
        s.jump(300.0).move(1)
        val after = s.engine().snapshot(s.t).distance
        assertTrue(after - before < 10, "jump added ${after - before} m")
    }

    @Test
    fun `an inaccurate fix is ignored`() {
        val e = Script().start().move(30).engine()
        val before = e.snapshot(0).distance
        e.on(Event.Location(Script().t0 + 31_000, 48.86, 2.35, null, accuracy = 60f))
        assertEquals(before, e.snapshot(0).distance)
        assertEquals(false, e.snapshot(0).gpsReady)
    }

    @Test
    fun `a five kilometre trace is measured within one percent`() {
        val s = Script().start().move(1786).stop()
        near(s.engine().recorded().distance, 1786 * 2.8, 50.0, "distance")
    }

    @Test
    fun `waiting at a traffic light auto-pauses and resumes`() {
        val s = Script().start().move(120)
        var e = s.stand(10).engine()
        assertEquals(Phase.AUTO_PAUSED, e.phase, "auto-paused within 10 s of stopping")
        val paused = s.stand(20).engine().snapshot(s.t)
        // The pause is dated from the stop: at most the detection window counts as moving.
        assertTrue(paused.movingMs in 120_000..123_000, "moving ${paused.movingMs}")
        e = s.move(5).engine()
        assertEquals(Phase.RUNNING, e.phase, "resumed within 5 s of running again")
        val timer = e.recorded().timer
        assertEquals(listOf(true, false, true), timer.map { it.start })
    }

    @Test
    fun `with auto-pause disabled moving time keeps increasing`() {
        val s = Script().start().move(120).stand(30)
        val e = s.engine(SessionEngine.Config(autoPause = false))
        assertEquals(Phase.RUNNING, e.phase)
        assertEquals(150_000, e.snapshot(s.t).movingMs)
        near(e.snapshot(s.t).distance, 120 * 2.8, 5.0, "distance")
    }

    @Test
    fun `a lap mark closes a lap and starts a new one at zero`() {
        val s = Script().start().move(200).lap().move(143).lap()
        val snap = s.engine().snapshot(s.t)
        val last = assertNotNull(snap.lastLap)
        near(last.distance, 400.0, 4.0, "lap distance")
        assertEquals(143_000, last.movingMs)
        assertEquals(3, snap.lapNumber)
        assertEquals(0.0, snap.lapDistance)
        assertEquals(0, snap.lapMovingMs)
    }

    @Test
    fun `a run without lap marks holds a single lap`() {
        val run = Script().start().move(300).stop().engine().recorded()
        assertEquals(1, run.laps.size)
        assertEquals(false, run.laps[0].manual)
        assertEquals(run.distance, run.laps[0].distance)
        assertEquals(run.movingMs, run.laps[0].movingMs)
    }

    @Test
    fun `a lap marked just before stopping leaves no empty lap`() {
        val run = Script().start().move(300).lap().stop().engine().recorded()
        assertEquals(1, run.laps.size)
    }

    @Test
    fun `current pace is steady at steady effort and blank when standing`() {
        val s = Script().start().move(30, speed = 1000 / 360.0)
        repeat(60) {
            s.move(1, speed = 1000 / 360.0)
            near(assertNotNull(s.engine().snapshot(s.t).pace), 360.0, 15.0, "pace")
        }
        val e = s.stand(4).engine(SessionEngine.Config(autoPause = false))
        s.stand(10)
        assertNull(s.engine(SessionEngine.Config(autoPause = false)).snapshot(s.t).pace)
        assertEquals(Phase.RUNNING, e.phase)
    }

    @Test
    fun `completed splits and the split in progress are reported`() {
        val s = Script().start().move(900) // 2 520 m
        val snap = s.engine().snapshot(s.t)
        assertEquals(2, snap.splitsMs.size)
        snap.splitsMs.forEach { near(it / 1000.0, 1000 / 2.8, 2.0, "split time") }
        near(assertNotNull(snap.currentSplitPace), 1000 / 2.8, 3.0, "current split pace")
        near(assertNotNull(snap.avgPace), 1000 / 2.8, 2.0, "average pace")
    }

    @Test
    fun `cadence is reported in steps per minute once enough steps are seen`() {
        val s = Script().start().move(5)
        assertNull(s.engine().snapshot(s.t).cadence)
        s.move(30)
        near(assertNotNull(s.engine().snapshot(s.t).cadence).toDouble(), 170.0, 6.0, "cadence")
    }

    @Test
    fun `live heart rate goes stale`() {
        val s = Script().start().move(10)
        val e = s.engine()
        e.on(Event.HeartRate(s.t, 150))
        assertEquals(150, e.snapshot(s.t + 5_000).heartRate)
        assertNull(e.snapshot(s.t + 20_000).heartRate)
    }

    @Test
    fun `replaying a truncated log gives the state the live engine had`() {
        val s = Script.fixture(withHr = false)
        val live = SessionEngine()
        val cut = s.events.size * 2 / 3
        s.events.take(cut).forEach(live::on)
        val at = s.events[cut - 1].t
        val rebuilt = SessionEngine.replay(SessionEngine.Config(), s.events.take(cut))
        assertEquals(live.snapshot(at), rebuilt.snapshot(at))
        assertEquals(live.recorded(), rebuilt.recorded())
        // Continuing after the rebuild ends in the same run as never having been interrupted.
        s.events.drop(cut).forEach(rebuilt::on)
        assertEquals(s.engine().recorded(), rebuilt.recorded())
    }

    @Test
    fun `stopping ends the run and freezes its figures`() {
        val s = Script().start().move(100).stop()
        val e = s.engine()
        assertEquals(Phase.STOPPED, e.phase)
        assertEquals(100_000, e.snapshot(s.t + 60_000).elapsedMs)
        assertEquals(Signal.NONE, e.on(Event.Lap(s.t + 1000)))
    }
}
