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
import dev.wellbeing.phone.data.coach
import dev.wellbeing.phone.data.path
import dev.wellbeing.phone.sync.InUse
import dev.wellbeing.running.Coach
import dev.wellbeing.running.Cue
import dev.wellbeing.running.Event
import dev.wellbeing.running.EventCodec
import dev.wellbeing.running.EventRow
import dev.wellbeing.running.FitEncoder
import dev.wellbeing.running.HrSample
import dev.wellbeing.running.Pacer
import dev.wellbeing.running.PacerTarget
import dev.wellbeing.running.Phase
import dev.wellbeing.running.PlannedStep
import dev.wellbeing.running.RecordedRun
import dev.wellbeing.running.RouteTracker
import dev.wellbeing.running.RoutePath
import dev.wellbeing.running.RunSummary
import dev.wellbeing.running.Sample
import dev.wellbeing.running.SessionEngine
import dev.wellbeing.running.Signal
import dev.wellbeing.running.Snapshot
import dev.wellbeing.running.Workout
import dev.wellbeing.running.WorkoutRunner
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

enum class Command { START, PAUSE, RESUME, LAP, STOP }

/** What a run is prepared with on the ready screen. A session and a pace target exclude each other. */
data class Plan(val workout: Workout? = null, val route: RoutePath? = null, val pacer: PacerTarget? = null) {
    val isEmpty get() = workout == null && route == null && pacer == null
}

/** The coaching state shown by the live screens. */
data class Coaching(
    val steps: List<PlannedStep>,
    val step: WorkoutRunner.Progress?,
    val complete: Boolean,
    val route: RouteTracker.State?,
    val routeName: String?,
    /** The route's points, for the maps. */
    val path: dev.wellbeing.running.RoutePath? = null,
    val pacerTarget: PacerTarget?,
    val pacer: Pacer.State?,
)

/** A stopped run waiting to be saved, discarded or resumed. */
data class Finished(
    val runId: Long,
    val run: RecordedRun,
    val summary: RunSummary,
    /** The session's steps; lap n of the run is step n. */
    val steps: List<PlannedStep>? = null,
    val workoutName: String? = null,
    val pacerTarget: PacerTarget? = null,
    val pacer: Pacer.State? = null,
    val routeName: String? = null,
)

/** A run found unfinished when the app started. */
data class Interrupted(val runId: Long, val snapshot: Snapshot)

/**
 * The one run that can exist at a time. The interface, the foreground service and the watch all go
 * through it. The run and its coaching are driven by a [Coach] only touched on the main thread;
 * storage runs on a single background thread so writes keep their order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RunController {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private lateinit var app: Context
    private lateinit var dao: RunDao
    /** The phone's settings as last read; the cue player reads its channels from here. */
    var settings = Settings()
        private set

    private var coach: Coach? = null
    private val engine: SessionEngine? get() = coach?.engine
    @Volatile private var runId: Long? = null
    private var config = SessionEngine.Config()
    private var plan = Plan()
    /** Last position on the ready screen, handed to a coach rebuilt when the plan changes. */
    private var lastFix: Event.Location? = null
    private val pendingEvents = ArrayList<EventRow>()
    private val pendingHr = ArrayList<HrSample>()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    /** Live figures; null when no session is prepared or running. */
    val snapshot: StateFlow<Snapshot?> = _snapshot
    private val _coaching = MutableStateFlow<Coaching?>(null)
    /** Step, route and pacer state; null for a free run. */
    val coaching: StateFlow<Coaching?> = _coaching
    private val _plan = MutableStateFlow(Plan())
    val prepared: StateFlow<Plan> = _plan
    private val _position = MutableStateFlow<Event.Location?>(null)
    /** Last position received, for the distance to a route's start on the ready screen. */
    val position: StateFlow<Event.Location?> = _position
    private val _track = MutableStateFlow<List<Sample>>(emptyList())
    /** Points recorded so far, refreshed every few seconds, for the mini-map area. */
    val track: StateFlow<List<Sample>> = _track
    private val _signals = MutableSharedFlow<Signal>(extraBufferCapacity = 8)
    val signals: SharedFlow<Signal> = _signals
    private val _cues = MutableSharedFlow<Cue>(extraBufferCapacity = 16)
    /** Coaching cues as the engines raise them; rendered by the cue player. */
    val cues: SharedFlow<Cue> = _cues
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
        main.launch {
            SettingsStore(app).flow.collect {
                settings = it
                // Alerts, announcement and auto-pause changed before the start apply to this run.
                rearm()
            }
        }
        io.launch {
            findInterrupted()
            // Pending runs get another chance, and sessions marked on the web come in, while the phone is home.
            dev.wellbeing.phone.sync.Sync.schedule(app)
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun coachSettings(cfg: SessionEngine.Config) = settings.coach(cfg)

    /** Opens a session on the ready screen: the service starts and looks for the position. */
    fun prepare(context: Context) {
        if (coach == null) {
            config = SessionEngine.Config(autoPause = settings.autoPause, splitMeters = settings.splitMeters)
            coach = Coach.of(planEvent(0), plan.route, coachSettings(config))
            runId = null
            _track.value = emptyList()
        }
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        tick()
    }

    private fun planEvent(t: Long) = Event.Plan(t, plan.workout, plan.route?.let { dev.wellbeing.running.RouteRef(it.id, it.version, it.name) }, plan.pacer)

    /** Attaches a session, a route and a pace target to the run about to start. */
    fun choose(p: Plan) {
        plan = if (p.workout != null) p.copy(pacer = null) else p
        _plan.value = plan
        rearm()
    }

    /** Rebuilds the coach of a run not started yet, with the current plan and settings. */
    private fun rearm() {
        val c = coach ?: return
        if (c.engine.phase != Phase.READY) return
        config = SessionEngine.Config(autoPause = settings.autoPause, splitMeters = settings.splitMeters)
        coach = Coach.of(planEvent(0), plan.route, coachSettings(config)).also { n -> lastFix?.let { n.on(it) } }
        tick()
    }

    /** What the run being prepared or recorded uses: the sync leaves it in place. */
    fun inUse() = if (coach == null && _finished.value == null) InUse() else InUse(plan.workout?.id, plan.route?.id)

    /** Closes a session that was prepared but never started. */
    fun leaveReady() {
        if (engine?.phase != Phase.READY) return
        coach = null
        _snapshot.value = null
        _coaching.value = null
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

    /** Feeds a sensor reading or a command to the run and logs it if a run is in progress. */
    fun dispatch(e: Event): Signal {
        val c = coach ?: return Signal.NONE
        val before = c.engine.phase
        // Once stopped, only a resume from the summary counts.
        if (before == Phase.STOPPED && e !is Event.Resume) return Signal.NONE
        if (e is Event.Location) {
            _position.value = e
            if (before == Phase.READY) lastFix = e
        }
        if (before == Phase.READY && e is Event.Start) {
            // The plan comes first in the log, so a replay rebuilds the coaching.
            if (!plan.isEmpty) EventCodec.encode(c.planEvent(e.t))?.let(pendingEvents::add)
            remember(plan)
        }
        val out = c.on(e)
        if (before != Phase.READY || e is Event.Start) EventCodec.encode(e)?.let(pendingEvents::add)
        out.issued.forEach { EventCodec.encode(it)?.let(pendingEvents::add) }
        out.cues.forEach { _cues.tryEmit(it) }
        val signal = out.signal
        if (signal != Signal.NONE || out.issued.isNotEmpty()) {
            if (signal != Signal.NONE) _signals.tryEmit(signal)
            if (out.issued.any { it is Event.Lap }) _signals.tryEmit(Signal.LAP)
            flush()
            if (signal == Signal.STOPPED) finish()
            if (signal == Signal.RESUMED && before == Phase.STOPPED) {
                _finished.value = null
                ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
            }
        }
        if (signal != Signal.NONE || e !is Event.Location) tick() else refreshCoaching()
        return signal
    }

    /** The last pace target is proposed again next time. */
    private fun remember(p: Plan) {
        val target = p.pacer ?: return
        main.launch {
            SettingsStore(app).update {
                when (target) {
                    is PacerTarget.Pace -> it.copy(pacerPace = target.secondsPerKm)
                    is PacerTarget.Time -> it.copy(pacerTime = target.seconds)
                }
            }
        }
    }

    fun onHeartRate(t: Long, bpm: Int) {
        val c = coach ?: return
        c.on(Event.HeartRate(t, bpm))
        if (c.engine.phase != Phase.READY && c.engine.phase != Phase.STOPPED) pendingHr += HrSample(t, bpm)
        tick()
    }

    /**
     * Refreshes the live figures and lets the clock end a timed step; called by the service once
     * per second.
     */
    fun tick() {
        val c = coach
        if (c != null && isActive) {
            val out = c.tick(now())
            out.cues.forEach { _cues.tryEmit(it) }
            if (out.issued.isNotEmpty()) {
                out.issued.forEach { EventCodec.encode(it)?.let(pendingEvents::add) }
                _signals.tryEmit(Signal.LAP)
                flush()
            }
        }
        _snapshot.value = c?.snapshot(now())
        refreshCoaching()
    }

    private fun refreshCoaching() {
        val c = coach
        if (c == null || (c.runner == null && c.tracker == null && c.pacer == null)) {
            _coaching.value = null
            return
        }
        val s = c.snapshot(now())
        _coaching.value = Coaching(
            steps = c.runner?.steps.orEmpty(),
            step = c.runner?.progress(s),
            complete = c.runner?.complete ?: false,
            route = c.tracker?.state,
            routeName = c.route?.name,
            path = c.route,
            pacerTarget = c.pacerTarget,
            pacer = c.pacer?.state,
        )
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
            if (events.isNotEmpty()) dao.insertEvents(events.map { EventEntity(runId = id, type = it.type, t = it.t, a = it.a, b = it.b, c = it.c, d = it.d, text = it.text) })
            if (hr.isNotEmpty()) dao.insertHr(hr.map { HrEntity(id, it.t, it.bpm) })
        }
    }

    private fun finish() {
        val c = coach ?: return
        val run = c.engine.recorded()
        val coaching = _coaching.value
        io.launch {
            val id = runId ?: return@launch
            val hr = dao.hr(id).map { HrSample(it.t, it.bpm) }
            _finished.value = Finished(
                id, run, RunSummary.of(run, hr),
                steps = c.runner?.steps, workoutName = c.workout?.name,
                pacerTarget = c.pacerTarget, pacer = c.pacer?.state ?: coaching?.pacer, routeName = c.route?.name,
            )
        }
    }

    /** The session, route and pace target the run was done with, as the server's journal patch takes them. */
    private fun context(c: Coach): String? {
        if (c.workout == null && c.route == null && c.pacerTarget == null) return null
        return buildJsonObject {
            c.workout?.let { w ->
                put("workout", buildJsonObject {
                    w.id?.let { put("id", it) }
                    put("name", w.name)
                    put("steps", w.stepsJson())
                })
            }
            c.route?.let { r -> put("route", buildJsonObject { put("id", r.id); put("name", r.name) }) }
            c.pacerTarget?.let { t ->
                put("pacer", buildJsonObject {
                    when (t) {
                        is PacerTarget.Pace -> { put("target", "pace"); put("value", t.secondsPerKm); put("strategy", "even") }
                        is PacerTarget.Time -> {
                            put("target", "time"); put("value", t.seconds)
                            put("strategy", if (t.gradeAdjusted && c.route?.ele != null) "grade" else "even")
                        }
                    }
                    put("final_gap", c.pacer?.state?.gapS ?: 0.0)
                })
            }
        }.toString()
    }

    /** Stores the stopped run with its notes and what it was done with, and writes its FIT file. */
    suspend fun save(title: String, rpe: Int?, note: String) = withContext(io.coroutineContext) {
        val f = _finished.value ?: return@withContext
        val ctx = withContext(Dispatchers.Main) { coach?.let(::context) }
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
                sync = SyncState.PENDING, fitPath = path, context = ctx,
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

    /** Goes back to the run from its summary: the time on the summary is a pause. */
    fun resumeStopped(): Signal = if (phase == Phase.STOPPED) dispatch(Event.Resume(now())) else Signal.NONE

    private fun clear() {
        coach = null
        runId = null
        lastFix = null
        plan = Plan()
        _plan.value = plan
        pendingEvents.clear(); pendingHr.clear()
        _finished.value = null
        _interrupted.value = null
        _snapshot.value = null
        _coaching.value = null
        _track.value = emptyList()
    }

    /** Rebuilds a run left unfinished by a crash or a kill, from its event log. */
    private suspend fun findInterrupted() {
        val run = dao.interrupted() ?: return
        val events = dao.events(run.id).mapNotNull { EventCodec.decode(EventRow(it.type, it.t, it.a, it.b, it.c, it.d, it.text)) }
        if (events.isEmpty()) { dao.deleteRun(run.id); return }
        val cfg = SessionEngine.Config(run.autoPause, run.splitMeters)
        val planDao = AppDb.get(app).plan()
        val routes = (events.firstOrNull() as? Event.Plan)?.route?.let { ref -> planDao.route(ref.id)?.path() }
        val rebuilt = withContext(Dispatchers.Main) { Coach.fromLog(events, coachSettings(cfg)) { routes } }
        val lastT = events.last().t
        withContext(Dispatchers.Main) {
            if (coach != null) return@withContext
            coach = rebuilt; config = cfg; runId = run.id
            plan = Plan(rebuilt.workout, rebuilt.route, rebuilt.pacerTarget)
            _plan.value = plan
            when (rebuilt.engine.phase) {
                // Stopped and never saved: the summary comes back, with resume.
                Phase.STOPPED -> { refreshCoaching(); finish() }
                Phase.READY -> { clear(); io.launch { dao.deleteRun(run.id) } }
                else -> {
                    // The time the app was dead must not count as running.
                    if (rebuilt.engine.phase != Phase.PAUSED) dispatch(Event.Pause(lastT))
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
