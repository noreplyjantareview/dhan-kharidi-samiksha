package com.vsk.attendance.data

import com.vsk.attendance.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Safe transport errors: never expose a request body or its device credential to the UI. */
class AttendanceApiException(message: String) : IllegalStateException(message)

class SupabaseApi(private val deviceToken: () -> String) {
    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()

    val configured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank() &&
            !baseUrl.contains("YOUR-PROJECT-REF") && !anonKey.contains("YOUR-ANON")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(this@SupabaseApi.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 30_000L
        }
    }

    private suspend inline fun <reified T> rpc(fn: String, args: JsonObject): T {
        if (!configured) throw AttendanceApiException("Attendance service is not configured.")
        // Resolve at request time, not construction time or login time.
        val token = withContext(Dispatchers.IO) { deviceToken() }
        val protectedArgs = buildJsonObject {
            args.forEach { (name, value) -> put(name, value) }
            put("p_device_token", token)
        }
        try {
            val response = client.post("$baseUrl/rest/v1/rpc/$fn") {
                // Anon headers are transport credentials, not employee authorization.
                header("apikey", anonKey)
                header("Authorization", "Bearer $anonKey")
                contentType(ContentType.Application.Json)
                setBody(protectedArgs)
            }
            if (response.status.value !in 200..299) {
                throw AttendanceApiException("Attendance request failed (${response.status.value}). Please try again.")
            }
            return response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AttendanceApiException) {
            throw e
        } catch (_: Exception) {
            throw AttendanceApiException("Could not contact the attendance service. Check your connection and try again.")
        }
    }

    suspend fun login(mobile: String, activationCode: String? = null): LoginResult =
        rpc("login_staff_v3", buildJsonObject {
            put("p_mobile", mobile); put("p_activation_code", activationCode)
        })

    suspend fun beginFaceCheck(staffId: String, action: String): FaceCheckResult {
        require(action == "on" || action == "off")
        return rpc("begin_face_check_v3", buildJsonObject {
            put("p_staff_id", staffId); put("p_action", action)
        })
    }

    suspend fun checkIn(staffId: String, lat: Double, lon: Double, challengeId: String): ActionResult {
        require(challengeId.isNotBlank())
        return rpc("record_check_in_v3", buildJsonObject {
            put("p_staff_id", staffId); put("p_lat", lat); put("p_long", lon)
            put("p_challenge_id", challengeId)
        })
    }

    suspend fun pulse(staffId: String, lat: Double, lon: Double): ActionResult =
        rpc("record_pulse_v3", buildJsonObject {
            put("p_staff_id", staffId); put("p_lat", lat); put("p_long", lon)
        })

    suspend fun checkOut(
        staffId: String, lat: Double, lon: Double, reason: String, challengeId: String? = null
    ): ActionResult {
        require((reason == "day_end" && challengeId == null) ||
            (reason == "manual" && !challengeId.isNullOrBlank()))
        return rpc("record_check_out_v3", buildJsonObject {
            put("p_staff_id", staffId); put("p_lat", lat); put("p_long", lon)
            put("p_reason", reason); put("p_challenge_id", challengeId)
        })
    }

    suspend fun today(staffId: String): TodayResult =
        rpc("get_today_v3", buildJsonObject { put("p_staff_id", staffId) })

    suspend fun history(staffId: String, from: String, to: String): HistoryResult =
        rpc("get_history_v3", buildJsonObject {
            put("p_staff_id", staffId); put("p_from", from); put("p_to", to)
        })

    suspend fun saveFaceTemplate(staffId: String, template: String): ActionResult =
        rpc("save_face_template_v3", buildJsonObject {
            put("p_staff_id", staffId); put("p_template", template)
        })
}
