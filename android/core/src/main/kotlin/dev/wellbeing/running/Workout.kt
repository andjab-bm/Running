package dev.wellbeing.running

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * A structured session: the document the web edits, the server validates and the phone runs.
 * It mirrors `server/src/pipeline/workout.rs`; both sides are tested against the fixtures of
 * `server/tests/fixtures/workouts/`. Durations are seconds, distances metres, paces seconds per km.
 */
enum class StepKind(val json: String) { WARMUP("warmup"), EFFORT("effort"), RECOVERY("recovery"), COOLDOWN("cooldown") }

sealed interface StepEnd {
    /** After this many seconds of moving time. */
    data class Time(val seconds: Double) : StepEnd
    /** After this many metres. */
    data class Distance(val meters: Double) : StepEnd
    /** When the runner presses lap. */
    data object Lap : StepEnd
}

sealed interface StepTarget {
    data object None : StepTarget
    /** Between [fast] and [slow], in seconds per kilometre. */
    data class Pace(val fast: Double, val slow: Double) : StepTarget
    /** A heart-rate zone of the profile, 1 to 5. */
    data class HrZone(val zone: Int) : StepTarget
}

data class WorkoutStep(val kind: StepKind, val end: StepEnd, val target: StepTarget = StepTarget.None)

sealed interface WorkoutItem {
    data class Single(val step: WorkoutStep) : WorkoutItem
    /** A block done [count] times; blocks are not nested. */
    data class Repeat(val count: Int, val skipLastRecovery: Boolean, val steps: List<WorkoutStep>) : WorkoutItem
}

/** One step of the unfolded session, with where it sits in its repeated block, if any. */
data class PlannedStep(
    val step: WorkoutStep,
    /** 1-based round of its block, and the block's count; null outside a block. */
    val round: Int? = null,
    val rounds: Int? = null,
)

data class Workout(val id: Long?, val name: String, val items: List<WorkoutItem>) {
    /** The steps in the order they are run, repeats expanded (same rule as the server). */
    fun unfold(): List<PlannedStep> = items.flatMap { item ->
        when (item) {
            is WorkoutItem.Single -> listOf(PlannedStep(item.step))
            is WorkoutItem.Repeat -> (1..item.count).flatMap { round ->
                item.steps.mapIndexedNotNull { i, s ->
                    val closing = item.skipLastRecovery && round == item.count && i == item.steps.lastIndex && s.kind == StepKind.RECOVERY
                    if (closing) null else PlannedStep(s, round, item.count)
                }
            }
        }
    }

    /** The steps as the server stores them, for the event log and the journal patch. */
    fun stepsJson(): JsonArray = buildJsonArray {
        for (item in items) when (item) {
            is WorkoutItem.Single -> add(stepJson(item.step))
            is WorkoutItem.Repeat -> add(buildJsonObject {
                put("type", "repeat")
                put("count", item.count)
                put("skip_last_recovery", item.skipLastRecovery)
                put("steps", buildJsonArray { item.steps.forEach { add(stepJson(it)) } })
            })
        }
    }

    private fun stepJson(s: WorkoutStep): JsonObject = buildJsonObject {
        put("type", "step")
        put("kind", s.kind.json)
        put("end", when (val e = s.end) {
            is StepEnd.Time -> buildJsonObject { put("type", "time"); put("value", e.seconds) }
            is StepEnd.Distance -> buildJsonObject { put("type", "distance"); put("value", e.meters) }
            StepEnd.Lap -> buildJsonObject { put("type", "lap") }
        })
        put("target", when (val t = s.target) {
            StepTarget.None -> buildJsonObject { put("type", "none") }
            is StepTarget.Pace -> buildJsonObject { put("type", "pace"); put("fast", t.fast); put("slow", t.slow) }
            is StepTarget.HrZone -> buildJsonObject { put("type", "hr_zone"); put("zone", t.zone) }
        })
    }

    companion object {
        const val MIN_TIME_S = 5.0
        const val MAX_TIME_S = 6.0 * 3600
        const val MIN_DISTANCE_M = 50.0
        const val MAX_DISTANCE_M = 100_000.0
        const val MIN_REPEATS = 2
        const val MAX_REPEATS = 50
        /** Bounds of a pace target in seconds per kilometre (2:00 to 20:00). */
        const val MIN_PACE_S_PER_KM = 120.0
        const val MAX_PACE_S_PER_KM = 1200.0
        const val MAX_STEPS = 500

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads and checks the steps of a session (a JSON array, or an object with `steps`), with
         * the same rules as the server. Returns the reason in English when it is refused.
         */
        fun parse(text: String, id: Long? = null, name: String = ""): Result<Workout> = runCatching {
            val root = json.parseToJsonElement(text)
            val (steps, docName) = when (root) {
                is JsonArray -> root to name
                is JsonObject -> (root["steps"] as? JsonArray ?: fail("The document has no steps.")) to
                    ((root["name"] as? JsonPrimitive)?.content ?: name)
                else -> fail("The document is not a session.")
            }
            Workout(id, docName, readItems(steps)).also { it.check() }
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

        private fun readItems(array: JsonArray): List<WorkoutItem> = array.map { el ->
            val o = el as? JsonObject ?: fail("A step must be an object.")
            when (o.str("type")) {
                "step" -> WorkoutItem.Single(readStep(o))
                "repeat" -> {
                    val count = o.num("count")?.takeIf { it == Math.floor(it) }?.toInt() ?: fail("A block needs a whole repeat count.")
                    val skip = (o["skip_last_recovery"] as? JsonPrimitive)?.booleanOrNull ?: false
                    val inner = o["steps"] as? JsonArray ?: fail("A block needs steps.")
                    WorkoutItem.Repeat(count, skip, inner.map { s ->
                        val so = s as? JsonObject ?: fail("A step must be an object.")
                        if (so.str("type") != "step") fail("Repeated blocks cannot be nested.")
                        readStep(so)
                    })
                }
                else -> fail("Unknown item type.")
            }
        }

        private fun readStep(o: JsonObject): WorkoutStep {
            val kind = StepKind.entries.firstOrNull { it.json == o.str("kind") } ?: fail("Unknown step kind.")
            val e = o["end"] as? JsonObject ?: fail("A step needs an end.")
            val end = when (e.str("type")) {
                "time" -> StepEnd.Time(e.num("value") ?: fail("A timed step needs a value."))
                "distance" -> StepEnd.Distance(e.num("value") ?: fail("A distance step needs a value."))
                "lap" -> StepEnd.Lap
                else -> fail("Unknown end type.")
            }
            val t = o["target"]
            val target = if (t == null || t is JsonNull) StepTarget.None else {
                val to = t as? JsonObject ?: fail("A target must be an object.")
                when (to.str("type")) {
                    "none" -> StepTarget.None
                    "pace" -> StepTarget.Pace(to.num("fast") ?: fail("A pace target needs bounds."), to.num("slow") ?: fail("A pace target needs bounds."))
                    "hr_zone" -> StepTarget.HrZone(to.num("zone")?.takeIf { it == Math.floor(it) }?.toInt() ?: fail("A zone target needs a zone."))
                    else -> fail("Unknown target type.")
                }
            }
            return WorkoutStep(kind, end, target)
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        private fun Workout.check() {
            if (items.isEmpty()) fail("A session needs at least one step.")
            for (item in items) when (item) {
                is WorkoutItem.Single -> checkStep(item.step)
                is WorkoutItem.Repeat -> {
                    if (item.count !in MIN_REPEATS..MAX_REPEATS) fail("A block is repeated between 2 and 50 times.")
                    if (item.steps.isEmpty()) fail("A repeated block needs at least one step.")
                    item.steps.forEach(::checkStep)
                }
            }
            val n = unfold().size
            if (n == 0) fail("A session needs at least one step.")
            if (n > MAX_STEPS) fail("A session unfolds into at most $MAX_STEPS steps.")
        }

        private fun checkStep(s: WorkoutStep) {
            when (val e = s.end) {
                is StepEnd.Time -> if (e.seconds !in MIN_TIME_S..MAX_TIME_S) fail("A step lasts between 5 seconds and 6 hours.")
                is StepEnd.Distance -> if (e.meters !in MIN_DISTANCE_M..MAX_DISTANCE_M) fail("A step covers between 50 m and 100 km.")
                StepEnd.Lap -> Unit
            }
            when (val t = s.target) {
                is StepTarget.Pace -> {
                    if (t.fast !in MIN_PACE_S_PER_KM..MAX_PACE_S_PER_KM || t.slow !in MIN_PACE_S_PER_KM..MAX_PACE_S_PER_KM) {
                        fail("A pace target lies between 2:00 and 20:00 per kilometre.")
                    }
                    if (t.fast >= t.slow) fail("The fast bound of a pace target must be faster than its slow bound.")
                }
                is StepTarget.HrZone -> if (t.zone !in 1..5) fail("A heart-rate zone target is a zone from 1 to 5.")
                StepTarget.None -> Unit
            }
        }
    }
}
