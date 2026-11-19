package dev.wellbeing.running

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Flat form of an [Event], as stored in the log of the run in progress. [text] carries the plan
 * (session document, route and pace target) as JSON; every other event fits in the numbers.
 */
data class EventRow(
    val type: Int,
    val t: Long,
    val a: Double? = null,
    val b: Double? = null,
    val c: Double? = null,
    val d: Double? = null,
    val text: String? = null,
)

/** Converts logged events to and from rows. Live heart rate is not logged: it does not shape the run. */
object EventCodec {
    private const val START = 0
    private const val PAUSE = 1
    private const val RESUME = 2
    private const val LAP = 3
    private const val STOP = 4
    private const val LOCATION = 5
    private const val STEPS = 6
    private const val PLAN = 7

    fun encode(e: Event): EventRow? = when (e) {
        is Event.Start -> EventRow(START, e.t)
        is Event.Pause -> EventRow(PAUSE, e.t)
        is Event.Resume -> EventRow(RESUME, e.t)
        is Event.Lap -> EventRow(LAP, e.t)
        is Event.Stop -> EventRow(STOP, e.t)
        is Event.Location -> EventRow(LOCATION, e.t, e.lat, e.lon, e.altitude, e.accuracy?.toDouble())
        is Event.Steps -> EventRow(STEPS, e.t, e.count.toDouble())
        is Event.HeartRate -> null
        is Event.Plan -> EventRow(PLAN, e.t, text = planJson(e).toString())
    }

    fun decode(r: EventRow): Event? = when (r.type) {
        START -> Event.Start(r.t)
        PAUSE -> Event.Pause(r.t)
        RESUME -> Event.Resume(r.t)
        LAP -> Event.Lap(r.t)
        STOP -> Event.Stop(r.t)
        LOCATION -> if (r.a == null || r.b == null) null else Event.Location(r.t, r.a, r.b, r.c, r.d?.toFloat())
        STEPS -> Event.Steps(r.t, r.a?.toInt() ?: 0)
        PLAN -> r.text?.let { runCatching { readPlan(r.t, it) }.getOrNull() }
        else -> null
    }

    private fun planJson(p: Event.Plan) = buildJsonObject {
        p.workout?.let { w ->
            put("workout", buildJsonObject {
                w.id?.let { put("id", it) }
                put("name", w.name)
                put("steps", w.stepsJson())
            })
        }
        p.route?.let { r ->
            put("route", buildJsonObject { put("id", r.id); put("version", r.version); put("name", r.name) })
        }
        p.pacer?.let { t ->
            put("pacer", when (t) {
                is PacerTarget.Pace -> buildJsonObject { put("target", "pace"); put("value", t.secondsPerKm) }
                is PacerTarget.Time -> buildJsonObject { put("target", "time"); put("value", t.seconds); put("grade", t.gradeAdjusted) }
            })
        }
    }

    private fun readPlan(t: Long, text: String): Event.Plan {
        val o = Json.parseToJsonElement(text) as JsonObject
        val workout = (o["workout"] as? JsonObject)?.let { w ->
            val id = (w["id"] as? JsonPrimitive)?.longOrNull
            val name = (w["name"] as? JsonPrimitive)?.content ?: ""
            Workout.parse(w["steps"].toString(), id, name).getOrThrow()
        }
        val route = (o["route"] as? JsonObject)?.let { r ->
            RouteRef((r["id"] as JsonPrimitive).longOrNull!!, (r["version"] as JsonPrimitive).longOrNull!!, (r["name"] as JsonPrimitive).content)
        }
        val pacer = (o["pacer"] as? JsonObject)?.let { p ->
            val value = (p["value"] as JsonPrimitive).doubleOrNull!!
            when ((p["target"] as JsonPrimitive).content) {
                "pace" -> PacerTarget.Pace(value)
                else -> PacerTarget.Time(value, (p["grade"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }
        }
        return Event.Plan(t, workout, route, pacer)
    }
}
