package dev.wellbeing.running

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchProtocolV2Test {
    private val zones = listOf(95, 114, 133, 152, 171, 190)
    private val base = WatchProtocol.State(Phase.RUNNING, 1_513_000, 4262.5, 300.0, imperial = false, zoneBounds = zones)
    private val effort = WatchProtocol.StepInfo(
        StepKind.EFFORT, 6, 14, 150.0, WatchProtocol.Remaining.METERS, StepTarget.Pace(300.0, 315.0), TargetState.TOO_FAST,
    )

    /** What a version-1 reader takes from a message: the fields it knows, nothing after them. */
    private fun asVersion1(bytes: ByteArray): WatchProtocol.State? {
        val b = ByteBuffer.wrap(bytes)
        if (b.get().toInt() != 1) return null
        val phase = b.get().toInt().let { if (it < 0) null else Phase.entries.getOrNull(it) ?: return null }
        return WatchProtocol.State(phase, b.long, b.float.toDouble(), b.float.takeIf { !it.isNaN() }?.toDouble(), b.get().toInt() == 1, List(6) { b.short.toInt() })
    }

    @Test
    fun `the coaching extension round-trips for a step, a pacer and a route`() {
        val guided = base.copy(coaching = WatchProtocol.Coaching(canStartFromWatch = true, routeActive = true, offRoute = true, step = effort))
        assertEquals(guided, WatchProtocol.State.decode(guided.encode()))
        val zone = guided.copy(coaching = WatchProtocol.Coaching(false, step = effort.copy(target = StepTarget.HrZone(2), targetState = TargetState.UNKNOWN, unit = WatchProtocol.Remaining.SECONDS)))
        assertEquals(zone, WatchProtocol.State.decode(zone.encode()))
        val open = guided.copy(coaching = WatchProtocol.Coaching(false, step = effort.copy(target = StepTarget.None, targetState = TargetState.NONE, unit = WatchProtocol.Remaining.ELAPSED_SECONDS)))
        assertEquals(open, WatchProtocol.State.decode(open.encode()))
        val paced = base.copy(coaching = WatchProtocol.Coaching(canStartFromWatch = false, pacerActive = true, pacerGapS = -31.0))
        assertEquals(paced, WatchProtocol.State.decode(paced.encode()))
        val searching = WatchProtocol.State(null, 0, 0.0, null, false, zones, WatchProtocol.Coaching(canStartFromWatch = true, searchingPosition = true))
        assertEquals(searching, WatchProtocol.State.decode(searching.encode()))
    }

    @Test
    fun `the state grows by 26 bytes`() {
        val grown = base.copy(coaching = WatchProtocol.Coaching(true, step = effort)).encode().size - base.encode().size
        assertEquals(26, grown)
    }

    @Test
    fun `a version-1 watch reads a version-2 state as before, and a version-2 watch reads a version-1 phone`() {
        val v2 = base.copy(coaching = WatchProtocol.Coaching(true, step = effort))
        assertEquals(base, asVersion1(v2.encode()))
        assertContentEquals(base.encode(), v2.encode().copyOf(base.encode().size))
        // A finished run stays STOPPED for a version-1 watch; only version 2 tells "ended".
        val ended = base.copy(phase = Phase.STOPPED, coaching = WatchProtocol.Coaching(false, ended = true))
        assertEquals(Phase.STOPPED, asVersion1(ended.encode())!!.phase)
        assertEquals(true, WatchProtocol.State.decode(ended.encode())!!.coaching!!.ended)
        assertNull(WatchProtocol.State.decode(base.encode())!!.coaching)
    }

    @Test
    fun `start from the wrist, its refusal and the cue path`() {
        val start = WatchProtocol.Command(42, WatchProtocol.CommandType.START_NEW)
        assertEquals(start, WatchProtocol.Command.decode(start.encode()))
        val refused = WatchProtocol.Ack(42, false, WatchProtocol.Refusal.BATTERY_OPTIMISATION)
        assertEquals(refused, WatchProtocol.Ack.decode(refused.encode()))
        // A version-1 acknowledgement (six bytes) still reads, with no reason.
        assertEquals(WatchProtocol.Ack(7, true), WatchProtocol.Ack.decode(WatchProtocol.Ack(7, true).encode().copyOf(6)))
        for (k in CueKind.entries) assertEquals(k, WatchProtocol.decodeCue(WatchProtocol.encodeCue(k)))
        assertNull(WatchProtocol.decodeCue(byteArrayOf(1, 99)))
    }

    @Test
    fun `a truncated extension is refused, not misread`() {
        val bytes = base.copy(coaching = WatchProtocol.Coaching(true, step = effort)).encode()
        assertNull(WatchProtocol.State.decode(bytes.copyOf(bytes.size - 3)))
    }
}
