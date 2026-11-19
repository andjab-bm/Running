package dev.wellbeing.phone.watch

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SettingsStore
import dev.wellbeing.phone.record.Command
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.running.CueKind
import dev.wellbeing.running.Phase
import dev.wellbeing.running.Signal
import dev.wellbeing.running.Units
import dev.wellbeing.running.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.common.util.concurrent.ListenableFuture
import dev.wellbeing.phone.record.WatchStart
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The cues worth a vibration on the wrist: steps and alerts, not the distance announcements. */
private val WATCH_CUES = setOf(
    CueKind.STEP, CueKind.COUNTDOWN, CueKind.COMPLETE, CueKind.TARGET, CueKind.PACER,
    CueKind.OFF_ROUTE, CueKind.BACK_ON_ROUTE, CueKind.ARRIVAL,
)

/** Whether a cue goes to the watch; while paused, as on the phone, only a step change or the end. */
fun cueForWatch(kind: CueKind, paused: Boolean, enabled: Boolean) =
    enabled && kind in WATCH_CUES && (!paused || kind == CueKind.STEP || kind == CueKind.COMPLETE)

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
    /** The last run shown, and when it was saved or discarded: the watch shows it as ended for a while. */
    private var lastRun: Pair<Long, Double>? = null
    private var endedAt: Long? = null

    private val _connected = MutableStateFlow(false)
    /**
     * True while the watch app is joined: it has been heard from recently and has not said bye.
     * This is the link itself, not the heart rate, which can take a while to arrive or be refused.
     */
    val connected: StateFlow<Boolean> = _connected
    private var lastHeard = 0L
    private var watchdog: Job? = null

    /** Notes that the watch just spoke, and watches for it going silent. */
    private fun heard() {
        lastHeard = System.currentTimeMillis()
        _connected.value = true
        if (watchdog?.isActive == true) return
        watchdog = main.launch {
            while (_connected.value) {
                delay(1_000)
                // An open watch app says hello every three seconds; a dimmed one only speaks
                // when it has heart rate to deliver.
                val limit = if (interactive) SILENT_MS else SILENT_DIMMED_MS
                if (System.currentTimeMillis() - lastHeard > limit) _connected.value = false
            }
        }
    }

    private const val SILENT_MS = 10_000L
    private const val SILENT_DIMMED_MS = 120_000L

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        main.launch { SettingsStore(app).flow.collect { settings = it } }
        // Every refresh of the live figures (about once per second) is a chance to update the watch.
        main.launch {
            combine(RunController.snapshot, RunController.finished) { s, f -> s to f }.collect { push(force = false) }
        }
        // Coaching cues vibrate on the wrist too, through a message the watch receives screen off.
        main.launch {
            RunController.cues.collect { cue ->
                val target = node ?: return@collect
                val paused = RunController.phase.let { it == Phase.PAUSED || it == Phase.AUTO_PAUSED }
                if (cueForWatch(cue.kind, paused, settings.watchVibrations)) send(target, WatchProtocol.PATH_CUE, WatchProtocol.encodeCue(cue.kind))
            }
        }
    }

    /** How long a saved or discarded run stays on the watch as ended. */
    private const val ENDED_SHOWN_MS = 2 * 60_000L

    /** The version-2 extension: the step, the pacer, the route, and whether the run is over. */
    private fun extension(ended: Boolean): WatchProtocol.Coaching {
        val c = RunController.coaching.value
        val s = RunController.snapshot.value
        val step = c?.step?.takeIf { !c.complete }?.let { p ->
            val (remaining, unit) = when {
                p.remainingS != null -> p.remainingS!! to WatchProtocol.Remaining.SECONDS
                p.remainingM != null -> p.remainingM!! to WatchProtocol.Remaining.METERS
                else -> p.movingMs / 1000.0 to WatchProtocol.Remaining.ELAPSED_SECONDS
            }
            WatchProtocol.StepInfo(p.planned.step.kind, p.index, p.count, remaining, unit, p.planned.step.target, p.target)
        }
        return WatchProtocol.Coaching(
            canStartFromWatch = WatchStart.available(app, settings),
            ended = ended,
            pacerActive = c?.pacerTarget != null,
            routeActive = c?.path != null,
            offRoute = c?.route?.offRoute == true,
            searchingPosition = s != null && s.phase != Phase.STOPPED && !s.gpsReady,
            step = step,
            pacerGapS = c?.pacer?.gapS,
        )
    }

    private fun currentState(): WatchProtocol.State {
        val s = RunController.snapshot.value
        val f = RunController.finished.value
        val bounds = settings.zones.bounds.map { it.toInt() }
        val imperial = settings.units == Units.IMPERIAL
        val now = System.currentTimeMillis()
        return when {
            // A stopped run waiting on the summary: stopped, not saved, and it may be resumed.
            f != null -> {
                lastRun = f.summary.movingMs to f.summary.distance; endedAt = null
                WatchProtocol.State(Phase.STOPPED, f.summary.movingMs, f.summary.distance, null, imperial, bounds, extension(ended = false))
            }
            s != null -> {
                if (s.phase == Phase.READY) lastRun = null else lastRun = s.movingMs to s.distance
                endedAt = null
                WatchProtocol.State(s.phase, s.movingMs, s.distance, s.pace, imperial, bounds, extension(ended = false))
            }
            // Saved or discarded: shown as ended, so the watch stops measuring and says the run is over.
            lastRun != null && now - (endedAt ?: now.also { endedAt = it }) < ENDED_SHOWN_MS -> lastRun!!.let { (ms, d) ->
                WatchProtocol.State(Phase.STOPPED, ms, d, null, imperial, bounds, extension(ended = true))
            }
            else -> {
                lastRun = null
                WatchProtocol.State(null, 0, 0.0, null, imperial, bounds, extension(ended = false))
            }
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
        val phaseChanged = state.phase != lastState?.phase || state.coaching?.ended != lastState?.coaching?.ended ||
            state.coaching?.step?.index != lastState?.coaching?.step?.index
        val period = if (interactive) 900L else WatchProtocol.AMBIENT_STATE_PERIOD_MS
        if (!force && !phaseChanged && now - lastSent < period) return
        lastSent = now
        lastState = state
        send(target, WatchProtocol.PATH_STATE, state.encode())
    }

    /**
     * Opens the watch app on the watch, which then joins the session. False when no watch can be
     * reached or it refused: the runner is then told to open it by hand.
     */
    suspend fun openOnWatch(): Boolean {
        if (!::app.isInitialized) return false
        val target = runCatching {
            Wearable.getCapabilityClient(app).getCapability(WatchProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes.firstOrNull()?.id
        }.getOrNull() ?: return false
        return runCatching {
            RemoteActivityHelper(app).startRemoteActivity(
                Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse(OPEN_URI)), target,
            ).awaitDone()
        }.isSuccess
    }

    /** Whether a watch with the app is within reach, for the ready screen's "open on the watch". */
    suspend fun watchReachable(): Boolean = ::app.isInitialized && runCatching {
        Wearable.getCapabilityClient(app).getCapability(WatchProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()
    }.getOrDefault(false)

    /** The address both apps answer to, so each can open the other. */
    const val OPEN_URI = "wellbeing://run"

    private fun send(target: String, path: String, payload: ByteArray) {
        Wearable.getMessageClient(app).sendMessage(target, path, payload)
    }

    /** Called for every message from the watch. */
    fun onMessage(source: String, path: String, data: ByteArray) {
        // Any message names the watch: the phone may have been restarted since the hello.
        if (path != WatchProtocol.PATH_BYE) { node = source; heard() }
        when (path) {
            WatchProtocol.PATH_HELLO -> {
                node = source
                interactive = WatchProtocol.Hello.decode(data)?.interactive ?: true
                push(force = true)
            }
            WatchProtocol.PATH_BYE -> if (node == source) { node = null; _connected.value = false }
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
                        // Start from the wrist: the settings are read first, the app may just have been woken.
                        WatchProtocol.CommandType.START_NEW -> {
                            main.launch {
                                settings = SettingsStore(app).flow.first()
                                RunController.useSettings(settings)
                                val refusal = WatchStart.start(app, settings)
                                send(source, WatchProtocol.PATH_ACK, WatchProtocol.Ack(c.id, refusal == WatchProtocol.Refusal.NONE, refusal).encode())
                                push(force = true)
                            }
                            return
                        }
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

/** Waits for a future without a Guava dependency. */
internal suspend fun <T> ListenableFuture<T>.awaitDone(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: java.util.concurrent.ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(true) }
}
