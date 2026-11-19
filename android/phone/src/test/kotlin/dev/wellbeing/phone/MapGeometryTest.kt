package dev.wellbeing.phone

import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.ui.CameraFollow
import dev.wellbeing.phone.ui.GeoBounds
import dev.wellbeing.phone.ui.LatLon
import dev.wellbeing.phone.ui.MercatorFrame
import dev.wellbeing.phone.data.DEFAULT_MAP_STYLE
import dev.wellbeing.phone.data.DEFAULT_MAP_STYLE_DARK
import dev.wellbeing.phone.ui.basemapStyle
import dev.wellbeing.phone.ui.heading
import dev.wellbeing.phone.ui.nextFrame
import dev.wellbeing.phone.ui.splitRoute
import dev.wellbeing.running.RoutePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `route-navigation` and the mini-map of `live-run-screens`, without MapLibre. */
class MapGeometryTest {
    private val mPerDeg = 111_194.93

    /** A straight route north from Paris, [km] long, a point every 100 m. */
    private fun north(km: Double): RoutePath {
        val n = (km * 10).toInt() + 1
        return RoutePath(1, 1, "Nord", DoubleArray(n) { 48.85 + it * 100 / mPerDeg }, DoubleArray(n) { 2.35 }, DoubleArray(n) { it * 100.0 }, null)
    }

    private fun length(points: List<LatLon>) = points.zipWithNext { a, b -> (b.lat - a.lat) * mPerDeg }.sum()

    @Test
    fun `the basemap comes only from the server's style, and only with the setting on`() {
        val s = Settings(mapStyleUrl = "https://tiles.example/light.json", mapStyleUrlDark = "https://tiles.example/dark.json")
        assertEquals("https://tiles.example/light.json", basemapStyle(s, dark = false))
        assertEquals("https://tiles.example/dark.json", basemapStyle(s, dark = true))
        assertEquals("https://tiles.example/light.json", basemapStyle(s.copy(mapStyleUrlDark = ""), dark = true))
        assertNull(basemapStyle(s.copy(basemap = false), dark = false))
        assertEquals(DEFAULT_MAP_STYLE, basemapStyle(Settings(), dark = false))
        assertEquals(DEFAULT_MAP_STYLE_DARK, basemapStyle(Settings(), dark = true))
        assertNull(basemapStyle(Settings(mapStyleUrl = "", mapStyleUrlDark = ""), dark = false))
        assertTrue(Settings().basemap)
    }

    @Test
    fun `a frame shows its bounds whole and centred`() {
        val b = GeoBounds(48.85, 2.33, 48.87, 2.37)
        val f = MercatorFrame.fit(b, 160.0, 120.0)
        val (cx, cy) = f.project((b.south + b.north) / 2, (b.west + b.east) / 2)
        assertEquals(80.0, cx, 1.0)
        assertEquals(60.0, cy, 1.0)
        for ((lat, lon) in listOf(b.south to b.west, b.north to b.east)) {
            val (x, y) = f.project(lat, lon)
            assertTrue("$x", x in -0.5..160.5)
            assertTrue("$y", y in -0.5..120.5)
        }
        // Edge to edge on one axis, to rounding.
        assertTrue(f.bounds.contains(GeoBounds(b.south + 1e-9, b.west + 1e-9, b.north - 1e-9, b.east - 1e-9)))
        // North is up.
        assertTrue(f.project(b.north, 2.35).second < f.project(b.south, 2.35).second)
    }

    @Test
    fun `the mini-map keeps its image while the trace stays inside, and takes a wider one when it grows`() {
        val start = GeoBounds(48.85, 2.35, 48.851, 2.351)
        val first = nextFrame(null, start, 160.0, 120.0)!!
        assertSame(first, nextFrame(first, GeoBounds(48.85, 2.35, 48.8512, 2.3511), 160.0, 120.0))
        val grown = GeoBounds(48.85, 2.35, 48.87, 2.351)
        val second = nextFrame(first, grown, 160.0, 120.0)!!
        assertNotSame(first, second)
        assertTrue(second.bounds.contains(grown))
        // A few metres more do not need a new image.
        assertSame(second, nextFrame(second, GeoBounds(48.85, 2.35, 48.8705, 2.351), 160.0, 120.0))
        assertNull(nextFrame(null, null, 160.0, 120.0))
    }

    @Test
    fun `halfway along a 10 km route the part done and the part ahead are 5 km each`() {
        val r = north(10.0)
        val split = splitRoute(r, joinedAt = 0.0, along = 5_000.0)
        assertEquals(5_000.0, length(split.done), 1.0)
        assertEquals(5_000.0, split.ahead.sumOf(::length), 1.0)
    }

    @Test
    fun `joined 1 km after the start, done counts from there`() {
        val r = north(10.0)
        val split = splitRoute(r, joinedAt = 1_000.0, along = 1_450.0)
        assertEquals(450.0, length(split.done), 1.0)
        assertEquals(2, split.ahead.size)
        assertEquals(1_000.0 + 8_550.0, split.ahead.sumOf(::length), 1.0)
        // Not joined yet: the whole route is ahead.
        assertEquals(10_000.0, splitRoute(r, null, null).ahead.sumOf(::length), 1.0)
    }

    @Test
    fun `the heading is the direction of the last 15 metres`() {
        val k = Math.cos(Math.toRadians(48.85))
        val northward = (0..5).map { LatLon(48.85 + it * 5 / mPerDeg, 2.35) }
        assertEquals(0.0, heading(northward)!!, 1.0)
        val eastward = (0..5).map { LatLon(48.85, 2.35 + it * 5 / (mPerDeg * k)) }
        assertEquals(90.0, heading(eastward)!!, 1.0)
        assertNull(heading(listOf(LatLon(48.85, 2.35), LatLon(48.85 + 3 / mPerDeg, 2.35))))
    }

    @Test
    fun `after a drag the map returns to the runner within 10 seconds`() {
        val f = CameraFollow()
        assertTrue(f.following)
        f.moved(1_000)
        assertFalse(f.following)
        assertFalse(f.tick(10_999))
        assertTrue(f.tick(11_000))
        assertTrue(f.following)
        f.moved(20_000)
        f.recentre()
        assertTrue(f.following)
    }
}
