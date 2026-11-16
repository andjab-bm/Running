package dev.wellbeing.phone

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.wellbeing.phone.data.AppDb
import dev.wellbeing.phone.data.EventEntity
import dev.wellbeing.phone.data.HrEntity
import dev.wellbeing.phone.data.LapEntity
import dev.wellbeing.phone.data.RunEntity
import dev.wellbeing.phone.data.RunState
import dev.wellbeing.phone.data.SampleEntity
import dev.wellbeing.phone.data.SyncState
import dev.wellbeing.phone.data.TimerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DbTest {
    private lateinit var db: AppDb

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `the event log of a run in progress round-trips in order`() = runBlocking {
        val dao = db.runs()
        val id = dao.insertRun(RunEntity(startT = 1000, autoPause = false, splitMeters = 1609.344))
        dao.insertEvents(listOf(EventEntity(runId = id, type = 0, t = 1000), EventEntity(runId = id, type = 5, t = 2000, a = 48.85, b = 2.35, c = null, d = 5.0)))
        dao.insertEvents(listOf(EventEntity(runId = id, type = 3, t = 3000)))
        assertEquals(listOf(0, 5, 3), dao.events(id).map { it.type })
        assertEquals(48.85, dao.events(id)[1].a!!, 0.0)
        assertNull(dao.events(id)[1].c)

        val found = assertNotNullRun(dao.interrupted())
        assertEquals(id, found.id)
        assertEquals(false, found.autoPause)
        assertEquals(1609.344, found.splitMeters, 0.0)
        assertTrue(dao.watchSaved().first().isEmpty())
    }

    @Test
    fun `saving a run stores its data and drops the event log`() = runBlocking {
        val dao = db.runs()
        val id = dao.insertRun(RunEntity(startT = 1000))
        dao.insertEvents(listOf(EventEntity(runId = id, type = 0, t = 1000)))
        dao.insertHr(listOf(HrEntity(id, 1500, 140), HrEntity(id, 1500, 142)))
        dao.saveFinished(
            RunEntity(id = id, state = RunState.SAVED, startT = 1000, endT = 9000, distance = 20.0, movingMs = 8000, rpe = 6, note = "ok", fitPath = "runs/$id.fit"),
            listOf(SampleEntity(id, 2000, 48.85, 2.35, 35.0, 3.0, 170, 2.8)),
            listOf(LapEntity(id, 0, 1000, 9000, 20.0, 8000, false, 141)),
            listOf(TimerEntity(id, 1000, true), TimerEntity(id, 9000, false)),
        )
        assertTrue(dao.events(id).isEmpty())
        assertNull(dao.interrupted())
        val saved = dao.watchSaved().first().single()
        assertEquals(6, saved.rpe)
        assertEquals(SyncState.PENDING, saved.sync)
        assertEquals(1, dao.samples(id).size)
        assertEquals(141, dao.laps(id).single().avgHr)
        assertEquals(2, dao.timer(id).size)
        // The later heart-rate value for the same instant replaced the first.
        assertEquals(listOf(142), dao.hr(id).map { it.bpm })
        assertEquals(listOf(id), dao.toSync().map { it.id })
    }

    @Test
    fun `runs to sync are pending ones and synced ones with edited notes`() = runBlocking {
        val dao = db.runs()
        val pending = dao.insertRun(RunEntity(state = RunState.SAVED, startT = 3000))
        val synced = dao.insertRun(RunEntity(state = RunState.SAVED, startT = 1000, sync = SyncState.SYNCED, serverActivityId = 7))
        val edited = dao.insertRun(RunEntity(state = RunState.SAVED, startT = 2000, sync = SyncState.SYNCED, serverActivityId = 8, journalDirty = true))
        dao.insertRun(RunEntity(state = RunState.SAVED, startT = 4000, sync = SyncState.FAILED, failReason = "refused"))
        dao.insertRun(RunEntity(startT = 5000))
        // Oldest first.
        assertEquals(listOf(edited, pending), dao.toSync().map { it.id })
        assertNotNull(dao.run(synced))
    }

    @Test
    fun `deleting a run removes everything recorded for it`() = runBlocking {
        val dao = db.runs()
        val id = dao.insertRun(RunEntity(state = RunState.SAVED, startT = 1000))
        val other = dao.insertRun(RunEntity(state = RunState.SAVED, startT = 2000))
        dao.insertSamples(listOf(SampleEntity(id, 2000, 48.85, 2.35, null, 3.0, null, null), SampleEntity(other, 2000, 48.85, 2.35, null, 3.0, null, null)))
        dao.insertHr(listOf(HrEntity(id, 1500, 140)))
        dao.deleteRun(id)
        assertNull(dao.run(id))
        assertTrue(dao.samples(id).isEmpty())
        assertTrue(dao.hr(id).isEmpty())
        assertEquals(1, dao.samples(other).size)
    }

    private fun assertNotNullRun(r: RunEntity?): RunEntity {
        assertNotNull(r)
        return r!!
    }
}
