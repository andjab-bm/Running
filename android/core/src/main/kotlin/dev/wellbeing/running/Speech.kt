package dev.wellbeing.running

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Cues waiting to be delivered. When several fall together they go out by importance — step
 * changes, then alerts, then distance announcements — in the order they came within one level;
 * a cue that waited too long is dropped rather than said late. While the run is paused only the
 * pause itself, and a step changed by a lap press, are delivered.
 */
class CueQueue(private val staleMs: Long = STALE_MS) {
    companion object {
        const val STALE_MS = 20_000L
        /** Kinds still delivered while paused. */
        private val WHILE_PAUSED = setOf(CueKind.PAUSE, CueKind.STEP, CueKind.COMPLETE)
    }

    private val waiting = mutableListOf<Cue>()

    val size get() = waiting.size

    fun add(cue: Cue) {
        waiting += cue
    }

    fun addAll(cues: List<Cue>) = cues.forEach(::add)

    /** The next cue to deliver at [now], or null; stale and silenced cues are dropped on the way. */
    fun next(now: Long, paused: Boolean): Cue? {
        while (waiting.isNotEmpty()) {
            val best = waiting.minWith(compareBy<Cue> { it.kind.priority }.thenBy { it.t })
            waiting.remove(best)
            if (now - best.t > staleMs) continue
            if (paused && best.kind !in WHILE_PAUSED) continue
            return best
        }
        return null
    }

    fun clear() = waiting.clear()
}

/**
 * The French sentences of the voice channel, with figures written the way a runner says them
 * ("5 minutes 52 au kilomètre", not "5:52"), so the phone's speech engine reads them naturally.
 */
object Speech {
    private const val M_PER_MILE = Format.METERS_PER_MILE

    fun say(cue: Cue, units: Units, zoneBounds: List<Int> = emptyList()): String = when (cue) {
        is Cue.StepStarted -> step(cue, units, zoneBounds)
        is Cue.Countdown -> "Encore ${cue.seconds} secondes."
        is Cue.SessionComplete -> "Séance terminée. Vous continuez en course libre."
        is Cue.TargetOut -> targetOut(cue, units, zoneBounds)
        is Cue.Pacer -> gap(cue.gapS).replaceFirstChar { it.uppercase() } + "."
        is Cue.OffRoute -> "Hors du parcours, à ${metres(cue.distanceM)}."
        is Cue.BackOnRoute -> "De retour sur le parcours."
        is Cue.Arrival -> "Arrivée au bout du parcours."
        is Cue.Split -> split(cue, units)
        is Cue.Paused -> if (cue.auto) "Pause automatique." else "Pause."
        is Cue.Resumed -> "Reprise."
    }

    private fun step(cue: Cue.StepStarted, units: Units, zones: List<Int>): String {
        val s = cue.step.step
        val parts = mutableListOf(kind(s.kind), end(s.end, units))
        target(s.target, units, zones)?.let { parts += it }
        val round = cue.step.round
        val rounds = cue.step.rounds
        val tail = if (round != null && rounds != null) " Répétition $round sur $rounds." else ""
        return parts.joinToString(", ") + "." + tail
    }

    fun kind(k: StepKind) = when (k) {
        StepKind.WARMUP -> "Échauffement"
        StepKind.EFFORT -> "Effort"
        StepKind.RECOVERY -> "Récupération"
        StepKind.COOLDOWN -> "Retour au calme"
    }

    private fun end(e: StepEnd, units: Units) = when (e) {
        is StepEnd.Time -> duration(e.seconds)
        is StepEnd.Distance -> distance(e.meters, units)
        StepEnd.Lap -> "jusqu'à l'appui sur Tour"
    }

    private fun target(t: StepTarget, units: Units, zones: List<Int>): String? = when (t) {
        StepTarget.None -> null
        is StepTarget.Pace -> "entre ${paceFigure(t.fast, units)} et ${pace(t.slow, units)}"
        is StepTarget.HrZone -> {
            val lo = zones.getOrNull(t.zone - 1)
            val hi = zones.getOrNull(t.zone)
            if (lo != null && hi != null) "zone ${t.zone}, de $lo à $hi battements" else "zone ${t.zone}"
        }
    }

    private fun targetOut(cue: Cue.TargetOut, units: Units, zones: List<Int>): String {
        val what = when (cue.state) {
            TargetState.TOO_FAST -> "Trop rapide."
            TargetState.TOO_SLOW -> "Trop lent."
            TargetState.BELOW -> "Fréquence cardiaque trop basse."
            TargetState.ABOVE -> "Fréquence cardiaque trop haute."
            else -> "Hors de la cible."
        }
        val aim = target(cue.target, units, zones) ?: return what
        return "$what Visez $aim."
    }

    private fun split(cue: Cue.Split, units: Units): String {
        val unit = if (units == Units.IMPERIAL) "mile" else "kilomètre"
        val sb = StringBuilder("${distance(cue.distance, units)}, en ${duration(cue.movingMs / 1000.0)}.")
        cue.splitPace?.let { sb.append(" Dernier $unit en ${pace(it, units)}.") }
        cue.avgPace?.let { sb.append(" Allure moyenne ${pace(it, units)}.") }
        cue.heartRate?.let { sb.append(" Fréquence cardiaque $it.") }
        return sb.toString()
    }

    /** `45 secondes`, `1 minute 30`, `25 minutes`, `1 heure 2 minutes`. */
    fun duration(seconds: Double): String {
        val s = seconds.roundToLong().coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return when {
            h > 0 -> listOfNotNull(plural(h, "heure"), if (m > 0) plural(m, "minute") else null).joinToString(" ")
            m == 0L -> plural(sec, "seconde")
            sec == 0L -> plural(m, "minute")
            else -> "${plural(m, "minute")} $sec"
        }
    }

    /** `5 minutes 52 au kilomètre`, `8 minutes au mile`. */
    fun pace(secondsPerKm: Double, units: Units) = paceFigure(secondsPerKm, units) + if (units == Units.IMPERIAL) " au mile" else " au kilomètre"

    private fun paceFigure(secondsPerKm: Double, units: Units): String {
        val s = (if (units == Units.IMPERIAL) secondsPerKm * M_PER_MILE / 1000 else secondsPerKm).roundToInt()
        return if (s % 60 == 0) plural((s / 60).toLong(), "minute") else "${plural((s / 60).toLong(), "minute")} ${s % 60}"
    }

    /** `400 mètres`, `3 kilomètres`, `2,5 kilomètres`, `1 mile`, `0,5 mile`. */
    fun distance(meters: Double, units: Units): String {
        if (units == Units.IMPERIAL) {
            val mi = (meters / M_PER_MILE * 10).roundToInt() / 10.0
            return "${decimal(mi)} ${if (mi >= 2) "miles" else "mile"}"
        }
        if (meters < 1000) return metres(meters)
        val km = (meters / 100).roundToInt() / 10.0
        return "${decimal(km)} ${if (km >= 2) "kilomètres" else "kilomètre"}"
    }

    private fun metres(m: Double) = "${(m / 10).roundToInt() * 10} mètres"

    /** `30 secondes de retard`, `1 minute 10 d'avance`. */
    fun gap(gapS: Double): String = duration(abs(gapS)) + if (gapS < 0) " de retard" else " d'avance"

    private fun decimal(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

    private fun plural(n: Long, word: String) = "$n $word${if (n > 1) "s" else ""}"
}
