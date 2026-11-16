package dev.wellbeing.running

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Summary figures of a saved run, computed the same way for the phone screens and the file. */
data class RunSummary(
    val distance: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val gain: Double,
    /** Seconds per kilometre. */
    val avgPace: Double?,
    val avgHeartRate: Int?,
    /** Steps per minute. */
    val avgCadence: Int?,
    val lapHeartRates: List<Int?>,
) {
    companion object {
        fun of(run: RecordedRun, hr: List<HrSample>): RunSummary {
            val join = HrJoin(hr)
            val bpm = run.samples.mapNotNull { join.at(it.t) }
            val cad = run.samples.mapNotNull { it.cadence }
            return RunSummary(
                distance = run.distance,
                movingMs = run.movingMs,
                elapsedMs = run.endT - run.startT,
                gain = run.gain,
                avgPace = if (run.distance < 20 || run.movingMs <= 0) null else (run.movingMs / 1000.0) / (run.distance / 1000.0),
                avgHeartRate = bpm.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                avgCadence = cad.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                lapHeartRates = run.laps.map { lap ->
                    run.samples.filter { it.t >= lap.startT && it.t <= lap.endT }.mapNotNull { join.at(it.t) }
                        .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                },
            )
        }
    }
}

/** Finds the heart rate measured closest to an instant, if any within [MAX_GAP_MS]. */
class HrJoin(hr: List<HrSample>) {
    private val sorted = hr.sortedBy { it.t }

    fun at(t: Long): Int? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid].t < t) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(sorted.getOrNull(lo - 1), sorted[lo]).minBy { abs(it.t - t) }
        return best.bpm.takeIf { abs(best.t - t) <= MAX_GAP_MS && it in 1..254 }
    }

    companion object {
        const val MAX_GAP_MS = 5_000L
    }
}

/**
 * Writes a run as a FIT activity file: `file_id`, timer `event`s, `record`s, `lap`s, one
 * `session` and one `activity`, using only standard messages and fields.
 *
 * The home server's importer is the reader this file is written for; a fixture produced here is
 * imported by a server test so the two cannot drift apart.
 */
object FitEncoder {
    /** Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z). */
    const val FIT_EPOCH_OFFSET_S = 631_065_600L
    private const val SEMICIRCLES_PER_DEGREE = 2_147_483_648.0 / 180.0

    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val SINT32 = 0x85
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    private class Field(val num: Int, val type: Int) {
        val size get() = when (type) { ENUM, UINT8 -> 1; UINT16 -> 2; else -> 4 }
    }

    private class Message(val local: Int, val global: Int, vararg val fields: Field)

    private val FILE_ID = Message(0, 0, Field(0, ENUM), Field(1, UINT16), Field(2, UINT16), Field(3, UINT32Z), Field(4, UINT32))
    private val EVENT = Message(1, 21, Field(253, UINT32), Field(0, ENUM), Field(1, ENUM), Field(4, UINT8))
    private val RECORD = Message(
        2, 20, Field(253, UINT32), Field(0, SINT32), Field(1, SINT32), Field(2, UINT16),
        Field(3, UINT8), Field(4, UINT8), Field(5, UINT32), Field(6, UINT16),
    )
    private val LAP = Message(
        3, 19, Field(254, UINT16), Field(253, UINT32), Field(0, ENUM), Field(1, ENUM), Field(2, UINT32),
        Field(7, UINT32), Field(8, UINT32), Field(9, UINT32), Field(15, UINT8), Field(24, ENUM),
    )
    private val SESSION = Message(
        4, 18, Field(254, UINT16), Field(253, UINT32), Field(0, ENUM), Field(1, ENUM), Field(2, UINT32),
        Field(5, ENUM), Field(6, ENUM), Field(7, UINT32), Field(8, UINT32), Field(9, UINT32),
        Field(16, UINT8), Field(25, UINT16), Field(26, UINT16),
    )
    private val ACTIVITY = Message(5, 34, Field(253, UINT32), Field(0, UINT32), Field(1, UINT16), Field(2, ENUM), Field(3, ENUM), Field(4, ENUM))

    private class Body {
        val out = ByteArrayOutputStream()
        private val defined = HashSet<Int>()

        fun write(m: Message, vararg values: Long?) {
            require(values.size == m.fields.size)
            if (defined.add(m.local)) {
                out.write(0x40 or m.local)
                out.write(0) // reserved
                out.write(0) // little endian
                le(m.global.toLong(), 2)
                out.write(m.fields.size)
                for (f in m.fields) { out.write(f.num); out.write(f.size); out.write(f.type) }
            }
            out.write(m.local)
            for ((f, v) in m.fields.zip(values)) le(v ?: invalid(f.type), f.size)
        }

        private fun invalid(type: Int): Long = when (type) {
            ENUM, UINT8 -> 0xFF
            UINT16 -> 0xFFFF
            SINT32 -> 0x7FFFFFFF
            UINT32Z -> 0
            else -> 0xFFFFFFFFL
        }

        private fun le(v: Long, size: Int) { for (i in 0 until size) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    }

    private fun fitTime(ms: Long): Long = Math.floorDiv(ms, 1000L) - FIT_EPOCH_OFFSET_S

    fun encode(run: RecordedRun, hr: List<HrSample> = emptyList()): ByteArray {
        val join = HrJoin(hr)
        val summary = RunSummary.of(run, hr)
        val body = Body()
        body.write(FILE_ID, 4 /* activity */, 255 /* development */, 1, 1, fitTime(run.startT))

        // One record per second: a later sample in the same second replaces the earlier one.
        val records = LinkedHashMap<Long, Sample>()
        for (s in run.samples) records[fitTime(s.t)] = s

        // Everything is written in time order; at the same instant a start comes first and a stop last.
        class Item(val t: Long, val rank: Int, val write: () -> Unit)
        val items = ArrayList<Item>()
        for (e in run.timer) items += Item(fitTime(e.t), if (e.start) 0 else 3) {
            body.write(EVENT, fitTime(e.t), 0 /* timer */, if (e.start) 0 else 4 /* stop_all */, 0)
        }
        for ((sec, s) in records) items += Item(sec, 1) {
            body.write(
                RECORD, sec,
                (s.lat * SEMICIRCLES_PER_DEGREE).roundToLong(),
                (s.lon * SEMICIRCLES_PER_DEGREE).roundToLong(),
                s.ele?.let { ((it + 500) * 5).roundToLong().coerceIn(0, 0xFFFE) },
                join.at(s.t)?.toLong(),
                // FIT stores running cadence per leg (strides per minute).
                s.cadence?.let { (it / 2.0).roundToLong().coerceIn(0, 254) },
                (s.dist * 100).roundToLong(),
                s.speed?.let { (it * 1000).roundToLong().coerceIn(0, 0xFFFE) },
            )
        }
        run.laps.forEachIndexed { i, lap ->
            items += Item(fitTime(lap.endT), 2) {
                body.write(
                    LAP, i.toLong(), fitTime(lap.endT), 9 /* lap */, 1 /* stop */, fitTime(lap.startT),
                    lap.endT - lap.startT, lap.movingMs, (lap.distance * 100).roundToLong(),
                    summary.lapHeartRates[i]?.toLong(), if (lap.manual) 0 else 7 /* session_end */,
                )
            }
        }
        items.sortWith(compareBy({ it.t }, { it.rank }))
        items.forEach { it.write() }

        val end = fitTime(run.endT)
        body.write(
            SESSION, 0, end, 8 /* session */, 1 /* stop */, fitTime(run.startT), 1 /* running */, 0 /* generic */,
            run.endT - run.startT, run.movingMs, (run.distance * 100).roundToLong(),
            summary.avgHeartRate?.toLong(), 0, run.laps.size.toLong(),
        )
        body.write(ACTIVITY, end, run.movingMs, 1, 0 /* manual */, 26 /* activity */, 1 /* stop */)

        val data = body.out.toByteArray()
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x20 // protocol 2.0
        header[2] = (2140 and 0xFF).toByte(); header[3] = (2140 shr 8).toByte() // profile 21.40
        for (i in 0 until 4) header[4 + i] = ((data.size shr (8 * i)) and 0xFF).toByte()
        ".FIT".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        val headerCrc = crc(header, 0, 12)
        header[12] = (headerCrc and 0xFF).toByte(); header[13] = (headerCrc shr 8).toByte()
        val fileCrc = crc(data, 0, data.size, crc(header, 0, 14))
        return header + data + byteArrayOf((fileCrc and 0xFF).toByte(), (fileCrc shr 8).toByte())
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** The FIT CRC-16 of [length] bytes of [bytes] from [offset], continuing from [seed]. */
    fun crc(bytes: ByteArray, offset: Int, length: Int, seed: Int = 0): Int {
        var crc = seed
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }
}
