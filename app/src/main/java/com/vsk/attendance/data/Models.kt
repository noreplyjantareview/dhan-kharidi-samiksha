package com.vsk.attendance.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Staff(
    val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("employee_code") val employeeCode: String = "",
    @SerialName("mobile_number") val mobileNumber: String,
    val role: String,
    val category: String = "staff",          // staff = one kendra + shift, nodal = visits many
    @SerialName("face_template") val faceTemplate: String? = null,
    @SerialName("face_enrollment_allowed") val faceEnrollmentAllowed: Boolean = false,
    @SerialName("face_revision") val faceRevision: String? = null
) {
    val isNodal: Boolean get() = category == "nodal"
}

/**
 * A kendra plus the shift this staff member holds there. Timing lives on the
 * assignment, so it is null for nodal officers (no fixed visit time).
 */
@Serializable
data class Kendra(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("radius_m") val radiusM: Int,
    @SerialName("check_in_start") val checkInStart: String? = null,
    @SerialName("check_in_end") val checkInEnd: String? = null,
    @SerialName("day_end") val dayEnd: String? = null,
    @SerialName("verify_interval_min") val verifyIntervalMin: Int = 30
)

@Serializable
data class LoginResult(
    val ok: Boolean = false,
    val error: String? = null,
    val reason: String? = null,
    val staff: Staff? = null,
    val kendras: List<Kendra> = emptyList(),
    @SerialName("face_revision") val faceRevision: String? = null
)

@Serializable
data class FaceCheckResult(
    val ok: Boolean = false,
    val error: String? = null,
    val reason: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 90,
    @SerialName("face_revision") val faceRevision: String? = null
)

@Serializable
data class ActionResult(
    val ok: Boolean = false,
    val status: String? = null,
    val reason: String? = null,
    val error: String? = null,
    val message: String? = null,
    val category: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
    @SerialName("attendance_status") val attendanceStatus: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    // check-in / pulse extras
    @SerialName("kendra_id") val kendraId: String? = null,
    @SerialName("kendra_name") val kendraName: String? = null,
    @SerialName("day_end") val dayEnd: String? = null,
    @SerialName("verify_interval_min") val verifyIntervalMin: Int? = null,
    @SerialName("shift_start") val shiftStart: String? = null,
    @SerialName("shift_end") val shiftEnd: String? = null,
    // nodal extras
    @SerialName("visit_count") val visitCount: Int? = null,
    @SerialName("visit_open") val visitOpen: Boolean? = null,
    // pulse extras
    val inside: Boolean? = null,
    val at: String? = null,
    @SerialName("present_count") val presentCount: Int? = null,
    @SerialName("absent_count") val absentCount: Int? = null,
    @SerialName("work_minutes") val workMinutes: Int? = null,
    @SerialName("auto_checked_out") val autoCheckedOut: Boolean? = null,
    @SerialName("check_out_reason") val checkOutReason: String? = null,
    @SerialName("face_revision") val faceRevision: String? = null
)

@Serializable
data class TodayRecord(
    val status: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("check_out_at") val checkOutAt: String? = null,
    @SerialName("check_in_kendra") val checkInKendra: String? = null,
    @SerialName("work_minutes") val workMinutes: Int? = null,
    @SerialName("check_out_reason") val checkOutReason: String? = null
)

@Serializable
data class Visit(
    val kendra: String? = null,
    @SerialName("check_in_at") val checkInAt: String? = null,
    @SerialName("last_pulse_at") val lastPulseAt: String? = null,
    val minutes: Int = 0
)

@Serializable
data class TodayResult(
    val ok: Boolean = false,
    val error: String? = null,
    val reason: String? = null,
    val day: String? = null,
    val category: String? = null,
    val record: TodayRecord? = null,
    val visits: List<Visit> = emptyList(),
    @SerialName("visit_count") val visitCount: Int = 0,
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
