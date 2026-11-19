package dev.wellbeing.running

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `run-announcements` scenarios of the per-kilometre announcement and its interval. */
class AnnouncerTest {
    private fun splits(config: Announcer.Config, script: Script): List<Cue.Split> {
        val coach = Coach(announcerConfig = config)
        return script.events.flatMap { coach.on(it).cues }.filterIsInstance<Cue.Split>()
    }

    @Test
    fun `the third kilometre is said with its distance, the total time and its pace`() {
        // Two kilometres at 6:00, then one at 5:52.
        val script = Script().start().move(720, speed = 1000 / 360.0).move(352, speed = 1000 / 352.0).move(20)
        val third = splits(Announcer.Config(enabled = true), script)[2]
        assertEquals(3_000.0, third.distance)
        assertEquals(352.0, third.splitPace!!, 2.0)
        assertEquals(1_072.0, third.movingMs / 1000.0, 2.0)
        val said = Speech.say(third.copy(movingMs = 1_072_000, splitPace = 352.0), Units.METRIC)
        assertEquals("3 kilomètres, en 17 minutes 52. Dernier kilomètre en 5 minutes 52 au kilomètre.", said)
    }

    @Test
    fun `nothing is said when the announcement is off, as it is by default`() {
        val script = Script().start().move(1_200)
        assertTrue(splits(Announcer.Config(), script).isEmpty())
        assertTrue(splits(Announcer.Config(enabled = false, every = 0.5), script).isEmpty())
    }

    @Test
    fun `an imperial profile hears miles and minutes per mile`() {
        val script = Script().start().move(1_300, speed = 2.8)
        val all = splits(Announcer.Config(enabled = true, units = Units.IMPERIAL), script)
        assertEquals(listOf(1.0, 2.0), all.map { Math.round(it.distance / Format.METERS_PER_MILE).toDouble() })
        val said = Speech.say(all[1], Units.IMPERIAL)
        assertTrue(said.startsWith("2 miles, en "), said)
        assertTrue("Dernier mile en 9 minutes 35 au mile." in said, said)
    }

    @Test
    fun `every two kilometres gives 2, 4 and 6 and not the odd ones`() {
        val script = Script().start().move(2_250, speed = 2.8)
        val all = splits(Announcer.Config(enabled = true, every = 2.0), script)
        assertEquals(listOf(2_000.0, 4_000.0, 6_000.0), all.map { it.distance })
        val said = Speech.say(all[0], Units.METRIC)
        assertTrue(Regex("""Derniers 2 kilomètres en 5 minutes 5\d au kilomètre\.""") in said, said)
    }

    @Test
    fun `half a kilometre is announced as a stretch, with the options asked for`() {
        val script = Script().start().move(200, speed = 2.8)
        val s = splits(Announcer.Config(enabled = true, every = 0.5, avgPace = true, heartRate = true), script).first()
        assertEquals(500.0, s.distance)
        val said = Speech.say(s, Units.METRIC)
        assertTrue(said.startsWith("500 mètres, en "), said)
        assertTrue("Dernière portion de 500 mètres en " in said, said)
        assertTrue("Allure moyenne " in said, said)
        // No heart rate source in this run: the option adds nothing rather than a wrong figure.
        assertEquals(null, s.heartRate)
    }

    @Test
    fun `a run rebuilt after a kill does not announce the marks already passed`() {
        val settings = Coach.Settings(announcer = Announcer.Config(enabled = true))
        val script = Script().start().move(500, speed = 2.8)
        val killedAt = script.events.size
        script.move(300, speed = 2.8)
        val before = listOf(Event.Plan(script.t0, null, null, null)) + script.events.take(killedAt)
        val rebuilt = Coach.fromLog(before, settings) { null }
        val after = script.events.drop(killedAt).flatMap { rebuilt.on(it).cues }.filterIsInstance<Cue.Split>()
        assertEquals(listOf(2_000.0), after.map { it.distance })
    }
}
