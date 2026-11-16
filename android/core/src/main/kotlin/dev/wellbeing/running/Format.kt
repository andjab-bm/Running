package dev.wellbeing.running

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Display helpers shared by the phone and the watch. Values in, strings out; no locale lookups. */
object Format {
    const val PLACEHOLDER = "—"
    const val METERS_PER_MILE = 1609.344
    const val FEET_PER_METER = 3.28084

    /** `m:ss`, or `h:mm:ss` from one hour. */
    fun duration(seconds: Double): String {
        val s = seconds.roundToLong().coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    /** Pace as `m:ss` per km or per mile; placeholder when stopped or implausibly slow. */
    fun pace(secondsPerKm: Double?, units: Units): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0) return PLACEHOLDER
        val perUnit = if (units == Units.IMPERIAL) secondsPerKm * METERS_PER_MILE / 1000 else secondsPerKm
        if (perUnit >= 30 * 60) return PLACEHOLDER
        val s = perUnit.roundToInt()
        return "${s / 60}:${two((s % 60).toLong())}"
    }

    /** Distance with two decimals and a decimal comma, in km or miles. */
    fun distance(meters: Double, units: Units): String {
        val v = if (units == Units.IMPERIAL) meters / METERS_PER_MILE else meters / 1000
        val hundredths = floor(v * 100 + 1e-9).toLong()
        return "${hundredths / 100},${two(hundredths % 100)}"
    }

    fun elevation(meters: Double, units: Units): String =
        (if (units == Units.IMPERIAL) meters * FEET_PER_METER else meters).roundToInt().toString()

    fun distanceUnit(units: Units) = if (units == Units.IMPERIAL) "mi" else "km"
    fun paceUnit(units: Units) = if (units == Units.IMPERIAL) "/mi" else "/km"
    fun elevationUnit(units: Units) = if (units == Units.IMPERIAL) "ft" else "m"

    private fun two(n: Long) = n.toString().padStart(2, '0')
}

enum class Units { METRIC, IMPERIAL }
