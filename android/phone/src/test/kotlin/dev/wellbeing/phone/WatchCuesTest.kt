package dev.wellbeing.phone

import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.watch.cueForWatch
import dev.wellbeing.running.CueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `watch-companion` "Coaching on the wrist": which cues vibrate the watch. */
class WatchCuesTest {
    @Test
    fun `steps and alerts vibrate the wrist, distance announcements do not`() {
        val sent = CueKind.entries.filter { cueForWatch(it, paused = false, enabled = true) }.toSet()
        assertEquals(
            setOf(CueKind.STEP, CueKind.COUNTDOWN, CueKind.COMPLETE, CueKind.TARGET, CueKind.PACER, CueKind.OFF_ROUTE, CueKind.BACK_ON_ROUTE, CueKind.ARRIVAL),
            sent,
        )
    }

    @Test
    fun `watch vibrations are a setting, on by default`() {
        assertTrue(Settings().watchVibrations)
        assertFalse(CueKind.entries.any { cueForWatch(it, paused = false, enabled = false) })
    }

    @Test
    fun `while paused only a step change or the end of the session goes to the wrist`() {
        assertEquals(setOf(CueKind.STEP, CueKind.COMPLETE), CueKind.entries.filter { cueForWatch(it, paused = true, enabled = true) }.toSet())
    }
}
