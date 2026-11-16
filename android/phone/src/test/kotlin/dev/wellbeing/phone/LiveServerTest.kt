package dev.wellbeing.phone

import dev.wellbeing.phone.sync.ImportOutcome
import dev.wellbeing.phone.sync.ServerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The client against a real home server. Skipped unless `WELLBEING_TEST_SERVER` names one, for
 * example a server started from `server/` with an empty data directory:
 *
 *     WELLBEING_TEST_SERVER=http://127.0.0.1:8090 ./gradlew :phone:testDebugUnitTest
 *
 * The server must hold at least one profile; the test uploads the encoder's fixture into the first.
 */
class LiveServerTest {
    @Test
    fun `a recorded run is uploaded once, annotated and listed`() {
        val url = System.getenv("WELLBEING_TEST_SERVER")
        assumeTrue("WELLBEING_TEST_SERVER is not set", !url.isNullOrBlank())
        val api = ServerApi(url!!)
        assertTrue(api.health().isNotBlank())
        val profile = api.profiles().first()
        assertEquals(profile, api.profile(profile.id))

        val fit = File("../../server/tests/fixtures/android-recorder.fit").readBytes()
        val first = api.importFit(profile.id, "run.fit", fit, 60)
        assertTrue("first upload: $first", first.status == ImportOutcome.IMPORTED || first.status == ImportOutcome.DUPLICATE)
        val id = assertNotNullId(first.activityId)

        // Sending the same run again never creates a second activity.
        val again = api.importFit(profile.id, "run.fit", fit, 60)
        assertEquals(ImportOutcome.DUPLICATE, again.status)
        assertEquals(id, again.activityId)

        assertTrue(api.patchJournal(id, "Essai depuis le téléphone", "Jambes lourdes", 7))
        val listed = api.activities(profile.id).single { it.id == id }
        assertEquals("Essai depuis le téléphone", listed.title)
        assertEquals(5028.8, listed.distance, 60.0)
        assertEquals(140, listed.avgHr)
    }

    private fun assertNotNullId(id: Long?): Long {
        assertNotNull(id)
        return id!!
    }
}
