package dev.wellbeing.phone

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.wellbeing.phone.sync.ApiException
import dev.wellbeing.phone.sync.ImportOutcome
import dev.wellbeing.phone.sync.ServerApi
import dev.wellbeing.running.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/** The client against a local stand-in for the home server, answering with the server's own payloads. */
class ServerApiTest {
    private lateinit var server: HttpServer
    private lateinit var api: ServerApi

    private class Seen(val method: String, val path: String, val query: String?, val header: String?, val contentType: String?, val body: String)
    private val seen = ArrayList<Seen>()

    private fun route(path: String, code: Int, body: String, type: String = "application/json") {
        server.createContext(path) { x: HttpExchange ->
            seen += Seen(x.requestMethod, x.requestURI.path, x.requestURI.query, x.requestHeaders.getFirst("X-Requested-With"), x.requestHeaders.getFirst("Content-Type"), x.requestBody.readBytes().toString(Charsets.ISO_8859_1))
            val bytes = body.toByteArray()
            x.responseHeaders.add("Content-Type", type)
            x.sendResponseHeaders(code, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        api = ServerApi("127.0.0.1:${server.address.port}")
    }

    @After
    fun stop() = server.stop(0)

    private fun kindOf(block: () -> Unit): ApiException.Kind = try {
        block(); fail("an ApiException was expected"); throw AssertionError()
    } catch (e: ApiException) {
        e.kind
    }

    @Test
    fun `a running server is recognised and gives its version`() {
        route("/api/v1/health", 200, """{"status":"ok","version":"0.1.0","database":"ok"}""")
        assertEquals("0.1.0", api.health())
    }

    @Test
    fun `another service at the address is not taken for the server`() {
        route("/", 200, "<html><body>Router admin</body></html>", "text/html")
        assertEquals(ApiException.Kind.NOT_OUR_SERVER, kindOf { api.health() })
        // JSON alone is not enough: the payload must carry the server's own fields.
        server.removeContext("/")
        route("/api/v1/health", 200, """{"ok":true}""")
        assertEquals(ApiException.Kind.NOT_OUR_SERVER, kindOf { api.health() })
    }

    @Test
    fun `nothing answering is reported as unreachable`() {
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(ApiException.Kind.UNREACHABLE, kindOf { ServerApi("127.0.0.1:$free").health() })
        assertEquals(ApiException.Kind.UNREACHABLE, kindOf { ServerApi("not a url") })
    }

    @Test
    fun `profiles carry units and zones`() {
        route(
            "/api/v1/profiles", 200,
            """{"profiles":[
              {"id":1,"name":"Andjab","color":"#B9AFFF","hr_max":188,"units":"metric","zones":{"bounds":[94,113,132,150,169,188],"custom":false}},
              {"id":2,"name":"Sarah","color":"#F7AFC3","hr_max":null,"units":"imperial","zones":{"bounds":[95,114,133,152,171,190],"custom":false}}]}""",
        )
        val list = api.profiles()
        assertEquals(listOf("Andjab", "Sarah"), list.map { it.name })
        assertEquals(Units.IMPERIAL, list[1].units)
        assertEquals(4, list[0].zones.of(160))
        assertEquals(listOf(94.0, 113.0, 132.0, 150.0, 169.0, 188.0), list[0].zones.bounds)
    }

    @Test
    fun `a deleted profile is reported as missing`() {
        route("/api/v1/profiles/9", 404, """{"error":"not_found","message":"This profile does not exist."}""")
        route("/api/v1/profiles/9/imports", 404, """{"error":"not_found","message":"This profile does not exist."}""")
        assertNull(api.profile(9))
        assertEquals(ApiException.Kind.PROFILE_MISSING, kindOf { api.importFit(9, "run.fit", byteArrayOf(1), 60) })
    }

    @Test
    fun `an upload names the profile, carries the required header and reads the outcome`() {
        route("/api/v1/profiles/2/imports", 200, """{"results":[{"file":"run-1.fit","status":"imported","activity_id":41}]}""")
        val outcome = api.importFit(2, "run-1.fit", "FITDATA".toByteArray(), 60)
        assertEquals(ImportOutcome(ImportOutcome.IMPORTED, 41, null), outcome)
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("tz_offset_minutes=60", r.query)
        assertEquals("wellbeing-android", r.header)
        assertTrue(r.contentType!!.startsWith("multipart/form-data"))
        assertTrue("the file is sent with its name", "filename=\"run-1.fit\"" in r.body && "FITDATA" in r.body)
    }

    @Test
    fun `duplicate and refused files are reported as the server says`() {
        route("/api/v1/profiles/2/imports", 200, """{"results":[{"file":"a.fit","status":"duplicate","activity_id":7,"reason":"This run is already in the profile."}]}""")
        assertEquals(ImportOutcome.DUPLICATE, api.importFit(2, "a.fit", byteArrayOf(1), 0).status)
        assertEquals(7L, api.importFit(2, "a.fit", byteArrayOf(1), 0).activityId)
        server.removeContext("/api/v1/profiles/2/imports")
        route("/api/v1/profiles/2/imports", 200, """{"results":[{"file":"a.fit","status":"failed","reason":"The file is damaged."}]}""")
        val failed = api.importFit(2, "a.fit", byteArrayOf(1), 0)
        assertEquals(ImportOutcome.FAILED, failed.status)
        assertEquals("The file is damaged.", failed.reason)
    }

    @Test
    fun `notes are patched, and an empty title is left to the server`() {
        route("/api/v1/activities/41", 200, """{"id":41}""")
        assertTrue(api.patchJournal(41, null, "Jambes lourdes", 7))
        assertEquals("""{"description":"Jambes lourdes","rpe":7}""", seen.last().body)
        assertEquals("PATCH", seen.last().method)
        assertEquals("wellbeing-android", seen.last().header)
        assertTrue(api.patchJournal(41, "Sortie longue", "", null))
        assertEquals("""{"title":"Sortie longue","description":"","rpe":null}""", seen.last().body)
        route("/api/v1/activities/99", 404, """{"error":"not_found","message":"gone"}""")
        assertFalse(api.patchJournal(99, "x", "", null))
    }

    @Test
    fun `the activity list is read in SI units`() {
        route("/api/v1/profiles/1/activities", 200, """{"items":[{"id":5,"title":"Footing","start_time":1794127330.0,"distance":7200.5,"moving_time":2700.0,"gain":40.0,"avg_hr":null}],"next_cursor":null}""")
        val a = api.activities(1).single()
        assertEquals(5L, a.id)
        assertEquals(1_794_127_330_000L, a.startT)
        assertEquals(2_700_000L, a.movingMs)
        assertNull(a.avgHr)
        assertEquals("limit=100", seen.single().query)
    }

    @Test
    fun `server errors are not mistaken for success`() {
        route("/api/v1/profiles", 500, """{"error":"internal","message":"boom"}""")
        assertEquals(ApiException.Kind.SERVER_ERROR, kindOf { api.profiles() })
    }
}
