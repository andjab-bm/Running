package dev.wellbeing.running

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TokensTest {
    private val css = File(System.getProperty("repo.root"), "web/src/lib/tokens.css").readText()

    /** Hex colours declared in the block that starts at [selector]. */
    private fun block(selector: String): Map<String, Int> {
        val start = css.indexOf(selector)
        check(start >= 0) { "$selector not found in tokens.css" }
        val body = css.substring(css.indexOf('{', start) + 1, css.indexOf('}', start))
        return Regex("""--([a-z0-9-]+):\s*#([0-9a-fA-F]{6})\s*;""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }
    }

    @Test
    fun `light tokens are those of the web app`() {
        assertEquals(block(":root {").toSortedMap(), Tokens.light.toSortedMap())
    }

    @Test
    fun `dark tokens are those of the web app`() {
        assertEquals(block(":root[data-theme='dark']").toSortedMap(), Tokens.dark.toSortedMap())
    }

    @Test
    fun `zone colours are shared by both themes`() {
        for (z in 1..5) assertEquals(Tokens.color("z$z", isDark = false), Tokens.color("z$z", isDark = true))
    }

    @Test
    fun `default zones follow the server's rule`() {
        // Maximum 190 bpm: zone 2 spans 114–133 and zone 5 starts at 171 (athlete-profile spec).
        val z = Zones.default
        assertEquals(listOf(95.0, 114.0, 133.0, 152.0, 171.0, 190.0), z.bounds)
        assertEquals(1, z.of(80))
        assertEquals(2, z.of(114))
        assertEquals(2, z.of(132))
        assertEquals(4, z.of(160))
        assertEquals(5, z.of(200))
    }

    @Test
    fun `logged events survive a round trip`() {
        val events = Script.fixture(withHr = false).events
        val back = events.mapNotNull(EventCodec::encode).mapNotNull(EventCodec::decode)
        assertEquals(events, back)
        assertEquals(null, EventCodec.encode(Event.HeartRate(1, 150)))
    }
}
