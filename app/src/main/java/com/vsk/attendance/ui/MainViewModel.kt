package com.vsk.attendance.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vsk.attendance.data.Kendra
import com.vsk.attendance.data.SessionStore
import com.vsk.attendance.data.Staff
import com.vsk.attendance.data.SupabaseApi
import com.vsk.attendance.location.AttendanceAction
import com.vsk.attendance.location.AttendanceRules
import com.vsk.attendance.location.LocationHelper
import com.vsk.attendance.util.GeoUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class AttendanceUi(
    val checkInAt: String? = null,
    val checkOutAt: String? = null,
    val dayStatus: String? = null,          // present / late
    val checkInKendra: String? = null,
    val nearestKendraName: String? = null,
    val distanceM: Double? = null,
    val inside: Boolean? = null,
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val lastPulseAt: String? = null,
    val workMinutes: Int? = null,
    val checkOutReason: String? = null,
    val message: String = "Waiting for location…",
    val clock: String = "",
    val running: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val MOCK_MSG =
            "Mock location detected — turn off fake-GPS apps to mark duty."
    }

    private val api = SupabaseApi()
    private val session = SessionStore(app)
    private val locator = LocationHelper(app)

    private val _staff = MutableStateFlow<Staff?>(null)
    val staff: StateFlow<Staff?> = _staff.asStateFlow()

    private val _kendras = MutableStateFlow<List<Kendra>>(emptyList())   // allowed kendras for this staff
    val kendras: StateFlow<List<Kendra>> = _kendras.asStateFlow()

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _attendance = MutableStateFlow(AttendanceUi())
    val attendance: StateFlow<AttendanceUi> = _attendance.asStateFlow()

    private val _configError = MutableStateFlow<String?>(null)
    val configError: StateFlow<String?> = _configError.asStateFlow()

    private var attendanceJob: Job? = null
    private var activeKendraId: String? = null
    private var lastPulseMillis: Long = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastFixMock = false

    init {
        if (!api.configured) {
            _configError.value =
                "Supabase not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties and rebuild."
        }
        _staff.value = session.loadStaff()
        _kendras.value = session.loadKendras()
    }

    fun login(mobile: String) {
        val number = mobile.trim()
        if (number.isEmpty()) { _loginError.value = "Enter your registered mobile number."; return }
        _loginBusy.value = true
        _loginError.value = null
        viewModelScope.launch {
            try {
                val res = api.login(number)
                if (res.ok && res.staff != null) {
                    session.save(res.staff, res.kendras)
                    _staff.value = res.staff
                    _kendras.value = res.kendras
                    if (res.staff.role == "staff" && res.kendras.isNotEmpty()) startAttendance()
                } else {
                    _loginError.value = res.error ?: "Login failed."
                }
            } catch (e: Exception) {
                _loginError.value = e.message ?: "Login failed."
            } finally {
                _loginBusy.value = false
            }
        }
    }

    fun logout() {
        stopAttendance()
        session.clear()
        _staff.value = null
        _kendras.value = emptyList()
        _attendance.value = AttendanceUi()
        activeKendraId = null
        lastPulseMillis = 0L
        _loginError.value = null
    }

    // ------------------------------------------------------------------
    // Manual, face-verified duty start / end
    // ------------------------------------------------------------------
    fun onDuty() {
        val s = _staff.value ?: return
        if (lastFixMock) { _attendance.value = _attendance.value.copy(message = MOCK_MSG); return }
        val lat = lastLat
        val lon = lastLon
        if (lat == null || lon == null) {
            _attendance.value = _attendance.value.copy(
                message = "Waiting for a GPS fix — move near a window or outside and try again."
            )
            return
        }
        viewModelScope.launch {
            try {
                val r = api.checkIn(s.id, lat, lon)
                if (r.status == "checked_in" || r.status == "already") {
                    activeKendraId = r.kendraId ?: activeKendraId
                    lastPulseMillis = System.currentTimeMillis()
                    _attendance.value = _attendance.value.copy(
                        checkInAt = r.checkInAt ?: _attendance.value.checkInAt,
                        dayStatus = r.attendanceStatus ?: _attendance.value.dayStatus,
                        checkInKendra = r.kendraName ?: _attendance.value.checkInKendra,
                        message = r.message ?: "On duty."
                    )
                } else {
                    _attendance.value = _attendance.value.copy(message = r.error ?: "Could not start duty.")
                }
            } catch (e: Exception) {
                _attendance.value = _attendance.value.copy(message = e.message ?: "Could not start duty.")
            }
        }
    }

    fun offDuty() {
        val s = _staff.value ?: return
        if (lastFixMock) { _attendance.value = _attendance.value.copy(message = MOCK_MSG); return }
        val lat = lastLat
        val lon = lastLon
        if (lat == null || lon == null) {
            _attendance.value = _attendance.value.copy(
                message = "Waiting for a GPS fix — move near a window or outside and try again."
            )
            return
        }
        viewModelScope.launch {
            try {
                val r = api.checkOut(s.id, lat, lon, "manual")
                if (r.ok) {
                    _attendance.value = _attendance.value.copy(
                        checkOutAt = r.checkOutAt ?: _attendance.value.checkOutAt,
                        workMinutes = r.workMinutes ?: _attendance.value.workMinutes,
                        message = r.message ?: "Off duty."
                    )
                } else {
                    _attendance.value = _attendance.value.copy(message = r.error ?: "Could not end duty.")
                }
            } catch (e: Exception) {
                _attendance.value = _attendance.value.copy(message = e.message ?: "Could not end duty.")
            }
        }
    }

    fun saveFaceTemplate(template: String, onDone: (Boolean, String?) -> Unit) {
        val s = _staff.value ?: return
        viewModelScope.launch {
            try {
                val r = api.saveFaceTemplate(s.id, template)
                if (r.ok) {
                    val updated = s.copy(faceTemplate = template)
                    session.save(updated, _kendras.value)
                    _staff.value = updated
                    onDone(true, null)
                } else {
                    onDone(false, r.error ?: "Could not save face registration.")
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "Could not save face registration.")
            }
        }
    }

    // ------------------------------------------------------------------
    // Duty attendance loop (foreground only)
    // ------------------------------------------------------------------
    fun startAttendance() {
        val s = _staff.value ?: return
        attendanceJob?.cancel()

        attendanceJob = viewModelScope.launch {
            // Re-fetch the allowed kendras: admin may have moved/edited them since login.
            try {
                val res = api.login(s.mobileNumber)
                if (res.ok && res.staff != null) {
                    session.save(res.staff, res.kendras)
                    _staff.value = res.staff
                    _kendras.value = res.kendras
                }
            } catch (_: Exception) { }

            val allowed = _kendras.value
            if (allowed.isEmpty()) {
                _attendance.value = AttendanceUi(
                    message = "No duty kendra assigned. Ask your admin to assign you a kendra.",
                    clock = GeoUtils.nowClockIST()
                )
                return@launch
            }

            try {
                val t = api.today(s.id)
                activeKendraId = null
                _attendance.value = _attendance.value.copy(
                    checkInAt = t.record?.checkInAt,
                    checkOutAt = t.record?.checkOutAt,
                    dayStatus = t.record?.status,
                    checkInKendra = t.record?.checkInKendra,
                    presentCount = t.presentCount,
                    absentCount = t.absentCount,
                    lastPulseAt = t.lastPulseAt,
                    workMinutes = t.record?.workMinutes,
                    checkOutReason = t.record?.checkOutReason,
                    running = true,
                    clock = GeoUtils.nowClockIST()
                )
                lastPulseMillis = System.currentTimeMillis()
            } catch (_: Exception) { }

            locator.locationUpdates(intervalMs = 15_000L)
                .catch { e ->
                    _attendance.value = _attendance.value.copy(
                        message = "Location error: ${e.message}", running = false
                    )
                }
                .collect { loc -> onFix(s.id, allowed, loc) }
        }
    }

    private fun isMock(loc: Location): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) loc.isMock
        else loc.isFromMockProvider

    private suspend fun onFix(staffId: String, allowed: List<Kendra>, loc: Location) {
        lastFixMock = isMock(loc)
        if (lastFixMock) {
            _attendance.value = _attendance.value.copy(
                clock = GeoUtils.nowClockIST(), running = true, message = MOCK_MSG
            )
            return
        }
        val lat = loc.latitude
        val lon = loc.longitude
        lastLat = lat
        lastLon = lon
        val near = AttendanceRules.nearest(allowed, lat, lon)
        val cur = _attendance.value
        _attendance.value = cur.copy(
            nearestKendraName = near?.kendra?.name,
            distanceM = near?.distM,
            inside = near?.inside,
            clock = GeoUtils.nowClockIST(),
            running = true
        )

        val checkedIn = cur.checkInAt != null
        val checkedOut = cur.checkOutAt != null
        val activeKendra = activeKendraId?.let { id -> allowed.firstOrNull { it.id == id } } ?: near?.kendra
        val action = AttendanceRules.decide(
            allowed, lat, lon, checkedIn, checkedOut, activeKendra,
            GeoUtils.nowMinutesIST(), System.currentTimeMillis() - lastPulseMillis
        )

        when (action) {
            AttendanceAction.PULSE -> try {
                val r = api.pulse(staffId, lat, lon)
                if (r.ok) {
                    lastPulseMillis = System.currentTimeMillis()
                    val auto = r.autoCheckedOut == true
                    _attendance.value = _attendance.value.copy(
                        presentCount = r.presentCount ?: _attendance.value.presentCount,
                        absentCount = r.absentCount ?: _attendance.value.absentCount,
                        lastPulseAt = r.at ?: _attendance.value.lastPulseAt,
                        checkOutAt = if (auto) r.checkOutAt ?: _attendance.value.checkOutAt
                                     else _attendance.value.checkOutAt,
                        workMinutes = if (auto) r.workMinutes ?: _attendance.value.workMinutes
                                      else _attendance.value.workMinutes,
                        checkOutReason = if (auto) r.checkOutReason ?: _attendance.value.checkOutReason
                                         else _attendance.value.checkOutReason,
                        message = when {
                            auto -> r.message ?: "Duty auto-closed: outside the duty area too long."
                            r.inside == true -> "Presence verified at ${r.kendraName ?: "duty kendra"} (${r.at})."
                            else -> "Absent at last verification (${r.at}) — outside duty area. " +
                                    "Duty auto-closes after 30 minutes outside."
                        }
                    )
                }
            } catch (e: Exception) {
                _attendance.value = _attendance.value.copy(message = e.message ?: "Verification failed.")
            }

            AttendanceAction.CHECK_OUT -> try {
                val r = api.checkOut(staffId, lat, lon, "day_end")
                if (r.ok) {
                    _attendance.value = _attendance.value.copy(
                        checkOutAt = r.checkOutAt ?: _attendance.value.checkOutAt,
                        workMinutes = r.workMinutes ?: _attendance.value.workMinutes,
                        checkOutReason = r.checkOutReason ?: _attendance.value.checkOutReason,
                        message = r.message ?: "Checked out."
                    )
                }
            } catch (e: Exception) {
                _attendance.value = _attendance.value.copy(message = e.message ?: "Check-out failed.")
            }

            AttendanceAction.NONE -> {
                val msg = when {
                    checkedOut -> "Duty complete for today."
                    checkedIn -> if (near?.inside == true) "On duty — presence being verified."
                                 else "On duty — currently outside the kendra area."
                    near?.inside == true -> "Inside duty area, outside check-in window."
                    else -> "Outside duty area — ${"%.0f".format(near?.distM ?: 0.0)} m from nearest kendra."
                }
                _attendance.value = _attendance.value.copy(message = msg)
            }
        }
    }

    fun stopAttendance() {
        attendanceJob?.cancel()
        attendanceJob = null
        _attendance.value = _attendance.value.copy(running = false)
    }

    override fun onCleared() {
        super.onCleared()
        stopAttendance()
    }
}
