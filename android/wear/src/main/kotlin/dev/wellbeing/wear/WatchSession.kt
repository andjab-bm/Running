package dev.wellbeing.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import dev.wellbeing.running.HrBuffer
import dev.wellbeing.running.HrSample
import android.os.VibrationEffect
import android.os.Vibrator
import dev.wellbeing.running.Phase
import dev.wellbeing.running.VibrationPatterns
import dev.wellbeing.running.WatchProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The watch's end of the link: it finds the phone, tells it the watch app is open, receives the
 * state to display, sends commands and delivers heart rate. Nothing is stored: the watch is a
 * remote for the run recorded on the phone. Everything runs on the main thread.
 */
object WatchSession : MessageClient.OnMessageReceivedListener {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private var listening = false
    private var phone: String? = null
    private var nextCommand = 1
    private val waiting = HashMap<Int, CompletableDeferred<WatchProtocol.Ack>>()
    private val buffer = HrBuffer()

    private val _state = MutableStateFlow<WatchProtocol.State?>(null)
    /** Last state received from the phone; null until the first one. */
    val state: StateFlow<WatchProtocol.State?> = _state
    private val _stateAt = MutableStateFlow(0L)
    /** When that state was received, to notice a lost link. */
    val stateAt: StateFlow<Long> = _stateAt
    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate
    private val _measuring = MutableStateFlow(false)
    /** True while the heart-rate service is running. */
    val measuring: StateFlow<Boolean> = _measuring
    private val _feedback = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /** Outcome of each command: true when the phone applied it. */
    val feedback: SharedFlow<Boolean> = _feedback

    /** A start asked from the wrist: waiting for the phone, or refused with its reason. */
    sealed interface Starting {
        data object Waiting : Starting
        data class Refused(val reason: WatchProtocol.Refusal) : Starting
    }

    private val _starting = MutableStateFlow<Starting?>(null)
    val starting: StateFlow<Starting?> = _starting

    /** How long the watch waits for the phone to start a run asked from the wrist. */
    const val START_TIMEOUT_MS = 10_000L

    /**
     * Asks the phone to start a free run. The run shows as started only once the phone confirms;
     * a refusal or no answer within 10 s says so.
     */
    fun startNew() {
        if (_starting.value == Starting.Waiting) return
        _starting.value = Starting.Waiting
        main.launch {
            val ack = request(WatchProtocol.CommandType.START_NEW, START_TIMEOUT_MS)
            _starting.value = if (ack?.applied == true) null else Starting.Refused(ack?.reason ?: WatchProtocol.Refusal.OTHER)
            if (ack?.applied == true) hello(interactive = true)
        }
    }

    fun dismissStart() {
        _starting.value = null
    }

    /** Opens the phone app on the companion phone; false when it could not be reached. */
    suspend fun openOnPhone(): Boolean = runCatching {
        val f = RemoteActivityHelper(app).startRemoteActivity(
            Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse(OPEN_URI)),
        )
        suspendCancellableCoroutine<Unit> { cont ->
            f.addListener({
                try { f.get(); cont.resume(Unit) } catch (e: Exception) { cont.resumeWithException(e) }
            }, Runnable::run)
        }
    }.isSuccess

    private const val OPEN_URI = "wellbeing://run"

    val sessionActive: Boolean
        get() = _state.value?.phase.let { it == Phase.READY || it == Phase.RUNNING || it == Phase.PAUSED || it == Phase.AUTO_PAUSED }

    /** When the run was stopped, while it waits on the phone's summary; null otherwise. */
    private var stoppedAt: Long? = null

    /** Heart rate is still wanted: a session, or a stopped run not saved yet, for up to five minutes. */
    fun keepMeasuring(now: Long = System.currentTimeMillis()) = WatchProtocol.measuring(_state.value, now - (stoppedAt ?: now))

    /** Minutes the watch still measures for a stopped run, for the stopped screen. */
    fun stoppedMinutesLeft(now: Long = System.currentTimeMillis()): Int? =
        stoppedAt?.let { ((WatchProtocol.STOPPED_KEEP_MS - (now - it)) / 60_000 + 1).toInt().coerceIn(0, 5) }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
    }

    private fun listen() {
        if (listening) return
        listening = true
        Wearable.getMessageClient(app).addListener(this)
    }

    private suspend fun findPhone(): String? = phone ?: runCatching {
        Wearable.getCapabilityClient(app).getCapability(WatchProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes.firstOrNull()?.id
            ?: Wearable.getNodeClient(app).connectedNodes.await().firstOrNull()?.id
    }.getOrNull()?.also { phone = it }

    private suspend fun send(path: String, payload: ByteArray): Boolean {
        val target = findPhone() ?: return false
        return runCatching { Wearable.getMessageClient(app).sendMessage(target, path, payload).await() }.isSuccess
    }

    /** Tells the phone the watch app is there; the phone answers with the current state. */
    fun hello(interactive: Boolean) {
        listen()
        main.launch { send(WatchProtocol.PATH_HELLO, WatchProtocol.Hello(interactive).encode()) }
    }

    /** Sends a command and reports whether the phone acknowledged it in time. */
    fun command(type: WatchProtocol.CommandType) {
        main.launch { _feedback.tryEmit(request(type, WatchProtocol.ACK_TIMEOUT_MS)?.applied == true) }
    }

    /** Sends a command and waits for its acknowledgement; null when none came in time. */
    private suspend fun request(type: WatchProtocol.CommandType, timeoutMs: Long): WatchProtocol.Ack? {
        val id = nextCommand++
        val ack = CompletableDeferred<WatchProtocol.Ack>()
        waiting[id] = ack
        val sent = send(WatchProtocol.PATH_COMMAND, WatchProtocol.Command(id, type).encode())
        val result = if (sent) withTimeoutOrNull(timeoutMs) { ack.await() } else null
        waiting.remove(id)
        return result
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        phone = event.sourceNodeId
        main.launch {
            when (path) {
                WatchProtocol.PATH_STATE -> WatchProtocol.State.decode(data)?.let {
                    val now = System.currentTimeMillis()
                    val pending = it.phase == Phase.STOPPED && it.coaching?.ended == false
                    stoppedAt = if (pending) stoppedAt ?: now else null
                    _state.value = it
                    _stateAt.value = now
                }
                // Received by the heart-rate service's process, so it vibrates with the screen off.
                WatchProtocol.PATH_CUE -> WatchProtocol.decodeCue(data)?.let { kind ->
                    app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(VibrationPatterns.of(kind), -1))
                }
                WatchProtocol.PATH_ACK -> WatchProtocol.Ack.decode(data)?.let { waiting[it.id]?.complete(it) }
            }
        }
    }

    // --- heart rate, fed by HrService ---

    fun setMeasuring(on: Boolean) {
        _measuring.value = on
        if (!on) { _heartRate.value = null; buffer.clear() }
    }

    fun onHeartRate(sample: HrSample) {
        buffer.add(sample)
        _heartRate.value = sample.bpm
    }

    /** Delivers what has been measured; samples that cannot be sent wait for the link to return. */
    suspend fun flushHeartRate() {
        while (buffer.size > 0) {
            val batch = buffer.take()
            if (!send(WatchProtocol.PATH_HR, WatchProtocol.encodeHr(batch))) {
                buffer.restore(batch)
                // The phone may have changed node after a reconnection: look it up again next time.
                phone = null
                return
            }
        }
    }

    fun bye() {
        main.launch { send(WatchProtocol.PATH_BYE, ByteArray(0)) }
    }
}
