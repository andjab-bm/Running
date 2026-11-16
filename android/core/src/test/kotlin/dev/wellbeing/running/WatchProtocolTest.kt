package dev.wellbeing.running

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchProtocolTest {
    @Test
    fun `state round-trips, with and without a session or a pace`() {
        val running = WatchProtocol.State(Phase.RUNNING, 1_513_000, 4262.5, 366.0, imperial = false, zoneBounds = listOf(95, 114, 133, 152, 171, 190))
        assertEquals(running, WatchProtocol.State.decode(running.encode()))
        val none = WatchProtocol.State(null, 0, 0.0, null, imperial = true, zoneBounds = listOf(90, 108, 126, 144, 162, 180))
        assertEquals(none, WatchProtocol.State.decode(none.encode()))
        for (p in Phase.entries) assertEquals(p, WatchProtocol.State.decode(running.copy(phase = p).encode())?.phase)
    }

    @Test
    fun `commands, acknowledgements and hellos round-trip`() {
        for (t in WatchProtocol.CommandType.entries) {
            val c = WatchProtocol.Command(123_456, t)
            assertEquals(c, WatchProtocol.Command.decode(c.encode()))
        }
        assertEquals(WatchProtocol.Ack(7, true), WatchProtocol.Ack.decode(WatchProtocol.Ack(7, true).encode()))
        assertEquals(WatchProtocol.Ack(8, false), WatchProtocol.Ack.decode(WatchProtocol.Ack(8, false).encode()))
        assertEquals(WatchProtocol.Hello(false), WatchProtocol.Hello.decode(WatchProtocol.Hello(false).encode()))
    }

    @Test
    fun `heart-rate batches round-trip`() {
        val batch = List(300) { HrSample(1_794_760_000_000 + it * 1000L, 120 + it % 60) }
        assertEquals(batch, WatchProtocol.decodeHr(WatchProtocol.encodeHr(batch)))
        assertEquals(emptyList(), WatchProtocol.decodeHr(WatchProtocol.encodeHr(emptyList())))
    }

    @Test
    fun `malformed payloads are refused, not misread`() {
        assertNull(WatchProtocol.State.decode(byteArrayOf()))
        assertNull(WatchProtocol.State.decode(byteArrayOf(1, 2, 3)))
        assertNull(WatchProtocol.Command.decode(byteArrayOf(1, 0, 0, 0, 1, 99)))
        assertNull(WatchProtocol.decodeHr(WatchProtocol.encodeHr(listOf(HrSample(1, 140))).dropLast(1).toByteArray()))
        // A message from another protocol version is ignored.
        assertNull(WatchProtocol.Ack.decode(WatchProtocol.Ack(7, true).encode().also { it[0] = 9 }))
    }

    @Test
    fun `samples missed during a dropout are delivered when the link returns`() {
        val buffer = HrBuffer()
        val t0 = 1_794_760_000_000
        repeat(120) { buffer.add(HrSample(t0 + it * 1000L, 140)) } // two minutes without the phone

        // A first attempt fails: nothing is lost and the order is kept.
        val attempt = buffer.take()
        assertEquals(120, attempt.size)
        buffer.restore(attempt)
        buffer.add(HrSample(t0 + 120_000, 141))

        val delivered = buffer.take()
        assertEquals(121, delivered.size)
        assertEquals(delivered.sortedBy { it.t }, delivered)
        assertEquals(t0, delivered.first().t)
        assertEquals(141, delivered.last().bpm)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `a long dropout is sent in several messages`() {
        val buffer = HrBuffer()
        repeat(1500) { buffer.add(HrSample(it * 1000L, 140)) }
        assertEquals(listOf(600, 600, 300, 0), List(4) { buffer.take().size })
    }

    @Test
    fun `the buffer keeps at most thirty minutes of measurements`() {
        val buffer = HrBuffer()
        repeat(3600) { buffer.add(HrSample(it * 1000L, 140)) } // one hour
        assertEquals(1801, buffer.size)
        assertEquals(1_799_000L, buffer.take(1).single().t)
        // Restoring old samples does not let the buffer grow past its cap either.
        buffer.restore(List(100) { HrSample(it * 1000L, 140) })
        assertEquals(1800, buffer.size)
    }
}
