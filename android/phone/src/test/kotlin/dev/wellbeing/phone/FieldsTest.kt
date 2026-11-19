package dev.wellbeing.phone

import androidx.test.core.app.ApplicationProvider
import dev.wellbeing.phone.data.Field
import dev.wellbeing.phone.ui.FieldTime
import dev.wellbeing.phone.ui.WorkoutText
import dev.wellbeing.running.Event
import dev.wellbeing.running.SessionEngine
import dev.wellbeing.running.StepEnd
import dev.wellbeing.running.StepKind
import dev.wellbeing.running.StepTarget
import dev.wellbeing.running.Units
import dev.wellbeing.running.Workout
import dev.wellbeing.running.WorkoutItem
import dev.wellbeing.running.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bottom field of the main screen and the session texts (spec `live-run-screens`, `workout-library`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FieldsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `the combined field is named for the choice and labelled Temps while running`() {
        assertEquals("Temps, distance et mini-carte", context.getString(R.string.f_combined))
        assertEquals("Temps", context.getString(FieldTime.label(Field.COMBINED)!!))
    }

    @Test
    fun `its time stops during a pause, unlike elapsed time`() {
        val e = SessionEngine()
        val t0 = 1_794_760_000_000L
        e.on(Event.Start(t0))
        e.on(Event.Pause(t0 + 600_000))
        val paused = e.snapshot(t0 + 600_000)
        val later = e.snapshot(t0 + 660_000)
        assertEquals("10:00", FieldTime.value(Field.COMBINED, paused))
        assertEquals("10:00", FieldTime.value(Field.COMBINED, later))
        assertEquals("11:00", FieldTime.value(Field.ELAPSED, later))
    }

    @Test
    fun `session totals are exact, a minimum, an estimate or unknown`() {
        fun w(vararg s: WorkoutStep) = Workout(1, "x", s.map { WorkoutItem.Single(it) })
        val timed = w(WorkoutStep(StepKind.WARMUP, StepEnd.Time(600.0)), WorkoutStep(StepKind.EFFORT, StepEnd.Time(1200.0)))
        assertEquals("30:00", WorkoutText.total(timed, Units.METRIC))
        val open = w(WorkoutStep(StepKind.EFFORT, StepEnd.Time(600.0)), WorkoutStep(StepKind.RECOVERY, StepEnd.Lap))
        assertEquals("au moins 10:00", WorkoutText.total(open, Units.METRIC))
        val paced = w(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(1000.0), StepTarget.Pace(300.0, 330.0)))
        assertEquals("≈ 5:15 · 1,00 km", WorkoutText.total(paced, Units.METRIC))
        assertEquals("durée inconnue", WorkoutText.total(w(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(1000.0)), WorkoutStep(StepKind.RECOVERY, StepEnd.Lap)), Units.METRIC))
        assertEquals("Zone 2 · 114–133 bpm", WorkoutText.target(StepTarget.HrZone(2), Units.METRIC, listOf(95, 114, 133, 152, 171, 190)))
        assertEquals("5:00–5:15 /km", WorkoutText.target(StepTarget.Pace(300.0, 315.0), Units.METRIC, emptyList()))
        assertEquals("0,25 mi", WorkoutText.end(StepEnd.Distance(402.336), Units.IMPERIAL))
    }
}
