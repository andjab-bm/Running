package dev.wellbeing.running

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitEncoderTest {
    /** Counts data messages per global message number, walking the file with its own definitions. */
    private fun messageCounts(fit: ByteArray): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        val sizes = HashMap<Int, Pair<Int, Int>>() // local type -> (global number, data size)
        var i = 14
        val end = fit.size - 2
        while (i < end) {
            val header = fit[i].toInt() and 0xFF
            val local = header and 0x0F
            if (header and 0x40 != 0) {
                val global = (fit[i + 3].toInt() and 0xFF) or ((fit[i + 4].toInt() and 0xFF) shl 8)
                val n = fit[i + 5].toInt() and 0xFF
                val size = (0 until n).sumOf { fit[i + 6 + it * 3 + 1].toInt() and 0xFF }
                sizes[local] = global to size
                i += 6 + n * 3
            } else {
                val (global, size) = sizes.getValue(local)
                counts.merge(global, 1, Int::plus)
                i += 1 + size
            }
        }
        assertEquals(end, i, "messages must end exactly at the file CRC")
        return counts
    }

    /**
     * "6 x 400 m" (`server/tests/fixtures/workouts/valid/intervals.json`) run with the coach: every
     * step is a lap, and the stop closes the cool-down, so the file has fourteen laps.
     */
    private fun guided(): Triple<ByteArray, RecordedRun, RunSummary> {
        val doc = File(System.getProperty("repo.root"), "server/tests/fixtures/workouts/valid/intervals.json").readText()
        val coach = Coach(workout = Workout.parse(doc).getOrThrow(), zoneBounds = listOf(95, 114, 133, 152, 171, 190))
        val s = Script().start().move(600 + 6 * (143 + 90) + 590).stop()
        s.events.forEach { coach.on(it) }
        val run = coach.engine.recorded()
        return Triple(FitEncoder.encode(run, s.hr), run, RunSummary.of(run, s.hr))
    }

    @Test
    fun `a guided run has one lap per step`() {
        val (_, run, _) = guided()
        assertEquals(14, run.laps.size)
        assertTrue(run.laps.subList(1, 13).filterIndexed { i, _ -> i % 2 == 0 }.all { kotlin.math.abs(it.distance - 400) < 3 })
    }

    private fun build(withHr: Boolean, script: Script = Script.fixture(withHr)): Triple<ByteArray, RecordedRun, RunSummary> {
        val s = script
        val run = s.engine().recorded()
        return Triple(FitEncoder.encode(run, s.hr), run, RunSummary.of(run, s.hr))
    }

    @Test
    fun `header and checksums are valid`() {
        val (fit) = build(withHr = true)
        assertEquals(14, fit[0].toInt())
        assertEquals(".FIT", String(fit, 8, 4, Charsets.US_ASCII))
        val dataSize = (0 until 4).sumOf { (fit[4 + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(fit.size - 14 - 2, dataSize)
        // A CRC computed over data followed by its own CRC is zero.
        assertEquals(0, FitEncoder.crc(fit, 0, 14), "header CRC")
        assertEquals(0, FitEncoder.crc(fit, 0, fit.size), "file CRC")
    }

    @Test
    fun `the scripted run has the expected messages`() {
        val (fit, run) = build(withHr = true)
        val counts = messageCounts(fit)
        assertEquals(1, counts[0], "file_id")
        assertEquals(3, counts[19], "laps")
        assertEquals(1, counts[18], "session")
        assertEquals(1, counts[34], "activity")
        assertEquals(run.timer.size, counts[21], "timer events")
        // start, manual pause, resume, auto-pause, auto-resume, stop
        assertEquals(listOf(true, false, true, false, true, false), run.timer.map { it.start })
        assertEquals(run.samples.size, counts[20], "records")
        assertTrue(run.samples.size in 1790..1810, "one record per second while running: ${run.samples.size}")
    }

    @Test
    fun `summary figures match the script`() {
        val (_, run, summary) = build(withHr = true)
        assertTrue(kotlin.math.abs(summary.distance - 1800 * 2.8) < 30, "distance ${summary.distance}")
        // 1 800 s of running, minus the few seconds it takes to notice the runner is moving again.
        assertTrue(summary.movingMs in 1_794_000..1_803_000, "moving ${summary.movingMs}")
        assertEquals(1_900_000, summary.elapsedMs)
        assertEquals(3, run.laps.size)
        assertEquals(listOf(true, true, false), run.laps.map { it.manual })
        assertTrue(summary.avgHeartRate in 135..145, "heart rate ${summary.avgHeartRate}")
        assertTrue(summary.avgCadence in 165..175, "cadence ${summary.avgCadence}")
        assertEquals(null, build(withHr = false).third.avgHeartRate)
    }

    /**
     * The files imported by the server's contract test. Run with `-PupdateFixtures=true` to
     * rewrite them after an intentional change to the encoder or the engine.
     */
    @Test
    fun `committed fixtures are what the encoder produces`() {
        val dir = File(System.getProperty("repo.root"), "server/tests/fixtures")
        val update = System.getProperty("update.fixtures") == "true"
        val runs = listOf(
            "android-recorder" to build(withHr = true),
            "android-recorder-nohr" to build(withHr = false),
            // Stopped, resumed and stopped again: one run whose stop reads as a pause.
            "android-recorder-resumed" to build(withHr = true, Script.resumed()),
            // A guided "6 x 400 m": fourteen laps, one per step.
            "android-recorder-guided" to guided(),
        )
        for ((name, built) in runs) {
            val (fit, run, summary) = built
            val expected = """
                {
                  "distance_m": ${"%.2f".format(java.util.Locale.ROOT, summary.distance)},
                  "moving_s": ${"%.3f".format(java.util.Locale.ROOT, summary.movingMs / 1000.0)},
                  "elapsed_s": ${"%.3f".format(java.util.Locale.ROOT, summary.elapsedMs / 1000.0)},
                  "laps": ${run.laps.size},
                  "avg_hr": ${summary.avgHeartRate},
                  "avg_cadence": ${summary.avgCadence}
                }
            """.trimIndent() + "\n"
            val fitFile = File(dir, "$name.fit")
            val jsonFile = File(dir, "$name.json")
            if (update) {
                dir.mkdirs()
                fitFile.writeBytes(fit)
                jsonFile.writeText(expected)
            }
            assertTrue(fitFile.exists(), "$fitFile is missing: run :core:test -PupdateFixtures=true")
            assertContentEquals(fitFile.readBytes(), fit, "$name.fit is out of date: run :core:test -PupdateFixtures=true")
            assertEquals(jsonFile.readText().replace("\r\n", "\n"), expected, "$name.json is out of date")
        }
    }
}
