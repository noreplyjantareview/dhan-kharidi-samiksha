package com.vsk.attendance.ui

import android.app.Application
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
    val message: String = "Waiting for location…",
    val clock: String = "",
    val running: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

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
                .collect { loc -> onFix(s.id, allowed, loc.latitude, loc.longitude) }
        }
    }

    private suspend fun onFix(staffId: String, allowed: List<Kendra>, lat: Double, lon: Double) {
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
            AttendanceAction.CHECK_IN -> try {
                val r = api.checkIn(staffId, lat, lon)
                if (r.status == "checked_in" || r.status == "already") {
                    activeKendraId = r.kendraId ?: activeKendraId
                    lastPulseMillis = System.currentTimeMillis()
                    _attendance.value = _attendance.value.copy(
                        checkInAt = r.checkInAt ?: _attendance.value.checkInAt,
                        dayStatus = r.attendanceStatus ?: _attendance.value.dayStatus,
                        checkInKendra = r.kendraName ?: _attendance.value.checkInKendra,
                        message = r.message ?: "Checked in."
                    )
                } else {
                    _attendance.value = _attendance.value.copy(message = r.error ?: "Check-in failed.")
                }
            } catch (e: Exception) {
                _attendance.value = _attendance.value.copy(message = e.message ?: "Check-in failed.")
            }

            AttendanceAction.PULSE -> try {
                val r = api.pulse(staffId, lat, lon)
                if (r.ok) {
                    lastPulseMillis = System.currentTimeMillis()
                    _attendance.value = _attendance.value.copy(
                        presentCount = r.presentCount ?: _attendance.value.presentCount,
                        absentCount = r.absentCount ?: _attendance.value.absentCount,
                        lastPulseAt = r.at ?: _attendance.value.lastPulseAt,
                        message = if (r.inside == true)
                            "Presence verified at ${r.kendraName ?: "duty kendra"} (${r.at})."
                        else
                            "Absent at last verification (${r.at}) — outside duty area."
                    )
                }
            } catch (e: Exception) {
                _attendance.value = _attendance.value.copy(message = e.message ?: "Verification failed.")
            }

            AttendanceAction.CHECK_OUT -> try {
                val r = api.checkOut(staffId, lat, lon)
                if (r.ok) {
                    _attendance.value = _attendance.value.copy(
                        checkOutAt = r.checkOutAt ?: _attendance.value.checkOutAt,
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
