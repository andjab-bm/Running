package dev.wellbeing.phone.record

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.wellbeing.running.Cue
import dev.wellbeing.running.CueKind
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Speech
import dev.wellbeing.running.TonePatterns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whether the voice channel can speak: still looking, ready, or no offline French voice. */
enum class VoiceStatus { CHECKING, READY, MISSING }

/**
 * The phone's cue player, shared by the recording service (cues of the run) and the coaching
 * settings (samples). The speech engine is started by the first user and shut down with the last,
 * so nothing stays loaded between runs.
 */
object Cues {
    private var users = 0
    private var outputs: AndroidCueOutputs? = null
    private var player: CuePlayer? = null

    private val _voice = MutableStateFlow(VoiceStatus.CHECKING)
    val voice: StateFlow<VoiceStatus> = _voice

    /** Starts the player if needed; pair each call with [release]. Main thread only. */
    fun acquire(context: Context): CuePlayer {
        users++
        player?.let { outputs?.recheck(); return it }
        val out = AndroidCueOutputs(context.applicationContext) { _voice.value = it }
        outputs = out
        return CuePlayer(
            out,
            channels = { RunController.settings.let { Channels(it.cueVoice, it.cueSound, it.cueVibration) } },
            sentence = { cue -> RunController.settings.let { Speech.say(cue, it.units, it.zones.bounds.map(Double::roundToInt)) } },
            paused = { RunController.phase == Phase.PAUSED || RunController.phase == Phase.AUTO_PAUSED },
        ).also { player = it }
    }

    fun release() {
        if (users == 0 || --users > 0) return
        player?.clear()
        player = null
        outputs?.shutdown()
        outputs = null
        _voice.value = VoiceStatus.CHECKING
    }
}

/**
 * Speech, tones and vibration on Android. Voice and tones take a transient "may duck" audio focus
 * with the navigation-guidance usage, so music or a podcast is lowered and comes back; they follow
 * the media route, through the headphones when connected. Speech uses an offline French voice of
 * the phone's own engine only, so no text leaves the phone.
 */
class AndroidCueOutputs(private val context: Context, private val onVoice: (VoiceStatus) -> Unit) : CueOutputs {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val pcm = mutableMapOf<CueKind, ShortArray>()
    private var voice: Voice? = null
    private var ready = false
    private val waiting = mutableMapOf<String, () -> Unit>()
    private var utterance = 0

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        recheck()
    }.apply {
        setAudioAttributes(attributes)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { finish(id) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { finish(id) }
            override fun onError(id: String, errorCode: Int) { finish(id) }
            override fun onStop(id: String, interrupted: Boolean) { finish(id) }
        })
    }

    private fun finish(id: String) {
        main.post { waiting.remove(id)?.invoke() }
    }

    /** Looks again for an offline French voice, after one may have been installed. */
    fun recheck() {
        if (!ready) {
            onVoice(VoiceStatus.CHECKING)
            return
        }
        voice = frenchVoice(runCatching { tts.voices }.getOrNull().orEmpty())?.also { tts.voice = it }
        onVoice(if (voice != null) VoiceStatus.READY else VoiceStatus.MISSING)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        waiting.values.toList().forEach { it() }
        waiting.clear()
    }

    override fun inCall() = audio.mode != AudioManager.MODE_NORMAL

    override fun voiceReady() = ready && voice != null

    override fun focus() = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun release() {
        audio.abandonAudioFocusRequest(focusRequest)
    }

    override fun tone(kind: CueKind, done: () -> Unit) {
        val samples = pcm.getOrPut(kind) { synthesise(kind) }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        }.getOrNull()
        if (track == null) {
            done()
            return
        }
        track.write(samples, 0, samples.size)
        track.play()
        main.postDelayed({ track.release(); done() }, TonePatterns.lengthMs(kind) + TAIL_MS)
    }

    override fun speak(text: String, done: () -> Unit) {
        val id = "cue-${++utterance}"
        waiting[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) waiting.remove(id)?.invoke()
    }

    override fun vibrate(pattern: LongArray) {
        // Alarm usage: the runner asked for these, so they are felt in silent mode too.
        context.getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createWaveform(pattern, -1), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }

    override fun after(ms: Long, block: () -> Unit) {
        main.postDelayed(block, ms)
    }

    companion object {
        private const val RATE = 22_050
        private const val TAIL_MS = 80L
        /** Fade in and out of each note, so it starts and ends without a click. */
        private const val FADE_MS = 8
        private const val LEVEL = 0.55

        /** An installed French voice that works offline, the best quality first, France before other countries. */
        fun frenchVoice(voices: Collection<Voice>): Voice? = voices
            .filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareByDescending<Voice> { it.locale.country == "FR" }.thenByDescending { it.quality }.thenBy { it.latency })
            .firstOrNull()

        fun synthesise(kind: CueKind): ShortArray {
            val out = ArrayList<Short>()
            for (t in TonePatterns.of(kind)) {
                val n = RATE * t.ms / 1000
                val fade = RATE * FADE_MS / 1000
                for (i in 0 until n) {
                    val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                    val v = if (t.hz == 0) 0.0 else sin(2 * PI * t.hz * i / RATE) * env * LEVEL
                    out += (v * Short.MAX_VALUE).toInt().toShort()
                }
            }
            return out.toShortArray()
        }
    }
}
