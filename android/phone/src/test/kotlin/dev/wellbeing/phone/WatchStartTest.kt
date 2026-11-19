package dev.wellbeing.phone

import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.record.StartGrants
import dev.wellbeing.phone.record.refusalText
import dev.wellbeing.phone.record.startRefusal
import dev.wellbeing.running.Phase
import dev.wellbeing.running.WatchProtocol
import dev.wellbeing.running.WatchProtocol.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `run-recording` "Start on request from the watch": the phone's answer and its reason. */
class WatchStartTest {
    private val all = StartGrants(fineLocation = true, backgroundLocation = true, batteryExempt = true)

    @Test
    fun `with the option and its grants, a start with nothing prepared or a session ready is accepted`() {
        assertEquals(Refusal.NONE, startRefusal(true, all, locationOn = true, phase = null))
        assertEquals(Refusal.NONE, startRefusal(true, all, locationOn = true, phase = Phase.READY))
    }

    @Test
    fun `each missing condition gives its own reason`() {
        assertEquals(Refusal.OTHER, startRefusal(false, all, true, null))
        assertEquals(Refusal.PERMISSION, startRefusal(true, all.copy(backgroundLocation = false), true, null))
        assertEquals(Refusal.PERMISSION, startRefusal(true, all.copy(fineLocation = false), true, null))
        assertEquals(Refusal.BATTERY_OPTIMISATION, startRefusal(true, all.copy(batteryExempt = false), true, null))
        assertEquals(Refusal.LOCATION_OFF, startRefusal(true, all, false, null))
        for (p in listOf(Phase.RUNNING, Phase.PAUSED, Phase.AUTO_PAUSED, Phase.STOPPED)) assertEquals(Refusal.BUSY, startRefusal(true, all, true, p))
        for (r in Refusal.entries.filter { it != Refusal.NONE }) assertTrue(refusalText(r).isNotEmpty())
    }

    @Test
    fun `the option is off by default and the refusal reaches a version-2 watch`() {
        assertFalse(Settings().startFromWatch)
        val ack = WatchProtocol.Ack(4, false, Refusal.BATTERY_OPTIMISATION)
        assertEquals(ack, WatchProtocol.Ack.decode(ack.encode()))
    }
}
