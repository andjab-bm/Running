package dev.wellbeing.running

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Messages exchanged between the phone and the watch over the Wearable Data Layer.
 *
 * The watch is a remote: it sends heart rate and commands, and shows the state the phone pushes.
 * Payloads are small fixed binary layouts; [decode] functions return null on anything malformed.
 */
object WatchProtocol {
    /** Phone → watch: the live state, about once per second while the watch screen is on. */
    const val PATH_STATE = "/wellbeing/state"
    /** Watch → phone: a batch of timestamped heart-rate samples. */
    const val PATH_HR = "/wellbeing/hr"
    /** Watch → phone: a session command. */
    const val PATH_COMMAND = "/wellbeing/cmd"
    /** Phone → watch: whether a command was applied. */
    const val PATH_ACK = "/wellbeing/ack"
    /** Watch → phone: the watch app is open (or its screen changed between active and dimmed). */
    const val PATH_HELLO = "/wellbeing/hello"
    /** Watch → phone: the watch app has left. */
    const val PATH_BYE = "/wellbeing/bye"

    /** Capability each app declares so the other can find it. */
    const val CAPABILITY_PHONE = "wellbeing_phone"
    const val CAPABILITY_WATCH = "wellbeing_watch"

    private const val VERSION: Byte = 1

    /** Session state as the watch shows it. [phase] is null when nothing is prepared on the phone. */
    data class State(
        val phase: Phase?,
        val movingMs: Long,
        val distance: Double,
        /** Current pace in seconds per kilometre. */
        val pace: Double?,
        val imperial: Boolean,
        /** Six heart-rate zone bounds, so the watch can colour its own reading. */
        val zoneBounds: List<Int>,
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(1 + 1 + 8 + 4 + 4 + 1 + 6 * 2).apply {
            put(VERSION)
            put((phase?.ordinal ?: -1).toByte())
            putLong(movingMs)
            putFloat(distance.toFloat())
            putFloat(pace?.toFloat() ?: Float.NaN)
            put(if (imperial) 1 else 0)
            for (i in 0 until 6) putShort((zoneBounds.getOrNull(i) ?: 0).toShort())
        }.array()

        companion object {
            fun decode(bytes: ByteArray): State? = read(bytes) { b ->
                val phase = b.get().toInt().let { if (it < 0) null else Phase.entries.getOrNull(it) ?: return@read null }
                val moving = b.long
                val distance = b.float.toDouble()
                val pace = b.float.takeIf { !it.isNaN() }?.toDouble()
                val imperial = b.get().toInt() == 1
                State(phase, moving, distance, pace, imperial, List(6) { b.short.toInt() })
            }
        }
    }

    enum class CommandType { START, PAUSE, RESUME, LAP, STOP }

    /** A command from the watch; [id] lets the watch match the acknowledgement. */
    data class Command(val id: Int, val type: CommandType) {
        fun encode(): ByteArray = ByteBuffer.allocate(6).put(VERSION).putInt(id).put(type.ordinal.toByte()).array()

        companion object {
            fun decode(bytes: ByteArray): Command? = read(bytes) { b ->
                val id = b.int
                Command(id, CommandType.entries.getOrNull(b.get().toInt()) ?: return@read null)
            }
        }
    }

    data class Ack(val id: Int, val applied: Boolean) {
        fun encode(): ByteArray = ByteBuffer.allocate(6).put(VERSION).putInt(id).put(if (applied) 1 else 0).array()

        companion object {
            fun decode(bytes: ByteArray): Ack? = read(bytes) { b -> Ack(b.int, b.get().toInt() == 1) }
        }
    }

    /** [interactive] is false while the watch screen is dimmed: the phone then slows its updates. */
    data class Hello(val interactive: Boolean) {
        fun encode(): ByteArray = byteArrayOf(VERSION, if (interactive) 1 else 0)

        companion object {
            fun decode(bytes: ByteArray): Hello? = read(bytes) { b -> Hello(b.get().toInt() == 1) }
        }
    }

    fun encodeHr(samples: List<HrSample>): ByteArray = ByteBuffer.allocate(1 + 2 + samples.size * 10).apply {
        put(VERSION)
        putShort(samples.size.toShort())
        for (s in samples) { putLong(s.t); putShort(s.bpm.toShort()) }
    }.array()

    fun decodeHr(bytes: ByteArray): List<HrSample>? = read(bytes) { b ->
        val n = b.short.toInt()
        if (n < 0 || b.remaining() != n * 10) return@read null
        List(n) { HrSample(b.long, b.short.toInt()) }
    }

    private inline fun <T> read(bytes: ByteArray, block: (ByteBuffer) -> T?): T? = try {
        val b = ByteBuffer.wrap(bytes)
        if (b.get() != VERSION) null else block(b)
    } catch (e: BufferUnderflowException) {
        null
    }

    /** Samples per message: keeps a message far below the Data Layer's size limit. */
    const val MAX_BATCH = 600
    /** The phone pushes state this often while the watch screen is dimmed. */
    const val AMBIENT_STATE_PERIOD_MS = 60_000L
    /** Without state for this long, the watch shows the phone as unreachable. */
    const val LINK_LOST_MS = 5_000L
    /** Unreachable for this long, the watch stops measuring. */
    const val GIVE_UP_MS = 10 * 60_000L
    /** A command not acknowledged within this delay was not applied. */
    const val ACK_TIMEOUT_MS = 2_000L
}

/**
 * Heart-rate samples measured on the watch and not yet delivered to the phone. When the link
 * drops they wait here, up to [capMs] of measurements, and go out when it returns.
 */
class HrBuffer(private val capMs: Long = 30 * 60_000L) {
    private val queue = ArrayDeque<HrSample>()

    val size: Int get() = queue.size

    fun add(sample: HrSample) {
        queue.addLast(sample)
        while (queue.first().t < sample.t - capMs) queue.removeFirst()
    }

    /** Takes the oldest samples for one message; give them back with [restore] if sending fails. */
    fun take(max: Int = WatchProtocol.MAX_BATCH): List<HrSample> {
        val n = minOf(max, queue.size)
        return List(n) { queue.removeFirst() }
    }

    /** Puts back, in front, samples that could not be delivered. */
    fun restore(samples: List<HrSample>) {
        for (s in samples.asReversed()) queue.addFirst(s)
        val newest = queue.lastOrNull()?.t ?: return
        while (queue.first().t < newest - capMs) queue.removeFirst()
    }

    fun clear() = queue.clear()
}
