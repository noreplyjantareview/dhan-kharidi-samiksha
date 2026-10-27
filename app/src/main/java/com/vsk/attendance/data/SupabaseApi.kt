package com.vsk.attendance.data

import com.vsk.attendance.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseApi {

    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()

    val configured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank() &&
                !baseUrl.contains("YOUR-PROJECT-REF") && !anonKey.contains("YOUR-ANON")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(this@SupabaseApi.json) }
    }

    private suspend inline fun <reified T> rpc(fn: String, args: JsonObject): T {
        if (!configured) {
            throw IllegalStateException(
                "Supabase is not configured. Add SUPABASE_URL and SUPABASE_ANON_KEY to local.properties, then rebuild."
            )
        }
        val response = client.post("$baseUrl/rest/v1/rpc/$fn") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $anonKey")
            contentType(ContentType.Application.Json)
            setBody(args)
        }
        return response.body<T>()
    }

    // ---- staff / attendance ----
    suspend fun login(mobile: String): LoginResult =
        rpc("login_by_mobile", buildJsonObject { put("p_mobile", mobile) })

    suspend fun checkIn(staffId: String, lat: Double, lon: Double): ActionResult =
        rpc("record_check_in", buildJsonObject {
            put("p_staff_id", staffId); put("p_lat", lat); put("p_long", lon)
        })

    suspend fun pulse(staffId: String, lat: Double, lon: Double): ActionResult =
        rpc("record_pulse", buildJsonObject {
            put("p_staff_id", staffId); put("p_lat", lat); put("p_long", lon)
        })

    suspend fun checkOut(staffId: String, lat: Double, lon: Double): ActionResult =
        rpc("record_check_out", buildJsonObject {
            put("p_staff_id", staffId); put("p_lat", lat); put("p_long", lon)
        })

    suspend fun today(staffId: String): TodayResult =
        rpc("get_today", buildJsonObject { put("p_staff_id", staffId) })

    suspend fun history(staffId: String, from: String, to: String): HistoryResult =
        rpc("get_history", buildJsonObject {
            put("p_staff_id", staffId); put("p_from", from); put("p_to", to)
        })

    suspend fun saveFaceTemplate(staffId: String, template: String): ActionResult =
        rpc("save_face_template", buildJsonObject {
            put("p_staff_id", staffId); put("p_template", template)
        })
}
