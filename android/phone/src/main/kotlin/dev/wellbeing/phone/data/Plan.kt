package dev.wellbeing.phone.data

import dev.wellbeing.running.RoutePath
import dev.wellbeing.running.Workout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The session as the coaching engines read it; null if the stored document no longer reads. */
fun PlanWorkoutEntity.workout(): Workout? = Workout.parse(steps, id, name).getOrNull()

/** The route with its points; null if they are unusable. */
fun PlanRouteEntity.path(): RoutePath? = runCatching {
    val o = Json.parseToJsonElement(points).jsonObject
    fun arr(k: String) = (o[k] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }?.toDoubleArray()
    RoutePath(id, version, name, arr("lat")!!, arr("lon")!!, arr("dist")!!, arr("ele"))
}.getOrNull()

/** A small drawing of the route for list rows: every n-th point, as (lat, lon). */
fun PlanRouteEntity.preview(max: Int = 80): List<Pair<Double, Double>> {
    val p = path() ?: return emptyList()
    val step = maxOf(1, p.lat.size / max)
    return p.lat.indices.filter { it % step == 0 || it == p.lat.lastIndex }.map { p.lat[it] to p.lon[it] }
}
