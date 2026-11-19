package dev.wellbeing.phone.record

import dev.wellbeing.running.Cue
import dev.wellbeing.running.CueKind
import dev.wellbeing.running.CueQueue
import dev.wellbeing.running.VibrationPatterns

/** Which of the three channels the runner switched on. */
data class Channels(val voice: Boolean, val sound: Boolean, val vibration: Boolean)

/** What the player needs from the phone: the Android side, faked in the tests. */
interface CueOutputs {
    /** A call is ringing or in progress: nothing may be spoken or played. */
    fun inCall(): Boolean

    /** An offline French voice is ready to speak. */
    fun voiceReady(): Boolean

    /** Asks other audio to lower for a moment; false when refused. */
    fun focus(): Boolean

    /** Lets other audio return to its level. */
    fun release()

    fun tone(kind: CueKind, done: () -> Unit)
    fun speak(text: String, done: () -> Unit)
    fun vibrate(pattern: LongArray)

    /** Runs [block] on the player's thread after [ms]. */
    fun after(ms: Long, block: () -> Unit)
}

/**
 * Renders the cues the engines raise, one at a time, through the channels switched on. Cues waiting
 * behind another go out by importance and are dropped when stale ([CueQueue]); during a call only
 * vibration is given. Not thread-safe: called on the main thread only.
 */
class CuePlayer(
    private val out: CueOutputs,
    private val channels: () -> Channels,
    private val sentence: (Cue) -> String,
    private val paused: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** A cue that has not finished by then (speech engine stuck) no longer holds the others. */
        const val MAX_CUE_MS = 15_000L
        /** Quiet between two cues, so two vibrations are felt as two. */
        const val GAP_MS = 250L
    }

    private val queue = CueQueue()
    /** The cue of the run being delivered; samples from the settings do not hold the queue. */
    private var current = 0
    private var busy = false

    fun add(cue: Cue) {
        queue.add(cue)
        pump()
    }

    /** A sample from the settings: delivered now, with the current channels, run or not. */
    fun test(cue: Cue) = deliver(cue, through = false)

    /** Forgets what is waiting, at the end of a run. */
    fun clear() = queue.clear()

    private fun pump() {
        if (busy) return
        val cue = queue.next(clock(), paused()) ?: return
        deliver(cue, through = true)
    }

    private fun deliver(cue: Cue, through: Boolean) {
        val id = if (through) ++current else -1
        if (through) busy = true
        val ch = channels()
        // Each part reports when it is over; the cue is done when all are.
        var pending = 1
        val finish = {
            if (through && busy && id == current) {
                busy = false
                out.after(GAP_MS) { pump() }
            }
        }
        val part = { if (--pending == 0) finish() }

        if (ch.vibration) {
            val pattern = VibrationPatterns.of(cue.kind)
            out.vibrate(pattern)
            pending++
            out.after(pattern.sum()) { part() }
        }
        val call = out.inCall()
        val sound = ch.sound && !call
        val voice = ch.voice && !call && out.voiceReady()
        if ((sound || voice) && out.focus()) {
            pending++
            var released = false
            val audioDone = {
                if (!released) {
                    released = true
                    out.release()
                    part()
                }
            }
            val speak = { if (voice) out.speak(sentence(cue)) { audioDone() } else audioDone() }
            if (sound) out.tone(cue.kind) { speak() } else speak()
            out.after(MAX_CUE_MS) { audioDone() }
        }
        part()
    }
}
