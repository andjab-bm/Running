package dev.wellbeing.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import dev.wellbeing.running.HrBuffer
import dev.wellbeing.running.HrSample
import dev.wellbeing.running.Phase
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
    private val waiting = HashMap<Int, CompletableDeferred<Boolean>>()
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

    val sessionActive: Boolean
        get() = _state.value?.phase.let { it == Phase.READY || it == Phase.RUNNING || it == Phase.PAUSED || it == Phase.AUTO_PAUSED }

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
        main.launch {
            val id = nextCommand++
            val ack = CompletableDeferred<Boolean>()
            waiting[id] = ack
            val sent = send(WatchProtocol.PATH_COMMAND, WatchProtocol.Command(id, type).encode())
            val applied = sent && withTimeoutOrNull(WatchProtocol.ACK_TIMEOUT_MS) { ack.await() } == true
            waiting.remove(id)
            _feedback.tryEmit(applied)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        phone = event.sourceNodeId
        main.launch {
            when (path) {
                WatchProtocol.PATH_STATE -> WatchProtocol.State.decode(data)?.let {
                    _state.value = it
                    _stateAt.value = System.currentTimeMillis()
                }
                WatchProtocol.PATH_ACK -> WatchProtocol.Ack.decode(data)?.let { waiting[it.id]?.complete(it.applied) }
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
