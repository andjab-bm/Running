package dev.wellbeing.running

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live-screen figures must reach a contrast ratio of 7:1 on their field (spec `mobile-theming`,
 * "Readable while running"). The pairs below are the ones `LiveScreen` draws; the measured
 * ratios are recorded in `docs/design-direction.md`.
 */
class ContrastTest {
    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((rgb shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG 2 contrast ratio between two opaque colours. */
    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Fields a figure can sit on: the five pastel fills and the plain surface of the lap list. */
    private val fields = listOf("p-lav", "p-mint", "p-sky", "p-butter", "p-rose", "surface")

    @Test
    fun `figures reach 7 to 1 on every field in both themes`() {
        for (dark in listOf(false, true)) {
            val ink = Tokens.color("ink", dark)
            for (field in fields) {
                val r = ratio(ink, Tokens.color(field, dark))
                assertTrue(r >= 7.0, "ink on $field (${if (dark) "dark" else "light"}) is only ${"%.1f".format(r)}:1")
            }
        }
    }

    @Test
    fun `the heart-rate figure reaches 7 to 1 on every zone colour`() {
        for (zone in 1..5) {
            val r = ratio(Tokens.ZONE_INK, Tokens.zone(zone))
            assertTrue(r >= 7.0, "zone ink on z$zone is only ${"%.1f".format(r)}:1")
        }
    }

    @Test
    fun `watch figures reach 7 to 1 on black`() {
        for (c in listOf(Tokens.Watch.TIME, Tokens.Watch.PACE, Tokens.Watch.DISTANCE) + (1..5).map { Tokens.zone(it) }) {
            val r = ratio(c, Tokens.Watch.GROUND)
            assertTrue(r >= 7.0, "#${"%06X".format(c)} on black is only ${"%.1f".format(r)}:1")
        }
    }
}
