package dev.wellbeing.running

import kotlin.math.roundToInt
import kotlin.math.sin

/** Builds a run second by second, heading north, as the phone's sensors would report it. */
class Script(
    val t0: Long = 1_794_760_000_000L,
    private val lat0: Double = 48.85,
    private val lon: Double = 2.35,
    private val withHr: Boolean = true,
) {
    val events = mutableListOf<Event>()
    val hr = mutableListOf<HrSample>()
    var t = t0
        private set
    private var north = 0.0
    private var stepDebt = 0.0
    private var second = 0

    init {
        fix()
    }

    private fun fix(accuracy: Float = 5f) {
        events += Event.Location(t, lat0 + north / M_PER_DEG, lon, 35.0, accuracy)
    }

    /** [seconds] of movement at [speed] m/s, with steps at 170 per minute and heart rate around 140. */
    fun move(seconds: Int, speed: Double = 2.8): Script {
        repeat(seconds) {
            t += 1000; second++
            north += speed
            if (speed > 0.5) {
                stepDebt += 170 / 60.0
                val n = stepDebt.toInt()
                stepDebt -= n
                events += Event.Steps(t, n)
            }
            fix()
            if (withHr) hr += HrSample(t, 140 + (10 * sin(second / 50.0)).roundToInt())
        }
        return this
    }

    fun stand(seconds: Int) = move(seconds, 0.0)

    /** A single fix far away from the current position, then back. */
    fun jump(meters: Double): Script {
        t += 1000; second++
        events += Event.Location(t, lat0 + (north + meters) / M_PER_DEG, lon, 35.0, 5f)
        return this
    }

    fun start() = also { events += Event.Start(t) }
    fun pause() = also { events += Event.Pause(t) }
    fun resume() = also { events += Event.Resume(t) }
    fun lap() = also { events += Event.Lap(t) }
    fun stop() = also { events += Event.Stop(t) }

    fun engine(config: SessionEngine.Config = SessionEngine.Config()) = SessionEngine.replay(config, events)

    companion object {
        /** Metres per degree of latitude on the sphere the engine and the server use. */
        const val M_PER_DEG = 111_194.93

        /** The run behind the fixture shared with the server tests. */
        fun fixture(withHr: Boolean) = Script(withHr = withHr)
            .start().move(600).lap()
            .move(100).pause().stand(60).resume()
            .move(500).lap()
            .move(300).stand(40).move(300)
            .stop()

        /** Stopped by mistake after 10 minutes, walked about 340 m during two minutes on the summary, resumed. */
        fun resumed() = Script()
            .start().move(600).stop()
            .move(120, speed = 2.8)
            .resume().move(300)
            .stop()
    }
}
