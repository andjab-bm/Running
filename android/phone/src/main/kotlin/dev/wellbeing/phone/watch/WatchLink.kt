package dev.wellbeing.phone.watch

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SettingsStore
import dev.wellbeing.phone.record.Command
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Signal
import dev.wellbeing.running.Units
import dev.wellbeing.running.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The phone's end of the link with the watch. The watch joins by saying hello; from then on the
 * phone pushes the live state to it, stores the heart rate it sends and applies its commands.
 * Everything here runs on the main thread, like the rest of the run.
 */
object WatchLink {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private var settings = Settings()

    /** Node id of the watch that joined, if any. */
    private var node: String? = null
    private var interactive = true
    private var lastSent = 0L
    private var lastState: WatchProtocol.State? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        main.launch { SettingsStore(app).flow.collect { settings = it } }
        // Every refresh of the live figures (about once per second) is a chance to update the watch.
        main.launch {
            combine(RunController.snapshot, RunController.finished) { s, f -> s to f }.collect { push(force = false) }
        }
    }

    private fun currentState(): WatchProtocol.State {
        val s = RunController.snapshot.value
        val f = RunController.finished.value
        val bounds = settings.zones.bounds.map { it.toInt() }
        val imperial = settings.units == Units.IMPERIAL
        return when {
            // A stopped run waiting to be saved: the watch shows it as finished.
            f != null -> WatchProtocol.State(Phase.STOPPED, f.summary.movingMs, f.summary.distance, null, imperial, bounds)
            s != null -> WatchProtocol.State(s.phase, s.movingMs, s.distance, s.pace, imperial, bounds)
            else -> WatchProtocol.State(null, 0, 0.0, null, imperial, bounds)
        }
    }

    /**
     * Sends the state to the watch: at most once per second while its screen is on, once per
     * minute while it is dimmed, and at once when the phase changes (pause, stop, end).
     */
    private fun push(force: Boolean) {
        val target = node ?: return
        val state = currentState()
        val now = System.currentTimeMillis()
        val phaseChanged = state.phase != lastState?.phase
        val period = if (interactive) 900L else WatchProtocol.AMBIENT_STATE_PERIOD_MS
        if (!force && !phaseChanged && now - lastSent < period) return
        lastSent = now
        lastState = state
        send(target, WatchProtocol.PATH_STATE, state.encode())
    }

    private fun send(target: String, path: String, payload: ByteArray) {
        Wearable.getMessageClient(app).sendMessage(target, path, payload)
    }

    /** Called for every message from the watch. */
    fun onMessage(source: String, path: String, data: ByteArray) {
        // Any message names the watch: the phone may have been restarted since the hello.
        if (path != WatchProtocol.PATH_BYE) node = source
        when (path) {
            WatchProtocol.PATH_HELLO -> {
                node = source
                interactive = WatchProtocol.Hello.decode(data)?.interactive ?: true
                push(force = true)
            }
            WatchProtocol.PATH_BYE -> if (node == source) node = null
            WatchProtocol.PATH_HR -> {
                // Samples carry the time of their measurement: a batch that arrives late, after
                // a dropout, still lands at the right place in the run.
                WatchProtocol.decodeHr(data)?.forEach { RunController.onHeartRate(it.t, it.bpm) }
            }
            WatchProtocol.PATH_COMMAND -> {
                val c = WatchProtocol.Command.decode(data) ?: return
                node = source
                val signal = RunController.command(
                    when (c.type) {
                        WatchProtocol.CommandType.START -> Command.START
                        WatchProtocol.CommandType.PAUSE -> Command.PAUSE
                        WatchProtocol.CommandType.RESUME -> Command.RESUME
                        WatchProtocol.CommandType.LAP -> Command.LAP
                        WatchProtocol.CommandType.STOP -> Command.STOP
                    },
                )
                send(source, WatchProtocol.PATH_ACK, WatchProtocol.Ack(c.id, applied = signal != Signal.NONE).encode())
                push(force = true)
            }
        }
    }
}

/** Woken by the system when the watch sends a message, even if the app is not open. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val source = event.sourceNodeId
        val path = event.path
        val data = event.data
        // Messages arrive on a background thread; the run lives on the main thread.
        mainExecutor.execute {
            WatchLink.init(applicationContext)
            WatchLink.onMessage(source, path, data)
        }
    }
}
