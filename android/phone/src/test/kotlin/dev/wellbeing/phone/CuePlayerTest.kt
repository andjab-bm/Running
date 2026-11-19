package dev.wellbeing.phone

import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.coach
import dev.wellbeing.phone.record.AndroidCueOutputs
import dev.wellbeing.phone.record.Channels
import dev.wellbeing.phone.record.CueOutputs
import dev.wellbeing.phone.record.CuePlayer
import dev.wellbeing.phone.ui.cueSamples
import dev.wellbeing.running.Cue
import dev.wellbeing.running.CueKind
import dev.wellbeing.running.PlannedStep
import dev.wellbeing.running.SessionEngine
import dev.wellbeing.running.StepEnd
import dev.wellbeing.running.StepKind
import dev.wellbeing.running.TonePatterns
import dev.wellbeing.running.Units
import dev.wellbeing.running.VibrationPatterns
import dev.wellbeing.running.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `run-announcements`: three channels, calls, order of importance and stale cues. */
class CuePlayerTest {
    /** The phone's side, on a virtual clock: tones and speech take time, as they do. */
    private class Fake : CueOutputs {
        var now = 1_000_000L
        var call = false
        var voice = true
        var focusGranted = true
        var speakMs = 2_000L
        val log = mutableListOf<String>()
        var focused = 0
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()

        override fun inCall() = call
        override fun voiceReady() = voice
        override fun focus(): Boolean {
            log += "focus"
            if (focusGranted) focused++
            return focusGranted
        }
        override fun release() {
            log += "release"
            focused--
        }
        override fun tone(kind: CueKind, done: () -> Unit) {
            log += "tone:$kind"
            after(TonePatterns.lengthMs(kind).toLong(), done)
        }
        override fun speak(text: String, done: () -> Unit) {
            log += "say:$text"
            after(speakMs, done)
        }
        override fun vibrate(pattern: LongArray) {
            log += "vibrate:${pattern.toList()}"
        }
        override fun after(ms: Long, block: () -> Unit) {
            timers += now + ms to block
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
                timers.remove(next)
                now = next.first
                next.second()
            }
            now = end
        }
    }

    private fun player(out: Fake, ch: Channels = Channels(true, true, true), paused: () -> Boolean = { false }) =
        CuePlayer(out, { ch }, { cue -> "« ${cue.kind} »" }, paused, clock = { out.now })

    private val step = Cue.StepStarted(0, PlannedStep(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(400.0))), 1, 3)

    private fun every(t: Long) = listOf(
        step.copy(t = t), Cue.Countdown(t, 5), Cue.SessionComplete(t),
        Cue.TargetOut(t, dev.wellbeing.running.TargetState.TOO_FAST, dev.wellbeing.running.StepTarget.None),
        Cue.Pacer(t, -40.0), Cue.OffRoute(t, 60.0), Cue.BackOnRoute(t), Cue.Arrival(t),
        Cue.Split(t, 1_000.0, 360_000, 360.0), Cue.Paused(t, auto = true),
    )

    @Test
    fun `each kind uses every channel switched on, the tone before the words`() {
        for (cue in every(0)) {
            val out = Fake()
            player(out).add(cue.let { retime(it, out.now) })
            out.advance(20_000)
            assertEquals(
                cue.kind.toString(),
                listOf("vibrate:${VibrationPatterns.of(cue.kind).toList()}", "focus", "tone:${cue.kind}", "say:« ${cue.kind} »", "release"),
                out.log,
            )
            assertEquals(0, out.focused)
        }
    }

    @Test
    fun `vibration only gives the step-change pattern and nothing spoken or played`() {
        val out = Fake()
        player(out, Channels(voice = false, sound = false, vibration = true)).add(step.copy(t = out.now))
        out.advance(5_000)
        assertEquals(listOf("vibrate:${VibrationPatterns.of(CueKind.STEP).toList()}"), out.log)
    }

    @Test
    fun `each channel alone is used alone`() {
        for (ch in listOf(Channels(true, false, false), Channels(false, true, false))) {
            for (cue in every(0)) {
                val out = Fake()
                player(out, ch).add(retime(cue, out.now))
                out.advance(20_000)
                assertFalse(out.log.any { it.startsWith("vibrate") })
                assertEquals(ch.voice, out.log.any { it.startsWith("say") })
                assertEquals(ch.sound, out.log.any { it.startsWith("tone") })
            }
        }
    }

    @Test
    fun `during a call nothing is spoken or played, and vibration still applies`() {
        for (cue in every(0)) {
            val out = Fake().apply { call = true }
            player(out).add(retime(cue, out.now))
            out.advance(20_000)
            assertEquals(listOf("vibrate:${VibrationPatterns.of(cue.kind).toList()}"), out.log)
        }
    }

    @Test
    fun `without a French voice, sound and vibration keep working`() {
        val out = Fake().apply { voice = false }
        player(out).add(step.copy(t = out.now))
        out.advance(5_000)
        assertEquals(listOf("vibrate:${VibrationPatterns.of(CueKind.STEP).toList()}", "focus", "tone:STEP", "release"), out.log)
    }

    @Test
    fun `when audio focus is refused nothing is played`() {
        val out = Fake().apply { focusGranted = false }
        player(out, Channels(true, true, false)).add(step.copy(t = out.now))
        out.advance(5_000)
        assertEquals(listOf("focus"), out.log)
    }

    @Test
    fun `a kilometre at a step change is said after the step, one cue at a time`() {
        val out = Fake()
        val p = player(out, Channels(voice = true, sound = false, vibration = false))
        val split = Cue.Split(out.now, 2_000.0, 720_000, 360.0)
        val target = Cue.TargetOut(out.now, dev.wellbeing.running.TargetState.TOO_SLOW, dev.wellbeing.running.StepTarget.None)
        // The step is already being said when the others arrive.
        p.add(step.copy(t = out.now))
        p.add(split)
        p.add(target)
        out.advance(30_000)
        assertEquals(listOf("say:« STEP »", "say:« TARGET »", "say:« SPLIT »"), out.log.filter { it.startsWith("say") })
        // Never two at once: each focus is released before the next.
        assertEquals(List(3) { listOf("focus", "release") }.flatten(), out.log.filter { it == "focus" || it == "release" })
    }

    @Test
    fun `a kilometre that waited more than 20 seconds is dropped`() {
        val out = Fake().apply { speakMs = 12_000 }
        val p = player(out, Channels(voice = true, sound = false, vibration = false))
        p.add(step.copy(t = out.now))
        p.add(Cue.TargetOut(out.now, dev.wellbeing.running.TargetState.TOO_SLOW, dev.wellbeing.running.StepTarget.None))
        p.add(Cue.Split(out.now, 1_000.0, 360_000, 360.0))
        out.advance(60_000)
        assertEquals(listOf("say:« STEP »", "say:« TARGET »"), out.log.filter { it.startsWith("say") })
    }

    @Test
    fun `while paused only the pause itself is signalled`() {
        val out = Fake()
        var paused = true
        val p = player(out, Channels(voice = true, sound = false, vibration = false)) { paused }
        p.add(Cue.Paused(out.now, auto = true))
        out.advance(5_000)
        p.add(Cue.Split(out.now, 1_000.0, 360_000, 360.0))
        p.add(Cue.Pacer(out.now, -45.0))
        out.advance(5_000)
        paused = false
        p.add(Cue.Resumed(out.now))
        out.advance(5_000)
        assertEquals(listOf("say:« PAUSE »", "say:« PAUSE »"), out.log.filter { it.startsWith("say") })
    }

    @Test
    fun `a sample from the settings plays at once with the current channels`() {
        val out = Fake()
        val p = player(out, Channels(voice = false, sound = true, vibration = true))
        val sample = cueSamples(out.now).first().second
        p.test(sample)
        out.advance(5_000)
        assertEquals(listOf("vibrate:${VibrationPatterns.of(CueKind.STEP).toList()}", "focus", "tone:STEP", "release"), out.log)
        assertEquals(4, cueSamples(0).map { it.second.kind }.toSet().size)
    }

    @Test
    fun `tones are synthesised to the length of their pattern`() {
        for (k in CueKind.entries) {
            assertEquals(TonePatterns.lengthMs(k) * 22_050 / 1000.0, AndroidCueOutputs.synthesise(k).size.toDouble(), 10.0)
        }
    }

    @Test
    fun `the phone settings switch the alerts and the announcement of the next run`() {
        val off = Settings().coach(SessionEngine.Config())
        assertFalse(off.announcer.enabled)
        assertTrue(off.runner.targetAlerts)
        assertTrue(off.tracker.offRouteAlerts)
        assertFalse(off.pacer.alerts)
        val on = Settings(units = Units.IMPERIAL, announce = true, announceEvery = 2.0, alertPacer = true, pacerMargin = 15, alertTarget = false, alertOffRoute = false)
            .coach(SessionEngine.Config())
        assertEquals(2 * dev.wellbeing.running.Format.METERS_PER_MILE, on.announcer.meters, 0.01)
        assertTrue(on.pacer.alerts)
        assertEquals(15.0, on.pacer.marginS, 0.0)
        assertFalse(on.runner.targetAlerts)
        assertFalse(on.tracker.offRouteAlerts)
    }

    private fun retime(c: Cue, t: Long): Cue = when (c) {
        is Cue.StepStarted -> c.copy(t = t)
        is Cue.Countdown -> c.copy(t = t)
        is Cue.SessionComplete -> c.copy(t = t)
        is Cue.TargetOut -> c.copy(t = t)
        is Cue.Pacer -> c.copy(t = t)
        is Cue.OffRoute -> c.copy(t = t)
        is Cue.BackOnRoute -> c.copy(t = t)
        is Cue.Arrival -> c.copy(t = t)
        is Cue.Split -> c.copy(t = t)
        is Cue.Paused -> c.copy(t = t)
        is Cue.Resumed -> c.copy(t = t)
    }
}
