package dev.wellbeing.running

/** Flat form of an [Event], as stored in the log of the run in progress. */
data class EventRow(val type: Int, val t: Long, val a: Double? = null, val b: Double? = null, val c: Double? = null, val d: Double? = null)

/** Converts logged events to and from rows. Live heart rate is not logged: it does not shape the run. */
object EventCodec {
    private const val START = 0
    private const val PAUSE = 1
    private const val RESUME = 2
    private const val LAP = 3
    private const val STOP = 4
    private const val LOCATION = 5
    private const val STEPS = 6

    fun encode(e: Event): EventRow? = when (e) {
        is Event.Start -> EventRow(START, e.t)
        is Event.Pause -> EventRow(PAUSE, e.t)
        is Event.Resume -> EventRow(RESUME, e.t)
        is Event.Lap -> EventRow(LAP, e.t)
        is Event.Stop -> EventRow(STOP, e.t)
        is Event.Location -> EventRow(LOCATION, e.t, e.lat, e.lon, e.altitude, e.accuracy?.toDouble())
        is Event.Steps -> EventRow(STEPS, e.t, e.count.toDouble())
        is Event.HeartRate -> null
    }

    fun decode(r: EventRow): Event? = when (r.type) {
        START -> Event.Start(r.t)
        PAUSE -> Event.Pause(r.t)
        RESUME -> Event.Resume(r.t)
        LAP -> Event.Lap(r.t)
        STOP -> Event.Stop(r.t)
        LOCATION -> if (r.a == null || r.b == null) null else Event.Location(r.t, r.a, r.b, r.c, r.d?.toFloat())
        STEPS -> Event.Steps(r.t, r.a?.toInt() ?: 0)
        else -> null
    }
}
