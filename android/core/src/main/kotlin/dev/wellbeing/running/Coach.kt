package dev.wellbeing.running

/**
 * One run with its coaching: the [SessionEngine] that records it and the engines that follow the
 * attached session, route and pace target. The engine stays the authority on time, distance and
 * pauses; the others read its snapshots and the positions it accepted.
 *
 * Feed every event through [on], and call [tick] about once a second so a timed step ends on time
 * even without a position. Events the coach issues itself (the lap that ends a step) are returned
 * so the caller logs them with the others. The log starts with [planEvent]; [fromLog] rebuilds the
 * whole coach from it, replaying without issuing anything twice, so a run killed in the fourth
 * repeat resumes in the fourth repeat.
 */
class Coach(
    engineConfig: SessionEngine.Config = SessionEngine.Config(),
    val workout: Workout? = null,
    zoneBounds: List<Int> = emptyList(),
    runnerConfig: WorkoutRunner.Config = WorkoutRunner.Config(),
    /** The route followed, with its points. */
    val route: RoutePath? = null,
    trackerConfig: RouteTracker.Config = RouteTracker.Config(),
    val pacerTarget: PacerTarget? = null,
    pacerConfig: Pacer.Config = Pacer.Config(),
) {
    val engine = SessionEngine(engineConfig)
    val runner: WorkoutRunner? = workout?.let { WorkoutRunner(it, zoneBounds, runnerConfig) }
    val tracker: RouteTracker? = route?.let { RouteTracker(it, trackerConfig) }
    val pacer: Pacer? = pacerTarget?.let { Pacer(it, PacePlan.of(it, route), pacerConfig) }

    /** What one event or tick produced. */
    data class Output(
        val signal: Signal,
        /** Events the coach added to the run, to be logged after the one fed in. */
        val issued: List<Event>,
        val cues: List<Cue>,
    )

    /** The first entry of the run's log. */
    fun planEvent(t: Long) = Event.Plan(t, workout, route?.let { RouteRef(it.id, it.version, it.name) }, pacerTarget)

    fun on(e: Event, live: Boolean = true): Output {
        val signal = engine.on(e)
        val cues = mutableListOf<Cue>()
        val issued = mutableListOf<Event>()
        react(signal, e.t, cues)
        if (e is Event.Location && engine.lastFixAccepted) tracker?.let { cues += it.onFix(e.t, e.lat, e.lon) }
        if (live) evaluate(e.t, cues, issued) else runner?.update(engine.snapshot(e.t), e.t)
        pacer?.update(e.t, engine.snapshot(e.t), tracker?.state)?.let { if (live) cues += it }
        engine.suppressAutoPause = runner?.suppressAutoPause ?: false
        return Output(signal, issued, if (live) cues else emptyList())
    }

    /** The clock moved on without an event. */
    fun tick(t: Long): Output {
        val cues = mutableListOf<Cue>()
        val issued = mutableListOf<Event>()
        evaluate(t, cues, issued)
        pacer?.let { cues += it.update(t, engine.snapshot(t), tracker?.state) }
        return Output(Signal.NONE, issued, cues)
    }

    private fun react(signal: Signal, t: Long, cues: MutableList<Cue>) {
        val s = engine.snapshot(t)
        when (signal) {
            Signal.STARTED -> runner?.let { cues += it.start(s, t) }
            Signal.LAP -> runner?.let { cues += it.onLap(s, t) }
            Signal.PAUSED -> cues += Cue.Paused(t, auto = false)
            Signal.AUTO_PAUSED -> cues += Cue.Paused(t, auto = true)
            Signal.RESUMED, Signal.AUTO_RESUMED -> cues += Cue.Resumed(t)
            else -> Unit
        }
    }

    private fun evaluate(t: Long, cues: MutableList<Cue>, issued: MutableList<Event>) {
        val r = runner ?: return
        val u = r.update(engine.snapshot(t), t)
        cues += u.cues
        if (u.endStep) {
            // A step that ends is a lap, so the file and the server follow the session.
            val lap = Event.Lap(t)
            issued += lap
            if (engine.on(lap) == Signal.LAP) cues += r.onLap(engine.snapshot(t), t)
            engine.suppressAutoPause = r.suppressAutoPause
        }
    }

    fun snapshot(now: Long): Snapshot = engine.snapshot(now)

    /** Settings that are not part of the log: they belong to the phone, not to the run. */
    data class Settings(
        val engine: SessionEngine.Config = SessionEngine.Config(),
        val zoneBounds: List<Int> = emptyList(),
        val runner: WorkoutRunner.Config = WorkoutRunner.Config(),
        val tracker: RouteTracker.Config = RouteTracker.Config(),
        val pacer: Pacer.Config = Pacer.Config(),
    )

    companion object {
        fun of(plan: Event.Plan?, route: RoutePath?, settings: Settings) = Coach(
            settings.engine, plan?.workout, settings.zoneBounds, settings.runner,
            route, settings.tracker, plan?.pacer, settings.pacer,
        )

        /**
         * Rebuilds a run from its log: the plan first, then every event without issuing anything.
         * [routeOf] finds the points of the planned route on the phone; a route no longer there
         * leaves the run without route following rather than without the run.
         */
        fun fromLog(events: List<Event>, settings: Settings, routeOf: (RouteRef) -> RoutePath?): Coach {
            val plan = events.firstOrNull() as? Event.Plan
            val route = plan?.route?.let(routeOf)?.takeIf { it.id == plan.route.id && it.version == plan.route.version }
            val coach = of(plan, route, settings)
            events.forEach { coach.on(it, live = false) }
            return coach
        }

        /** Rebuilds a run from its log, then continues live from where it stopped. */
        fun replay(coach: Coach, events: Iterable<Event>): Coach = coach.also { c -> events.forEach { c.on(it, live = false) } }
    }
}
