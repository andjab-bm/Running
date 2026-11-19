package dev.wellbeing.phone.ui

import dev.wellbeing.phone.data.Settings
import dev.wellbeing.running.RoutePath
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The map's arithmetic, kept apart from MapLibre so it is unit-tested: which style to load, the
 * extent and projection of the mini-map, the route split into done and ahead, the heading, and
 * when the map goes back to following the runner.
 */

/** The basemap to load, or null for the plain background: the setting on and a style on the server. */
fun basemapStyle(settings: Settings, dark: Boolean): String? {
    if (!settings.basemap) return null
    val url = if (dark) settings.mapStyleUrlDark.ifEmpty { settings.mapStyleUrl } else settings.mapStyleUrl
    return url.ifEmpty { null }
}

data class LatLon(val lat: Double, val lon: Double)

data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(o: GeoBounds) = o.south >= south && o.north <= north && o.west >= west && o.east <= east

    /** Grown by [fraction] of its size on every side, and at least [minMeters] across. */
    fun padded(fraction: Double, minMeters: Double = 300.0): GeoBounds {
        val minLat = minMeters / M_PER_DEG
        val minLon = minMeters / (M_PER_DEG * cos(Math.toRadians((south + north) / 2)))
        val dLat = max((north - south) * fraction, (minLat - (north - south)).coerceAtLeast(0.0) / 2)
        val dLon = max((east - west) * fraction, (minLon - (east - west)).coerceAtLeast(0.0) / 2)
        return GeoBounds(south - dLat, west - dLon, north + dLat, east + dLon)
    }

    companion object {
        private const val M_PER_DEG = 111_194.93

        fun of(points: List<LatLon>): GeoBounds? = if (points.isEmpty()) null else GeoBounds(
            points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon },
        )
    }
}

/**
 * A view of the Web Mercator world as MapLibre draws it: 512 logical pixels across at zoom 0. The
 * mini-map's snapshot is taken with this centre and zoom, so the canvas drawn over it uses the same
 * projection and the trace lands on its streets.
 */
data class MercatorFrame(val cx: Double, val cy: Double, val zoom: Double, val width: Double, val height: Double) {
    private val scale get() = WORLD * Math.pow(2.0, zoom)

    /** Logical pixels from the top left of the frame. */
    fun project(lat: Double, lon: Double): Pair<Double, Double> =
        ((x(lon) - cx) * scale + width / 2) to ((y(lat) - cy) * scale + height / 2)

    val center get() = LatLon(lat(cy), lon(cx))

    /** What the frame shows. */
    val bounds: GeoBounds
        get() {
            val hw = width / 2 / scale
            val hh = height / 2 / scale
            return GeoBounds(lat(cy + hh), lon(cx - hw), lat(cy - hh), lon(cx + hw))
        }

    companion object {
        const val WORLD = 512.0
        /** Never closer than this, so a run of a few metres is not shown at house scale. */
        const val MAX_ZOOM = 17.0

        fun x(lon: Double) = (lon + 180) / 360
        fun y(lat: Double): Double {
            val s = sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
            return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        }
        fun lon(x: Double) = x * 360 - 180
        fun lat(y: Double) = Math.toDegrees(2 * kotlin.math.atan(exp((0.5 - y) * 2 * PI)) - PI / 2)

        /** The frame of [width] × [height] logical pixels that shows [b] whole, centred. */
        fun fit(b: GeoBounds, width: Double, height: Double): MercatorFrame {
            val x0 = x(b.west); val x1 = x(b.east)
            val y0 = y(b.north); val y1 = y(b.south)
            val dx = max(x1 - x0, 1e-9); val dy = max(y1 - y0, 1e-9)
            val zoom = min(log2(min(width / (dx * WORLD), height / (dy * WORLD))), MAX_ZOOM)
            return MercatorFrame((x0 + x1) / 2, (y0 + y1) / 2, zoom, width, height)
        }
    }
}

/**
 * The mini-map's frame: kept while what it must show (the trace, and the route when one is
 * followed) stays inside, replaced by a wider one when it grows out of it. Each new frame is a new
 * basemap snapshot, so it is taken with room to spare.
 */
fun nextFrame(current: MercatorFrame?, wanted: GeoBounds?, width: Double, height: Double): MercatorFrame? {
    if (wanted == null || width <= 0 || height <= 0) return current
    if (current != null && current.width == width && current.height == height && current.bounds.contains(wanted)) return current
    return MercatorFrame.fit(wanted.padded(0.25), width, height)
}

/** A route cut at two distances along it: the part done between them, and the rest ahead. */
data class RouteSplit(val done: List<LatLon>, val ahead: List<List<LatLon>>)

fun pointAt(path: RoutePath, d: Double): LatLon {
    val dist = path.dist
    if (d <= 0) return LatLon(path.lat[0], path.lon[0])
    if (d >= dist.last()) return LatLon(path.lat.last(), path.lon.last())
    var i = dist.binarySearch(d)
    if (i >= 0) return LatLon(path.lat[i], path.lon[i])
    i = -i - 1
    val f = (d - dist[i - 1]) / (dist[i] - dist[i - 1])
    return LatLon(path.lat[i - 1] + f * (path.lat[i] - path.lat[i - 1]), path.lon[i - 1] + f * (path.lon[i] - path.lon[i - 1]))
}

/** The route's points between [from] and [to] metres along it, ends interpolated. */
fun stretch(path: RoutePath, from: Double, to: Double): List<LatLon> {
    if (to <= from) return emptyList()
    val out = mutableListOf(pointAt(path, from))
    for (i in path.dist.indices) if (path.dist[i] > from && path.dist[i] < to) out += LatLon(path.lat[i], path.lon[i])
    out += pointAt(path, to)
    return out
}

/** Done is from where the runner joined to where they are; before and after is ahead. */
fun splitRoute(path: RoutePath, joinedAt: Double?, along: Double?): RouteSplit {
    val end = path.length
    if (joinedAt == null || along == null) return RouteSplit(emptyList(), listOf(stretch(path, 0.0, end)))
    val a = along.coerceIn(joinedAt, end)
    return RouteSplit(stretch(path, joinedAt, a), listOf(stretch(path, 0.0, joinedAt), stretch(path, a, end)).filter { it.size >= 2 })
}

/**
 * The direction of travel in degrees from north, from the last positions at least [minMeters]
 * apart; null when the runner has not moved enough to tell.
 */
fun heading(track: List<LatLon>, minMeters: Double = 15.0): Double? {
    if (track.size < 2) return null
    val last = track.last()
    val k = cos(Math.toRadians(last.lat))
    for (i in track.indices.reversed()) {
        val p = track[i]
        val dy = (last.lat - p.lat) * 111_194.93
        val dx = (last.lon - p.lon) * 111_194.93 * k
        if (dx * dx + dy * dy >= minMeters * minMeters) return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }
    return null
}

/**
 * Whether the map follows the runner. Moving the map by hand frees it; it follows again on request,
 * or by itself [RETURN_MS] after the last touch.
 */
class CameraFollow {
    companion object {
        const val RETURN_MS = 10_000L
    }

    var following = true
        private set
    private var touchedAt = 0L

    fun moved(now: Long) {
        following = false
        touchedAt = now
    }

    fun recentre() {
        following = true
    }

    /** True when the map should go back to the runner now. */
    fun tick(now: Long): Boolean {
        if (!following && now - touchedAt >= RETURN_MS) {
            following = true
            return true
        }
        return false
    }
}
