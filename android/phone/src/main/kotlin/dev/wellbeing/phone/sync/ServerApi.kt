package dev.wellbeing.phone.sync

import dev.wellbeing.running.Units
import dev.wellbeing.running.Zones
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ServerProfile(val id: Long, val name: String, val units: Units, val zones: Zones)

data class ServerActivity(val id: Long, val startT: Long, val title: String, val distance: Double, val movingMs: Long, val avgHr: Int?, val gain: Double?)

/** Outcome of one uploaded file, as the server reports it. */
data class ImportOutcome(val status: String, val activityId: Long?, val reason: String?) {
    companion object {
        const val IMPORTED = "imported"
        const val DUPLICATE = "duplicate"
        const val FAILED = "failed"
    }
}

/** Why a request to the home server did not give a usable answer. */
class ApiException(val kind: Kind, message: String? = null) : IOException(message) {
    enum class Kind {
        /** Nothing answers at the address: away from home, server off, wrong address. */
        UNREACHABLE,
        /** Something answers, but it is not this product's server. */
        NOT_OUR_SERVER,
        /** The profile runs go to no longer exists. */
        PROFILE_MISSING,
        /** The server answered with an error it should not give. */
        SERVER_ERROR,
    }
}

/** What the sync needs from the home server; faked in tests. */
interface ServerClient {
    /** Checks that the address answers as this product's server and returns its version. */
    fun health(): String
    fun profiles(): List<ServerProfile>
    /** The profile, or null when it does not exist (any more). */
    fun profile(id: Long): ServerProfile?
    fun importFit(profileId: Long, fileName: String, bytes: ByteArray, tzOffsetMinutes: Int): ImportOutcome
    /** Applies notes to an activity; a null [title] leaves the server's title. False when the activity is gone. */
    fun patchJournal(activityId: Long, title: String?, description: String, rpe: Int?): Boolean
    fun activities(profileId: Long): List<ServerActivity>
}

/**
 * The home server's REST API (`/api/v1`), over plain HTTP on the local network. Every call is
 * blocking and only ever goes to the address the user configured.
 */
class ServerApi(baseUrl: String, client: OkHttpClient = defaultClient) : ServerClient {
    private val http = client
    private val base: HttpUrl = normalise(baseUrl) ?: throw ApiException(ApiException.Kind.UNREACHABLE, "invalid address")
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String) = base.newBuilder().addPathSegments("api/v1/$path").build()

    private fun request(path: String) = Request.Builder().url(url(path))
        // Required by the server on every state-changing request.
        .header("X-Requested-With", "wellbeing-android")

    /** Runs a request and returns (status code, body); connection problems become UNREACHABLE. */
    private fun call(request: Request, client: OkHttpClient = http): Pair<Int, String> = try {
        client.newCall(request).execute().use { it.code to (it.body.string()) }
    } catch (e: IOException) {
        throw ApiException(ApiException.Kind.UNREACHABLE, e.message)
    }

    private fun parse(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw ApiException(ApiException.Kind.NOT_OUR_SERVER, "unexpected answer")
    }

    private fun ok(result: Pair<Int, String>): JsonObject {
        if (result.first !in 200..299) throw ApiException(ApiException.Kind.SERVER_ERROR, "HTTP ${result.first}")
        return parse(result.second)
    }

    override fun health(): String {
        val (code, body) = call(request("health").build(), quickClient(http))
        // The health payload is this product's signature: status, version and database state.
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val version = o?.get("version")?.jsonPrimitive?.contentOrNull
        if (o == null || version == null || "status" !in o || "database" !in o) throw ApiException(ApiException.Kind.NOT_OUR_SERVER)
        if (code != 200) throw ApiException(ApiException.Kind.SERVER_ERROR, "HTTP $code")
        return version
    }

    private fun profileOf(o: JsonObject): ServerProfile {
        val bounds = o["zones"]?.jsonObject?.get("bounds")?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }
        return ServerProfile(
            id = o.getValue("id").jsonPrimitive.longOrNull ?: throw ApiException(ApiException.Kind.NOT_OUR_SERVER),
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            units = if (o["units"]?.jsonPrimitive?.contentOrNull == "imperial") Units.IMPERIAL else Units.METRIC,
            zones = bounds?.takeIf { it.size == 6 }?.let(::Zones) ?: Zones.default,
        )
    }

    override fun profiles(): List<ServerProfile> =
        ok(call(request("profiles").build())).getValue("profiles").jsonArray.map { profileOf(it.jsonObject) }

    override fun profile(id: Long): ServerProfile? {
        val result = call(request("profiles/$id").build())
        return if (result.first == 404) null else profileOf(ok(result))
    }

    override fun importFit(profileId: Long, fileName: String, bytes: ByteArray, tzOffsetMinutes: Int): ImportOutcome {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val target = url("profiles/$profileId/imports").newBuilder().addQueryParameter("tz_offset_minutes", tzOffsetMinutes.toString()).build()
        val result = call(request("").url(target).post(body).build())
        if (result.first == 404) throw ApiException(ApiException.Kind.PROFILE_MISSING)
        val first = ok(result)["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ApiException(ApiException.Kind.SERVER_ERROR, "no outcome")
        return ImportOutcome(
            status = first["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            activityId = first["activity_id"]?.jsonPrimitive?.longOrNull,
            reason = first["reason"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override fun patchJournal(activityId: Long, title: String?, description: String, rpe: Int?): Boolean {
        val payload = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", title)
            put("description", description)
            if (rpe == null) put("rpe", JsonNull) else put("rpe", rpe)
        }
        val result = call(request("activities/$activityId").patch(payload.toString().toRequestBody("application/json".toMediaType())).build())
        if (result.first == 404) return false
        ok(result)
        return true
    }

    override fun activities(profileId: Long): List<ServerActivity> {
        val target = url("profiles/$profileId/activities").newBuilder().addQueryParameter("limit", "100").build()
        val result = call(request("").url(target).build())
        if (result.first == 404) throw ApiException(ApiException.Kind.PROFILE_MISSING)
        return ok(result)["items"]?.jsonArray.orEmpty().map { it.jsonObject }.map { o ->
            ServerActivity(
                id = o.getValue("id").jsonPrimitive.longOrNull ?: 0,
                startT = ((o["start_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                distance = o["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                movingMs = ((o["moving_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                avgHr = o["avg_hr"]?.jsonPrimitive?.doubleOrNull?.let { Math.round(it).toInt() },
                gain = o["gain"]?.jsonPrimitive?.doubleOrNull,
            )
        }
    }

    companion object {
        /** The health check must answer fast: it decides whether the phone is at home. */
        const val HEALTH_TIMEOUT_S = 3L

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun quickClient(from: OkHttpClient) = from.newBuilder()
            .connectTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS).readTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS).callTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        /** Accepts `192.168.1.20:8080` as well as a full `http://…` address; null when unusable. */
        fun normalise(input: String): HttpUrl? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            return (if ("://" in trimmed) trimmed else "http://$trimmed").toHttpUrlOrNull()
        }
    }
}
