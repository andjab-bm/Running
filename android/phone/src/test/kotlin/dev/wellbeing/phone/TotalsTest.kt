package dev.wellbeing.phone

import dev.wellbeing.phone.data.RunEntity
import dev.wellbeing.phone.data.RunState
import dev.wellbeing.phone.ui.Totals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TotalsTest {
    private val zone = ZoneId.of("Europe/Paris")

    private fun run(date: String, km: Double) = RunEntity(
        state = RunState.SAVED,
        startT = LocalDate.parse(date).atTime(18, 30).atZone(zone).toInstant().toEpochMilli(),
        distance = km * 1000, movingMs = (km * 360_000).toLong(),
    )

    @Test
    fun `three runs since Monday`() {
        // Thursday 12 November 2026; the week started on Monday 9.
        val runs = listOf(run("2026-11-09", 5.0), run("2026-11-10", 8.0), run("2026-11-12", 10.0), run("2026-11-08", 14.0))
        val week = Totals.of(runs, month = false, today = LocalDate.parse("2026-11-12"), zone = zone)
        assertEquals(3, week.runs)
        assertEquals(23_000.0, week.distance, 0.001)
        assertEquals(23 * 360_000L, week.movingMs)
        assertEquals(4, Totals.of(runs, month = true, today = LocalDate.parse("2026-11-12"), zone = zone).runs)
    }

    @Test
    fun `a week that crosses a month boundary`() {
        // Wednesday 2 December 2026; the week started on Monday 30 November.
        val runs = listOf(run("2026-11-29", 6.0), run("2026-11-30", 5.0), run("2026-12-01", 7.0))
        val today = LocalDate.parse("2026-12-02")
        val week = Totals.of(runs, month = false, today = today, zone = zone)
        assertEquals(2, week.runs)
        assertEquals(12_000.0, week.distance, 0.001)
        val month = Totals.of(runs, month = true, today = today, zone = zone)
        assertEquals(1, month.runs)
        assertEquals(7_000.0, month.distance, 0.001)
    }

    @Test
    fun `a run on Monday at midnight belongs to the new week`() {
        val monday = LocalDate.parse("2026-11-09").atStartOfDay(zone).toInstant().toEpochMilli()
        val runs = listOf(RunEntity(state = RunState.SAVED, startT = monday, distance = 1000.0), RunEntity(state = RunState.SAVED, startT = monday - 1, distance = 1000.0))
        assertEquals(1, Totals.of(runs, month = false, today = LocalDate.parse("2026-11-12"), zone = zone).runs)
    }
}
