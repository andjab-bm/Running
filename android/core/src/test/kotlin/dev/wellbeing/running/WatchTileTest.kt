package dev.wellbeing.running

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `watch-companion` "Watch tile": what the tile shows from what the watch knows. */
class WatchTileTest {
    private val zones = listOf(95, 114, 133, 152, 171, 190)
    private val now = 1_800_000_000_000L
    private val running = WatchProtocol.State(Phase.RUNNING, 20 * 60_000L, 3_420.0, 351.0, false, zones, WatchProtocol.Coaching(false))

    @Test
    fun `twenty minutes into a joined run the tile shows the run with time, distance and heart rate`() {
        val t = WatchTile.of(running, now - 12_000, 148, measuring = true, now)
        assertIs<WatchTile.Run>(t)
        assertEquals("En course", t.status)
        assertEquals("20:00", t.time)
        assertEquals("3,42", t.distance)
        assertEquals("km", t.unit)
        assertEquals("148", t.heartRate)
        assertEquals("il y a 12 s", WatchTile.age(t.ageS))
    }

    @Test
    fun `paused and stopped-not-saved runs say so`() {
        assertEquals("En pause", (WatchTile.of(running.copy(phase = Phase.AUTO_PAUSED), now, null, true, now) as WatchTile.Run).status)
        val stopped = running.copy(phase = Phase.STOPPED, coaching = WatchProtocol.Coaching(false, ended = false))
        assertEquals("Arrêtée", (WatchTile.of(stopped, now, null, true, now) as WatchTile.Run).status)
        assertEquals(Format.PLACEHOLDER, (WatchTile.of(running, now, null, true, now) as WatchTile.Run).heartRate)
    }

    @Test
    fun `without a run the watch has joined, the tile only offers to start`() {
        // Nothing heard from the phone, or the app was never opened for this run.
        assertEquals(WatchTile.Idle, WatchTile.of(null, 0, null, measuring = false, now))
        assertEquals(WatchTile.Idle, WatchTile.of(running, now, 140, measuring = false, now))
        // Saved, or figures too old to be the run of now.
        assertEquals(WatchTile.Idle, WatchTile.of(running.copy(phase = Phase.STOPPED, coaching = WatchProtocol.Coaching(false, ended = true)), now, null, true, now))
        assertEquals(WatchTile.Idle, WatchTile.of(running, now - WatchTile.STALE_MS - 1, 140, true, now))
        assertEquals(WatchTile.Idle, WatchTile.of(running.copy(phase = Phase.READY), now, 140, true, now))
    }

    @Test
    fun `an imperial profile sees miles`() {
        val t = WatchTile.of(running.copy(imperial = true, distance = 3_218.7), now, 150, true, now) as WatchTile.Run
        assertEquals("mi", t.unit)
        assertEquals("2,00", t.distance)
        assertEquals("à l'instant", WatchTile.age(t.ageS))
        assertEquals("il y a 2 min", WatchTile.age(130))
    }
}
