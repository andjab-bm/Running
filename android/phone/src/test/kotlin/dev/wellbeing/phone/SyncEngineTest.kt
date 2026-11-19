package dev.wellbeing.phone

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.RunDao
import dev.wellbeing.phone.data.RunEntity
import dev.wellbeing.phone.data.RunState
import dev.wellbeing.phone.data.Settings
import dev.wellbeing.phone.data.SyncState
import dev.wellbeing.phone.sync.ApiException
import dev.wellbeing.phone.sync.HeldItem
import dev.wellbeing.phone.sync.ImportOutcome
import dev.wellbeing.phone.sync.InUse
import dev.wellbeing.phone.sync.PlanCount
import dev.wellbeing.phone.sync.PlanManifest
import dev.wellbeing.phone.sync.PlanRoute
import dev.wellbeing.phone.sync.PlanWorkout
import dev.wellbeing.phone.sync.RoutePoints
import dev.wellbeing.phone.sync.ServerActivity
import dev.wellbeing.phone.sync.ServerClient
import dev.wellbeing.phone.sync.ServerProfile
import dev.wellbeing.phone.sync.SyncEngine
import dev.wellbeing.phone.sync.SyncResult
import dev.wellbeing.running.Units
import dev.wellbeing.running.Zones
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scenarios of the `run-sync` spec, against a scripted server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncEngineTest {
    private lateinit var db: AppDb
    private lateinit var dao: RunDao
    private var settings = Settings(serverUrl = "http://home:8080", profileId = 2, profileName = "Sarah")
    private val server = FakeServer()

    /** A stand-in for the home server that remembers what it was asked. */
    private class FakeServer : ServerClient {
        var reachable = true
        var profile: ServerProfile? = ServerProfile(2, "Sarah", Units.IMPERIAL, Zones.fromMax(180.0))
        /** Start times (from the file name) the server already holds, with their activity id. */
        val held = HashMap<String, Long>()
        val refuse = HashSet<String>()
        /** Uploads still accepted before the connection drops; negative means no limit. */
        var uploadsBeforeDrop = -1
        val imports = ArrayList<String>()
        val patches = ArrayList<String>()
        val contexts = ArrayList<String?>()
        val workouts = ArrayList<PlanWorkout>()
        val routes = ArrayList<PlanRoute>()
        val pointsFetched = ArrayList<Long>()
        val receipts = ArrayList<List<HeldItem>>()
        private var nextId = 100L

        private fun check() { if (!reachable) throw ApiException(ApiException.Kind.UNREACHABLE) }
        override fun health(): String { check(); return "0.1.0" }
        override fun profiles() = listOfNotNull(profile)
        override fun profile(id: Long): ServerProfile? { check(); return profile?.takeIf { it.id == id } }
        override fun importFit(profileId: Long, fileName: String, bytes: ByteArray, tzOffsetMinutes: Int): ImportOutcome {
            check()
            if (uploadsBeforeDrop == 0) throw ApiException(ApiException.Kind.UNREACHABLE)
            if (uploadsBeforeDrop > 0) uploadsBeforeDrop--
            imports += fileName
            if (fileName in refuse) return ImportOutcome(ImportOutcome.FAILED, null, "The file is damaged.")
            held[fileName]?.let { return ImportOutcome(ImportOutcome.DUPLICATE, it, "This run is already in the profile.") }
            return ImportOutcome(ImportOutcome.IMPORTED, nextId.also { held[fileName] = it; nextId++ }, null)
        }
        override fun patchJournal(activityId: Long, title: String?, description: String, rpe: Int?, context: String?): Boolean {
            check(); patches += "$activityId|$title|$description|$rpe"; contexts += context; return true
        }
        override fun plan(profileId: Long): PlanManifest {
            check(); return PlanManifest(workouts.toList(), routes.toList(), "https://tiles.example/style.json", "https://tiles.example/dark.json")
        }
        override fun routePoints(id: Long): RoutePoints {
            check(); pointsFetched += id
            val v = routes.first { it.id == id }.version
            return RoutePoints(v, """{"lat":[48.85,48.86],"lon":[2.35,2.35],"ele":null,"dist":[0,1112]}""")
        }
        override fun planReceipt(profileId: Long, device: String, items: List<HeldItem>) { check(); receipts += items }
        override fun activities(profileId: Long) = held.values.sorted().map { ServerActivity(it, it * 1000, "Sortie", 5000.0, 1_800_000, null, null) } +
            ServerActivity(1, 500, "Footing importé", 7200.0, 2_700_000, null, 40.0)
    }

    private var inUse = InUse()

    private fun engine() = SyncEngine(
        dao, { settings }, { settings = it(settings) }, { server }, { path -> if (path.endsWith("missing.fit")) null else path.toByteArray() },
        plan = db.plan(), device = "Pixel 8", inUse = { inUse },
    )

    private val intervals = PlanWorkout(5, 1, "6 × 400 m", "", """[{"type":"step","kind":"effort","end":{"type":"distance","value":400}}]""")
    private fun route(version: Long = 1, id: Long = 9) = PlanRoute(id, version, "Boucle du canal", 9400.0, 62.0, 62.0, true, true)

    private suspend fun saved(startT: Long, title: String = "", rpe: Int? = null, note: String = "", path: String = "runs/$startT.fit") =
        dao.insertRun(RunEntity(state = RunState.SAVED, startT = startT, endT = startT + 1_800_000, distance = 5000.0, movingMs = 1_800_000, title = title, rpe = rpe, note = note, fitPath = path))

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).allowMainThreadQueries().build()
        dao = db.runs()
    }

    @After
    fun close() = db.close()

    @Test
    fun `several pending runs are uploaded oldest first`() = runBlocking {
        saved(3000); saved(1000); saved(2000)
        val report = engine().run()
        assertEquals(SyncResult.DONE, report.result)
        assertEquals(3, report.uploaded)
        assertEquals(0, report.pending)
        assertEquals(listOf("run-1000.fit", "run-2000.fit", "run-3000.fit"), server.imports)
        assertTrue(dao.watchSaved().first().all { it.sync == SyncState.SYNCED && it.serverActivityId != null })
    }

    @Test
    fun `the chosen profile's units and zones are refreshed`() = runBlocking {
        engine().run()
        assertEquals(Units.IMPERIAL, settings.units)
        assertEquals(Zones.fromMax(180.0), settings.zones)
    }

    @Test
    fun `away from home nothing is sent and runs stay pending`() = runBlocking {
        val id = saved(1000)
        server.reachable = false
        val report = engine().run()
        assertEquals(SyncResult.UNREACHABLE, report.result)
        assertEquals(1, report.pending)
        assertTrue(server.imports.isEmpty())
        assertEquals(SyncState.PENDING, dao.run(id)!!.sync)
    }

    @Test
    fun `a connection lost mid-sync is retried and no run appears twice`() = runBlocking {
        val first = saved(1000); val second = saved(2000)
        server.uploadsBeforeDrop = 1
        assertEquals(SyncResult.UNREACHABLE, engine().run().result)
        assertEquals(SyncState.SYNCED, dao.run(first)!!.sync)
        assertEquals(SyncState.PENDING, dao.run(second)!!.sync)

        server.uploadsBeforeDrop = -1
        assertEquals(SyncResult.DONE, engine().run().result)
        // The first run was not sent again; each exists once on the server.
        assertEquals(listOf("run-1000.fit", "run-2000.fit"), server.imports)
        assertEquals(2, server.held.size)
    }

    @Test
    fun `a run the server already holds is linked to it`() = runBlocking {
        val id = saved(1000)
        server.held["run-1000.fit"] = 42
        engine().run()
        val run = dao.run(id)!!
        assertEquals(SyncState.SYNCED, run.sync)
        assertEquals(42L, run.serverActivityId)
    }

    @Test
    fun `a refused file is marked failed, kept, and not retried`() = runBlocking {
        val bad = saved(1000); val good = saved(2000)
        server.refuse += "run-1000.fit"
        val report = engine().run()
        assertEquals(1, report.failed)
        assertEquals(1, report.uploaded)
        assertEquals(SyncState.FAILED, dao.run(bad)!!.sync)
        assertEquals("The file is damaged.", dao.run(bad)!!.failReason)
        assertEquals(SyncState.SYNCED, dao.run(good)!!.sync)
        engine().run()
        assertEquals(1, server.imports.count { it == "run-1000.fit" })
    }

    @Test
    fun `a run whose file is gone is marked failed without contacting the import`() = runBlocking {
        val id = saved(1000, path = "runs/missing.fit")
        engine().run()
        assertEquals(SyncState.FAILED, dao.run(id)!!.sync)
        assertTrue(server.imports.isEmpty())
    }

    @Test
    fun `a deleted profile uploads nothing and asks for another profile`() = runBlocking {
        val id = saved(1000)
        server.profile = null
        val report = engine().run()
        assertEquals(SyncResult.PROFILE_MISSING, report.result)
        assertEquals(1, report.pending)
        assertTrue(server.imports.isEmpty())
        assertEquals(SyncState.PENDING, dao.run(id)!!.sync)
    }

    @Test
    fun `without a server or a profile runs just wait`() = runBlocking {
        saved(1000)
        settings = Settings()
        assertEquals(SyncResult.NOT_CONFIGURED, engine().run().result)
        settings = Settings(serverUrl = "http://home:8080")
        assertEquals(SyncResult.NOT_CONFIGURED, engine().run().result)
        assertTrue(server.imports.isEmpty())
    }

    @Test
    fun `effort and note entered after the run reach the server`() = runBlocking {
        saved(1000, rpe = 7, note = "Jambes lourdes")
        engine().run()
        // No title entered: the server keeps its own.
        assertEquals(listOf("100|null|Jambes lourdes|7"), server.patches)
    }

    @Test
    fun `a run saved without notes sends no patch`() = runBlocking {
        saved(1000)
        engine().run()
        assertTrue(server.patches.isEmpty())
    }

    @Test
    fun `editing a synced run sends the change without uploading again`() = runBlocking {
        val id = saved(1000, title = "Sortie du soir")
        engine().run()
        server.imports.clear(); server.patches.clear()
        dao.updateRun(dao.run(id)!!.copy(note = "Belle lumière", journalDirty = true))
        assertEquals(listOf(id), dao.toSync().map { it.id })

        val report = engine().run()
        assertEquals(0, report.pending)
        assertTrue(server.imports.isEmpty())
        assertEquals(listOf("100|Sortie du soir|Belle lumière|null"), server.patches)
        assertEquals(false, dao.run(id)!!.journalDirty)
    }

    @Test
    fun `an edit made away from home waits and is sent once home`() = runBlocking {
        val id = saved(1000)
        engine().run()
        server.patches.clear()
        dao.updateRun(dao.run(id)!!.copy(title = "Nouveau titre", journalDirty = true))
        server.reachable = false
        assertEquals(1, engine().run().pending)
        assertTrue(dao.run(id)!!.journalDirty)
        server.reachable = true
        engine().run()
        assertEquals(listOf("100|Nouveau titre||null"), server.patches)
    }

    @Test
    fun `the profile's server activities are cached for the history`() = runBlocking {
        val id = saved(1000)
        engine().run()
        val cached = dao.watchServerActivities(2).first()
        assertEquals(2, cached.size)
        // The run recorded here is known by its server id, so the history shows it once.
        assertTrue(cached.any { it.id == dao.run(id)!!.serverActivityId })
        assertTrue(cached.any { it.title == "Footing importé" })
        assertNull(dao.run(id)!!.failReason)
    }

    // ---- plan delivery (spec `plan-delivery`) ----

    @Test
    fun `a session and a route marked the evening before are on the phone after the sync`() = runBlocking {
        server.workouts += intervals
        server.routes += route()
        val report = engine().run()
        assertEquals(PlanCount(1, 1), report.plan)
        assertEquals("6 × 400 m", db.plan().workout(5)!!.name)
        assertTrue(db.plan().route(9)!!.points.contains("48.86"))
        assertEquals(listOf(HeldItem("workout", 5, 1), HeldItem("route", 9, 1)), server.receipts.last())
        // The map style comes with the plan, for the map screen.
        assertEquals("https://tiles.example/style.json", settings.mapStyleUrl)
        assertEquals("https://tiles.example/dark.json", settings.mapStyleUrlDark)
    }

    @Test
    fun `an edited session is replaced, and route points are fetched only for a new version`() = runBlocking {
        server.workouts += intervals
        server.routes += route()
        engine().run()
        server.workouts[0] = intervals.copy(version = 2, name = "6 × 400 m (piste)")
        engine().run()
        assertEquals("6 × 400 m (piste)", db.plan().workout(5)!!.name)
        assertEquals(listOf(9L), server.pointsFetched)
        server.routes[0] = route(version = 2)
        engine().run()
        assertEquals(listOf(9L, 9L), server.pointsFetched)
        assertEquals(HeldItem("workout", 5, 2), server.receipts.last()[0])
    }

    @Test
    fun `an unmarked item leaves the phone at the next sync`() = runBlocking {
        server.workouts += intervals
        server.routes += route()
        engine().run()
        server.routes.clear()
        engine().run()
        assertNull(db.plan().route(9))
        assertEquals(listOf(HeldItem("workout", 5, 1)), server.receipts.last())
    }

    @Test
    fun `away from home nothing is fetched and what the phone holds stays usable`() = runBlocking {
        server.workouts += intervals
        engine().run()
        server.workouts.clear()
        server.reachable = false
        val report = engine().run()
        assertEquals(SyncResult.UNREACHABLE, report.result)
        assertNull(report.plan)
        assertEquals("6 × 400 m", db.plan().workout(5)!!.name)
    }

    @Test
    fun `a route in use while preparing stays until the run is over`() = runBlocking {
        server.routes += route()
        engine().run()
        server.routes.clear()
        inUse = InUse(routeId = 9)
        engine().run()
        assertEquals("Boucle du canal", db.plan().route(9)!!.name)
        inUse = InUse()
        engine().run()
        assertNull(db.plan().route(9))
    }

    @Test
    fun `changing profile removes the previous profile's plan and fetches the new one`() = runBlocking {
        server.workouts += intervals
        engine().run()
        settings = settings.copy(profileId = 3, profileName = "Alex")
        server.profile = ServerProfile(3, "Alex", Units.METRIC, Zones.fromMax(190.0))
        server.workouts.clear()
        server.workouts += PlanWorkout(8, 1, "Footing", "", "[]")
        engine().run()
        assertNull(db.plan().workout(5))
        assertEquals(3L, db.plan().workout(8)!!.profileId)
    }

    // ---- coaching context (spec `run-sync`, "Carry the coaching context") ----

    @Test
    fun `a session run away from home reaches the activity once home, with its steps`() = runBlocking {
        val context = """{"workout":{"id":5,"name":"6 × 400 m","steps":[]},"pacer":null}"""
        val id = dao.insertRun(RunEntity(state = RunState.SAVED, startT = 1000, endT = 2000, fitPath = "runs/1000.fit", context = context))
        server.reachable = false
        engine().run()
        assertTrue(server.patches.isEmpty())
        server.reachable = true
        engine().run()
        assertEquals(listOf(context), server.contexts)
        assertEquals(false, dao.run(id)!!.journalDirty)
    }

    @Test
    fun `the session as run is sent even if it was deleted on the web meanwhile`() = runBlocking {
        val context = """{"workout":{"id":5,"name":"6 × 400 m","steps":[{"type":"step","kind":"effort","end":{"type":"lap"}}]}}"""
        dao.insertRun(RunEntity(state = RunState.SAVED, startT = 1000, endT = 2000, fitPath = "runs/1000.fit", context = context))
        // Nothing in the plan any more: the phone sends what it kept with the run.
        engine().run()
        assertEquals(listOf(context), server.contexts)
    }
}
