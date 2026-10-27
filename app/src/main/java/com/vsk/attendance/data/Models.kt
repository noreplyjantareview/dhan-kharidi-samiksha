package com.vsk.attendance.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Staff(
    val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("mobile_number") val mobileNumber: String,
    val role: String,
    @SerialName("duty_type") val dutyType: String
)

@Serializable
data class Kendra(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("radius_m") val radiusM: Int,
    @SerialName("check_in_start") val checkInStart: String,
    @SerialName("check_in_end") val checkInEnd: String,
    @SerialName("day_end") val dayEnd: String,
    @SerialName("verify_interval_min") val verifyIntervalMin: Int = 30
)

@Serializable
data class LoginResult(
    val ok: Boolean = false,
    val error: String? = null,
    val staff: Staff? = null,
    val kendras: List<Kendra> = emptyList()
)

@Serializable
data class ActionResult(
    val ok: Boolean = false,
    val status: String? = null,
    val reason: String? = null,
    val error: String? = null,
    val message: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
    @SerialName("attendance_status") val attendanceStatus: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    // check-in / pulse extras
    @SerialName("kendra_id") val kendraId: String? = null,
    @SerialName("kendra_name") val kendraName: String? = null,
    @SerialName("day_end") val dayEnd: String? = null,
    @SerialName("verify_interval_min") val verifyIntervalMin: Int? = null,
    // pulse extras
    val inside: Boolean? = null,
    val at: String? = null,
    @SerialName("present_count") val presentCount: Int? = null,
    @SerialName("absent_count") val absentCount: Int? = null
)

@Serializable
data class TodayRecord(
    val status: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
    @SerialName("check_in_kendra") val checkInKendra: String? = null
)

@Serializable
data class TodayResult(
    val ok: Boolean = false,
    val day: String? = null,
    val record: TodayRecord? = null,
    @SerialName("present_count") val presentCount: Int = 0,
    @SerialName("absent_count") val absentCount: Int = 0,
    @SerialName("last_pulse_at") val lastPulseAt: String? = null
)

@Serializable
data class HistoryRow(
    val day: String,
    val status: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null
)

@Serializable
data class HistoryResult(
    val ok: Boolean = false,
    val error: String? = null,
    val rows: List<HistoryRow> = emptyList()
)

@Serializable
data class StaffSummary(
    val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("mobile_number") val mobileNumber: String,
    val role: String,
    @SerialName("duty_type") val dutyType: String,
    @SerialName("kendra_names") val kendraNames: String? = null
)

@Serializable
data class KendraListResult(
    val ok: Boolean = false,
    val error: String? = null,
    val rows: List<Kendra> = emptyList()
)

@Serializable
data class StaffListResult(
    val ok: Boolean = false,
    val error: String? = null,
    val rows: List<StaffSummary> = emptyList()
)

@Serializable
data class KendraIdsResult(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("kendra_ids") val kendraIds: List<String> = emptyList()
)

@Serializable
data class IdResult(
    val ok: Boolean = false,
    val id: String? = null,
    val error: String? = null
)
