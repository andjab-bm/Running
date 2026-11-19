package dev.wellbeing.running

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Messages exchanged between the phone and the watch over the Wearable Data Layer.
 *
 * The watch is a remote: it sends heart rate and commands, and shows the state the phone pushes.
 * Payloads are small fixed binary layouts; [decode] functions return null on anything malformed.
 *
 * Version 2 (coaching) appends an extension to the state after the version-1 fields and keeps the
 * leading version byte at 1: a watch or phone still on version 1 reads the fields it knows and
 * ignores the rest, and a version-2 decoder reads a version-1 state with no extension.
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
    /** Phone → watch (version 2): a coaching cue to vibrate, received even with the watch screen off. */
    const val PATH_CUE = "/wellbeing/cue"

    /** Capability each app declares so the other can find it. */
    const val CAPABILITY_PHONE = "wellbeing_phone"
    const val CAPABILITY_WATCH = "wellbeing_watch"

    private const val VERSION: Byte = 1
    /** Marks the coaching extension of the state. */
    private const val EXTENSION_V2: Byte = 2

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
        /** Version 2: coaching and start-from-the-wrist; null from a version-1 phone. */
        val coaching: Coaching? = null,
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(BASE_SIZE + if (coaching != null) Coaching.SIZE else 0).apply {
            put(VERSION)
            put((phase?.ordinal ?: -1).toByte())
            putLong(movingMs)
            putFloat(distance.toFloat())
            putFloat(pace?.toFloat() ?: Float.NaN)
            put(if (imperial) 1 else 0)
            for (i in 0 until 6) putShort((zoneBounds.getOrNull(i) ?: 0).toShort())
            coaching?.write(this)
        }.array()

        companion object {
            private const val BASE_SIZE = 1 + 1 + 8 + 4 + 4 + 1 + 6 * 2

            fun decode(bytes: ByteArray): State? = read(bytes) { b ->
                val phase = b.get().toInt().let { if (it < 0) null else Phase.entries.getOrNull(it) ?: return@read null }
                val moving = b.long
                val distance = b.float.toDouble()
                val pace = b.float.takeIf { !it.isNaN() }?.toDouble()
                val imperial = b.get().toInt() == 1
                val zones = List(6) { b.short.toInt() }
                val coaching = if (b.remaining() > 0 && b.get() == EXTENSION_V2) Coaching.read(b) ?: return@read null else null
                State(phase, moving, distance, pace, imperial, zones, coaching)
            }
        }
    }

    /** What the remaining figure of a step counts. */
    enum class Remaining { SECONDS, METERS, ELAPSED_SECONDS }

    /** The current step of a guided run, as the coaching page shows it. */
    data class StepInfo(
        val kind: StepKind,
        val index: Int,
        val count: Int,
        /** Seconds or metres left; for a step that ends on lap, the time spent in it. */
        val remaining: Double,
        val unit: Remaining,
        val target: StepTarget,
        val targetState: TargetState,
    )

    /** The version-2 extension of the state (26 bytes). */
    data class Coaching(
        /** The phone can start a run when the watch asks (the option is on and its grants hold). */
        val canStartFromWatch: Boolean,
        /** The run is over (saved or discarded); without it, phase `STOPPED` means stopped, not saved. */
        val ended: Boolean = false,
        val pacerActive: Boolean = false,
        val routeActive: Boolean = false,
        val offRoute: Boolean = false,
        val searchingPosition: Boolean = false,
        val step: StepInfo? = null,
        /** Seconds ahead of the pace target (negative when behind). */
        val pacerGapS: Double? = null,
    ) {
        internal fun write(b: ByteBuffer) {
            b.put(EXTENSION_V2)
            var flags = 0
            listOf(canStartFromWatch, ended, pacerActive, routeActive, offRoute, searchingPosition, step != null)
                .forEachIndexed { i, on -> if (on) flags = flags or (1 shl i) }
            b.put(flags.toByte())
            b.put((step?.kind?.ordinal ?: 0).toByte())
            b.putShort((step?.index ?: 0).toShort())
            b.putShort((step?.count ?: 0).toShort())
            b.putFloat(step?.remaining?.toFloat() ?: Float.NaN)
            b.put((step?.unit?.ordinal ?: 0).toByte())
            when (val t = step?.target) {
                is StepTarget.Pace -> { b.put(1); b.putFloat(t.fast.toFloat()); b.putFloat(t.slow.toFloat()) }
                is StepTarget.HrZone -> { b.put(2); b.putFloat(t.zone.toFloat()); b.putFloat(Float.NaN) }
                else -> { b.put(0); b.putFloat(Float.NaN); b.putFloat(Float.NaN) }
            }
            b.put((step?.targetState?.ordinal ?: 0).toByte())
            b.putFloat(pacerGapS?.toFloat() ?: Float.NaN)
        }

        companion object {
            /** Extension marker included. */
            const val SIZE = 1 + 1 + 1 + 2 + 2 + 4 + 1 + 1 + 4 + 4 + 1 + 4

            internal fun read(b: ByteBuffer): Coaching? {
                val flags = b.get().toInt()
                fun flag(i: Int) = flags and (1 shl i) != 0
                val kind = StepKind.entries.getOrNull(b.get().toInt()) ?: return null
                val index = b.short.toInt()
                val count = b.short.toInt()
                val remaining = b.float.toDouble()
                val unit = Remaining.entries.getOrNull(b.get().toInt()) ?: return null
                val targetType = b.get().toInt()
                val lo = b.float.toDouble()
                val hi = b.float.toDouble()
                val state = TargetState.entries.getOrNull(b.get().toInt()) ?: return null
                val gap = b.float.takeIf { !it.isNaN() }?.toDouble()
                val target = when (targetType) {
                    1 -> StepTarget.Pace(lo, hi)
                    2 -> StepTarget.HrZone(lo.toInt())
                    else -> StepTarget.None
                }
                val step = if (flag(6)) StepInfo(kind, index, count, remaining, unit, target, state) else null
                return Coaching(flag(0), flag(1), flag(2), flag(3), flag(4), flag(5), step, gap)
            }
        }
    }

    /**
     * [START_NEW] (version 2) asks the phone to prepare and start a run when nothing is prepared.
     * A version-1 phone does not know it and does not acknowledge, so the watch only offers it when
     * the state says [Coaching.canStartFromWatch].
     */
    enum class CommandType { START, PAUSE, RESUME, LAP, STOP, START_NEW }

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

    /** Why the phone could not start a run the watch asked for (version 2). */
    enum class Refusal { NONE, PERMISSION, BATTERY_OPTIMISATION, LOCATION_OFF, BUSY, OTHER }

    /** [reason] is appended in version 2, for a refused [CommandType.START_NEW]; a version-1 reader ignores it. */
    data class Ack(val id: Int, val applied: Boolean, val reason: Refusal = Refusal.NONE) {
        fun encode(): ByteArray = ByteBuffer.allocate(7).put(VERSION).putInt(id).put(if (applied) 1 else 0).put(reason.ordinal.toByte()).array()

        companion object {
            fun decode(bytes: ByteArray): Ack? = read(bytes) { b ->
                val id = b.int
                val applied = b.get().toInt() == 1
                val reason = if (b.remaining() > 0) Refusal.entries.getOrNull(b.get().toInt()) ?: Refusal.OTHER else Refusal.NONE
                Ack(id, applied, reason)
            }
        }
    }

    /** A cue for the watch to vibrate (version 2). */
    fun encodeCue(kind: CueKind): ByteArray = byteArrayOf(VERSION, kind.ordinal.toByte())

    fun decodeCue(bytes: ByteArray): CueKind? = read(bytes) { b -> CueKind.entries.getOrNull(b.get().toInt()) }

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
