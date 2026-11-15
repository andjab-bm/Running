package dev.wellbeing.running

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun durations() {
        assertEquals("0:00", Format.duration(0.0))
        assertEquals("51:03", Format.duration(3063.0))
        assertEquals("1:02:11", Format.duration(3731.2))
    }

    @Test
    fun `pace in both unit systems and when unavailable`() {
        assertEquals("6:00", Format.pace(360.0, Units.METRIC))
        assertEquals("9:39", Format.pace(360.0, Units.IMPERIAL))
        assertEquals(Format.PLACEHOLDER, Format.pace(null, Units.METRIC))
        assertEquals(Format.PLACEHOLDER, Format.pace(2400.0, Units.METRIC))
    }

    @Test
    fun `distance is truncated, not rounded up`() {
        assertEquals("4,25", Format.distance(4259.0, Units.METRIC))
        assertEquals("0,00", Format.distance(0.0, Units.METRIC))
        assertEquals("3,10", Format.distance(5000.0, Units.IMPERIAL))
        assertEquals("210", Format.elevation(64.0, Units.IMPERIAL))
    }
}
