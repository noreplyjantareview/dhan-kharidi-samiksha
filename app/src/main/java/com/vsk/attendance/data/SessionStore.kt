package com.vsk.attendance.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("vsk_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(staff: Staff, kendras: List<Kendra>) {
        prefs.edit()
            .putString("staff", json.encodeToString(staff))
            .putString("kendras", json.encodeToString(kendras))
            .apply()
    }

    fun loadStaff(): Staff? =
        prefs.getString("staff", null)?.let { runCatching { json.decodeFromString<Staff>(it) }.getOrNull() }

    fun loadKendras(): List<Kendra> =
        prefs.getString("kendras", null)?.let {
            runCatching { json.decodeFromString<List<Kendra>>(it) }.getOrNull()
        } ?: emptyList()

    fun clear() = prefs.edit().clear().apply()
}
