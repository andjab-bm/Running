package dev.wellbeing.phone.ui

import dev.wellbeing.running.Format
import dev.wellbeing.running.PlannedStep
import dev.wellbeing.running.Speech
import dev.wellbeing.running.StepEnd
import dev.wellbeing.running.StepKind
import dev.wellbeing.running.StepTarget
import dev.wellbeing.running.TargetState
import dev.wellbeing.running.Units
import dev.wellbeing.running.Workout

/**
 * How sessions and steps read on the phone — the same words and rules as the web's
 * `web/src/lib/workout.ts`. Pure, so the field and step texts are unit-tested.
 */
object WorkoutText {
    /** Seconds per km assumed for a distance step with no pace target, in the outline only. */
    private const val OUTLINE_PACE = 360.0
    /** Width given to a step that ends on lap, in the outline. */
    private const val OUTLINE_LAP_S = 90.0

    fun kind(k: StepKind) = Speech.kind(k)

    /** `400 m`, `1,5 km`, `0,25 mi`, `8:00` or `jusqu'à Tour`. */
    fun end(e: StepEnd, units: Units): String = when (e) {
        is StepEnd.Time -> Format.duration(e.seconds)
        StepEnd.Lap -> "jusqu'à Tour"
        is StepEnd.Distance -> distance(e.meters, units)
    }

    fun distance(meters: Double, units: Units): String = when {
        units == Units.IMPERIAL -> "${trim(meters / Format.METERS_PER_MILE)} mi"
        meters >= 1000 -> "${trim(meters / 1000)} km"
        else -> "${Math.round(meters)} m"
    }

    private fun trim(v: Double) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').replace('.', ',')

    /** `5:00–5:15 /km`, `Zone 2 · 114–133 bpm`, or empty without a target. */
    fun target(t: StepTarget, units: Units, bounds: List<Int>): String = when (t) {
        StepTarget.None -> ""
        is StepTarget.Pace -> "${Format.pace(t.fast, units)}–${Format.pace(t.slow, units)} ${Format.paceUnit(units)}"
        is StepTarget.HrZone -> {
            val lo = bounds.getOrNull(t.zone - 1)
            val hi = bounds.getOrNull(t.zone)
            if (lo != null && hi != null) "Zone ${t.zone} · $lo–$hi bpm" else "Zone ${t.zone}"
        }
    }

    /** `Effort 3/6` for a step of a repeated block, `Échauffement` otherwise. */
    fun label(p: PlannedStep) = kind(p.step.kind) + if (p.round != null) " ${p.round}/${p.rounds}" else ""

    fun verdict(s: TargetState): String? = when (s) {
        TargetState.IN -> "Dans la cible"
        TargetState.TOO_FAST -> "Trop vite"
        TargetState.TOO_SLOW -> "Trop lent"
        TargetState.BELOW -> "Trop bas"
        TargetState.ABOVE -> "Trop haut"
        else -> null
    }

    fun weight(p: PlannedStep): Double = when (val e = p.step.end) {
        is StepEnd.Time -> e.seconds
        is StepEnd.Distance -> e.meters / 1000 * ((p.step.target as? StepTarget.Pace)?.let { (it.fast + it.slow) / 2 } ?: OUTLINE_PACE)
        StepEnd.Lap -> OUTLINE_LAP_S
    }

    /**
     * The session's length, never invented: exact when every step is timed, "au moins" when one
     * ends on lap, an estimate when distances have a pace target, "durée inconnue" otherwise.
     */
    fun total(w: Workout, units: Units): String {
        val steps = w.unfold()
        val open = steps.any { it.step.end == StepEnd.Lap }
        val timed = steps.sumOf { (it.step.end as? StepEnd.Time)?.seconds ?: 0.0 }
        val byDistance = steps.filter { it.step.end is StepEnd.Distance }
        val metres = byDistance.sumOf { (it.step.end as StepEnd.Distance).meters }
        val time = when {
            byDistance.isEmpty() -> (if (open) "au moins " else "") + Format.duration(timed)
            byDistance.any { it.step.target !is StepTarget.Pace } -> null
            else -> (if (open) "au moins " else "≈ ") + Format.duration(timed + byDistance.sumOf { weight(it) })
        }
        val dist = if (byDistance.size == steps.size) "${Format.distance(metres, units)} ${Format.distanceUnit(units)}" else null
        return listOfNotNull(time, dist).joinToString(" · ").ifEmpty { "durée inconnue" }
    }
}
