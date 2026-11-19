package dev.wellbeing.running

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutTest {
    private val fixtures = File(System.getProperty("repo.root"), "server/tests/fixtures/workouts")

    private fun read(sub: String) = File(fixtures, sub).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test
    fun `every valid fixture is accepted with the server's step count`() {
        val files = read("valid")
        assertTrue(files.size >= 3)
        for (f in files) {
            val text = f.readText()
            val w = Workout.parse(text).getOrElse { throw AssertionError("${f.name}: ${it.message}") }
            val expected = Regex("\"expect_steps\"\\s*:\\s*(\\d+)").find(text)!!.groupValues[1].toInt()
            assertEquals(expected, w.unfold().size, f.name)
        }
    }

    @Test
    fun `every invalid fixture is refused, as by the server`() {
        val files = read("invalid")
        assertTrue(files.size >= 6)
        for (f in files) assertTrue(Workout.parse(f.readText()).isFailure, "${f.name} should be refused")
    }

    @Test
    fun `six repeats unfold into twelve steps, or eleven without the last recovery`() {
        fun block(skip: Boolean) = Workout(
            null, "6 × 400 m",
            listOf(
                WorkoutItem.Repeat(
                    6, skip,
                    listOf(
                        WorkoutStep(StepKind.EFFORT, StepEnd.Distance(400.0), StepTarget.Pace(300.0, 315.0)),
                        WorkoutStep(StepKind.RECOVERY, StepEnd.Time(90.0)),
                    ),
                ),
            ),
        )
        assertEquals(12, block(false).unfold().size)
        val eleven = block(true).unfold()
        assertEquals(11, eleven.size)
        assertEquals(StepKind.EFFORT, eleven.last().step.kind)
        assertEquals(3 to 6, eleven[4].round to eleven[4].rounds)
    }

    @Test
    fun `the steps written back read the same`() {
        val w = Workout.parse(File(fixtures, "valid/intervals.json").readText()).getOrThrow()
        val again = Workout.parse(w.stepsJson().toString()).getOrThrow()
        assertEquals(w.items, again.items)
    }
}
