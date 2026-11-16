package dev.wellbeing.phone.record

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.EventEntity
import dev.wellbeing.phone.data.HrEntity
import dev.wellbeing.phone.data.LapEntity
import dev.wellbeing.phone.data.RunDao
import dev.wellbeing.phone.data.RunEntity
import dev.wellbeing.phone.data.RunState
import dev.wellbeing.phone.data.SampleEntity
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SettingsStore
import dev.wellbeing.phone.data.SyncState
import dev.wellbeing.phone.data.TimerEntity
import dev.wellbeing.running.Event
import dev.wellbeing.running.EventCodec
import dev.wellbeing.running.EventRow
import dev.wellbeing.running.FitEncoder
import dev.wellbeing.running.HrSample
import dev.wellbeing.running.Phase
import dev.wellbeing.running.RecordedRun
import dev.wellbeing.running.RunSummary
import dev.wellbeing.running.Sample
import dev.wellbeing.running.SessionEngine
import dev.wellbeing.running.Signal
import dev.wellbeing.running.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Command { START, PAUSE, RESUME, LAP, STOP }

/** A stopped run waiting to be saved or discarded. */
data class Finished(val runId: Long, val run: RecordedRun, val summary: RunSummary)

/** A run found unfinished when the app started. */
data class Interrupted(val runId: Long, val snapshot: Snapshot)

/**
 * The one run that can exist at a time. The interface, the foreground service and (later) the
 * watch all go through it. The engine is only touched on the main thread; storage runs on a
 * single background thread so writes keep their order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RunController {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private lateinit var app: Context
    private lateinit var dao: RunDao
    private var settings = Settings()

    private var engine: SessionEngine? = null
    @Volatile private var runId: Long? = null
    private var config = SessionEngine.Config()
    private val pendingEvents = ArrayList<EventRow>()
    private val pendingHr = ArrayList<HrSample>()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    /** Live figures; null when no session is prepared or running. */
    val snapshot: StateFlow<Snapshot?> = _snapshot
    private val _track = MutableStateFlow<List<Sample>>(emptyList())
    /** Points recorded so far, refreshed every few seconds, for the mini-map area. */
    val track: StateFlow<List<Sample>> = _track
    private val _signals = MutableSharedFlow<Signal>(extraBufferCapacity = 8)
    val signals: SharedFlow<Signal> = _signals
    private val _finished = MutableStateFlow<Finished?>(null)
    val finished: StateFlow<Finished?> = _finished
    private val _interrupted = MutableStateFlow<Interrupted?>(null)
    val interrupted: StateFlow<Interrupted?> = _interrupted

    val phase: Phase? get() = engine?.phase
    val isActive: Boolean get() = engine?.phase.let { it == Phase.RUNNING || it == Phase.PAUSED || it == Phase.AUTO_PAUSED }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        dao = AppDb.get(app).runs()
        main.launch { SettingsStore(app).flow.collect { settings = it } }
        io.launch {
            findInterrupted()
            // Runs saved earlier and still waiting get another chance at each start of the app.
            if (dao.toSync().isNotEmpty()) dev.wellbeing.phone.sync.Sync.schedule(app)
        }
    }

    private fun now() = System.currentTimeMillis()

    /** Opens a session on the ready screen: the service starts and looks for the position. */
    fun prepare(context: Context) {
        if (engine == null) {
            config = SessionEngine.Config(autoPause = settings.autoPause, splitMeters = settings.splitMeters)
            engine = SessionEngine(config)
            runId = null
            _track.value = emptyList()
        }
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        tick()
    }

    /** Closes a session that was prepared but never started. */
    fun leaveReady() {
        if (engine?.phase != Phase.READY) return
        engine = null
        _snapshot.value = null
    }

    fun command(c: Command): Signal {
        val t = now()
        return dispatch(
            when (c) {
                Command.START -> Event.Start(t)
                Command.PAUSE -> Event.Pause(t)
                Command.RESUME -> Event.Resume(t)
                Command.LAP -> Event.Lap(t)
                Command.STOP -> Event.Stop(t)
            },
        )
    }

    /** Feeds a sensor reading or a command to the engine and logs it if a run is in progress. */
    fun dispatch(e: Event): Signal {
        val eng = engine ?: return Signal.NONE
        val before = eng.phase
        if (before == Phase.STOPPED) return Signal.NONE
        val signal = eng.on(e)
        if (before != Phase.READY || e is Event.Start) EventCodec.encode(e)?.let(pendingEvents::add)
        if (signal != Signal.NONE) {
            _signals.tryEmit(signal)
            flush()
            if (signal == Signal.STOPPED) finish()
        }
        if (signal != Signal.NONE || e !is Event.Location) tick()
        return signal
    }

    fun onHeartRate(t: Long, bpm: Int) {
        val eng = engine ?: return
        eng.on(Event.HeartRate(t, bpm))
        if (eng.phase != Phase.READY && eng.phase != Phase.STOPPED) pendingHr += HrSample(t, bpm)
        tick()
    }

    /** Refreshes the live figures; called by the service once per second. */
    fun tick() {
        _snapshot.value = engine?.snapshot(now())
    }

    /** Writes what has been logged since the last call; called every few seconds and on commands. */
    fun flush() {
        val eng = engine ?: return
        if (eng.phase == Phase.READY) return
        val events = ArrayList(pendingEvents).also { pendingEvents.clear() }
        val hr = ArrayList(pendingHr).also { pendingHr.clear() }
        _track.value = eng.recorded().samples
        val cfg = config
        val startT = eng.recorded().startT
        io.launch {
            val id = runId ?: dao.insertRun(RunEntity(startT = startT, autoPause = cfg.autoPause, splitMeters = cfg.splitMeters)).also { runId = it }
            if (events.isNotEmpty()) dao.insertEvents(events.map { EventEntity(runId = id, type = it.type, t = it.t, a = it.a, b = it.b, c = it.c, d = it.d) })
            if (hr.isNotEmpty()) dao.insertHr(hr.map { HrEntity(id, it.t, it.bpm) })
        }
    }

    private fun finish() {
        val run = engine?.recorded() ?: return
        io.launch {
            val id = runId ?: return@launch
            val hr = dao.hr(id).map { HrSample(it.t, it.bpm) }
            _finished.value = Finished(id, run, RunSummary.of(run, hr))
        }
    }

    /** Stores the stopped run with its notes and writes its FIT file. */
    suspend fun save(title: String, rpe: Int?, note: String) = withContext(io.coroutineContext) {
        val f = _finished.value ?: return@withContext
        val hr = dao.hr(f.runId).map { HrSample(it.t, it.bpm) }
        val path = "runs/${f.runId}.fit"
        File(app.filesDir, path).apply { parentFile?.mkdirs() }.writeBytes(FitEncoder.encode(f.run, hr))
        val s = f.summary
        dao.saveFinished(
            RunEntity(
                id = f.runId, state = RunState.SAVED, startT = f.run.startT, endT = f.run.endT,
                distance = s.distance, movingMs = s.movingMs, gain = s.gain, avgHr = s.avgHeartRate, avgCadence = s.avgCadence,
                title = title.trim(), rpe = rpe, note = note.trim(),
                autoPause = config.autoPause, splitMeters = config.splitMeters,
                sync = SyncState.PENDING, fitPath = path,
            ),
            f.run.samples.map { SampleEntity(f.runId, it.t, it.lat, it.lon, it.ele, it.dist, it.cadence, it.speed) },
            f.run.laps.mapIndexed { i, l -> LapEntity(f.runId, i, l.startT, l.endT, l.distance, l.movingMs, l.manual, s.lapHeartRates[i]) },
            f.run.timer.map { TimerEntity(f.runId, it.t, it.start) },
        )
        dev.wellbeing.phone.sync.Sync.schedule(app)
        withContext(Dispatchers.Main) { clear() }
    }

    /** Deletes the stopped run: it never reaches the history or the server. */
    suspend fun discard() = withContext(io.coroutineContext) {
        _finished.value?.let { dao.deleteRun(it.runId) }
        withContext(Dispatchers.Main) { clear() }
    }

    private fun clear() {
        engine = null
        runId = null
        pendingEvents.clear(); pendingHr.clear()
        _finished.value = null
        _interrupted.value = null
        _snapshot.value = null
        _track.value = emptyList()
    }

    /** Rebuilds a run left unfinished by a crash or a kill, from its event log. */
    private suspend fun findInterrupted() {
        val run = dao.interrupted() ?: return
        val events = dao.events(run.id).mapNotNull { EventCodec.decode(EventRow(it.type, it.t, it.a, it.b, it.c, it.d)) }
        if (events.isEmpty()) { dao.deleteRun(run.id); return }
        val cfg = SessionEngine.Config(run.autoPause, run.splitMeters)
        val rebuilt = SessionEngine.replay(cfg, events)
        val lastT = events.last().t
        withContext(Dispatchers.Main) {
            if (engine != null) return@withContext
            engine = rebuilt; config = cfg; runId = run.id
            when (rebuilt.phase) {
                Phase.STOPPED -> finish()
                Phase.READY -> { clear(); io.launch { dao.deleteRun(run.id) } }
                else -> {
                    // The time the app was dead must not count as running.
                    if (rebuilt.phase != Phase.PAUSED) dispatch(Event.Pause(lastT))
                    _snapshot.value = null
                    _interrupted.value = Interrupted(run.id, rebuilt.snapshot(lastT))
                }
            }
        }
    }

    /** Continues an interrupted run from now. */
    fun resumeInterrupted(context: Context) {
        if (_interrupted.value == null) return
        _interrupted.value = null
        prepare(context)
        command(Command.RESUME)
    }

    /** Ends an interrupted run where it stopped, keeping what was recorded. */
    fun endInterrupted() {
        val i = _interrupted.value ?: return
        _interrupted.value = null
        dispatch(Event.Stop(i.snapshot.elapsedMs + (engine?.recorded()?.startT ?: 0)))
    }
}
