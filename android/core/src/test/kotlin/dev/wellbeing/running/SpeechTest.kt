package dev.wellbeing.running

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechTest {
    private val t = 1_794_760_000_000L

    @Test
    fun `a kilometre mark and a step change together, the step goes first`() {
        val q = CueQueue()
        val effort = PlannedStep(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(400.0)), 3, 6)
        q.add(Cue.Split(t, 3_000.0, 1_060_000, 352.0))
        q.add(Cue.TargetOut(t, TargetState.TOO_FAST, StepTarget.Pace(300.0, 315.0)))
        q.add(Cue.StepStarted(t, effort, 5, 14))
        assertEquals(CueKind.STEP, q.next(t, paused = false)!!.kind)
        assertEquals(CueKind.TARGET, q.next(t + 3_000, paused = false)!!.kind)
        assertEquals(CueKind.SPLIT, q.next(t + 6_000, paused = false)!!.kind)
        assertNull(q.next(t + 9_000, paused = false))
    }

    @Test
    fun `a kilometre announcement that waited more than 20 seconds is dropped`() {
        val q = CueQueue()
        q.add(Cue.Split(t, 3_000.0, 1_060_000, 352.0))
        assertNull(q.next(t + 20_001, paused = false))
    }

    @Test
    fun `while paused, only the pause is signalled`() {
        val q = CueQueue()
        q.add(Cue.Split(t, 3_000.0, 1_060_000, 352.0))
        q.add(Cue.Pacer(t, -31.0))
        q.add(Cue.TargetOut(t, TargetState.TOO_SLOW, StepTarget.Pace(300.0, 315.0)))
        q.add(Cue.Paused(t, auto = true))
        assertEquals(Cue.Paused(t, auto = true), q.next(t, paused = true))
        assertNull(q.next(t + 1_000, paused = true))
    }

    @Test
    fun `a split is said as a runner says it, in kilometres or miles`() {
        val km = Speech.say(Cue.Split(t, 3_000.0, (17 * 60 + 40) * 1000L, 352.0), Units.METRIC)
        assertEquals("3 kilomètres, en 17 minutes 40. Dernier kilomètre en 5 minutes 52 au kilomètre.", km)
        val mi = Speech.say(Cue.Split(t, 2 * Format.METERS_PER_MILE, 18 * 60 * 1000L, 335.6, avgPace = 335.6, heartRate = 151), Units.IMPERIAL)
        assertEquals("2 miles, en 18 minutes. Dernier mile en 9 minutes au mile. Allure moyenne 9 minutes au mile. Fréquence cardiaque 151.", mi)
    }

    @Test
    fun `steps are announced with their length, target and repeat`() {
        val effort = PlannedStep(WorkoutStep(StepKind.EFFORT, StepEnd.Distance(400.0), StepTarget.Pace(300.0, 315.0)), 3, 6)
        assertEquals(
            "Effort, 400 mètres, entre 5 minutes et 5 minutes 15 au kilomètre. Répétition 3 sur 6.",
            Speech.say(Cue.StepStarted(t, effort, 5, 14), Units.METRIC),
        )
        val warmup = PlannedStep(WorkoutStep(StepKind.WARMUP, StepEnd.Time(600.0), StepTarget.HrZone(2)))
        assertEquals("Échauffement, 10 minutes, zone 2, de 114 à 133 battements.", Speech.say(Cue.StepStarted(t, warmup, 0, 14), Units.METRIC, listOf(95, 114, 133, 152, 171, 190)))
        val open = PlannedStep(WorkoutStep(StepKind.RECOVERY, StepEnd.Lap))
        assertEquals("Récupération, jusqu'à l'appui sur Tour.", Speech.say(Cue.StepStarted(t, open, 1, 3), Units.METRIC))
        val recovery = PlannedStep(WorkoutStep(StepKind.RECOVERY, StepEnd.Time(90.0)), 2, 6)
        assertEquals("Récupération, 1 minute 30. Répétition 2 sur 6.", Speech.say(Cue.StepStarted(t, recovery, 4, 14), Units.METRIC))
    }

    @Test
    fun `alerts say what is wrong and what to aim for`() {
        assertEquals(
            "Trop rapide. Visez entre 8 minutes 3 et 8 minutes 27 au mile.",
            Speech.say(Cue.TargetOut(t, TargetState.TOO_FAST, StepTarget.Pace(300.0, 315.0)), Units.IMPERIAL),
        )
        assertEquals("30 secondes de retard.", Speech.say(Cue.Pacer(t, -30.0), Units.METRIC))
        assertEquals("1 minute 10 d'avance.", Speech.say(Cue.Pacer(t, 70.4), Units.METRIC))
        assertEquals("Hors du parcours, à 60 mètres.", Speech.say(Cue.OffRoute(t, 58.0), Units.METRIC))
    }

    @Test
    fun `times, paces and distances in words`() {
        assertEquals("45 secondes", Speech.duration(45.0))
        assertEquals("1 minute 30", Speech.duration(90.0))
        assertEquals("25 minutes", Speech.duration(1500.0))
        assertEquals("1 heure 2 minutes", Speech.duration(3725.0))
        assertEquals("6 minutes au kilomètre", Speech.pace(360.0, Units.METRIC))
        assertEquals("2,5 kilomètres", Speech.distance(2_500.0, Units.METRIC))
        assertEquals("1 kilomètre", Speech.distance(1_000.0, Units.METRIC))
        assertEquals("0,5 mile", Speech.distance(804.672, Units.IMPERIAL))
    }

    @Test
    fun `each kind of cue vibrates its own way`() {
        val patterns = CueKind.entries.map { VibrationPatterns.of(it).toList() }
        assertEquals(patterns.size, patterns.toSet().size)
    }
}
