package com.vsk.attendance.ui

import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vsk.attendance.data.ActionResult
import com.vsk.attendance.data.AttendanceApiException
import com.vsk.attendance.data.Kendra
import com.vsk.attendance.data.SessionStorageException
import com.vsk.attendance.data.SessionStore
import com.vsk.attendance.data.Staff
import com.vsk.attendance.data.SupabaseApi
import com.vsk.attendance.data.Visit
import com.vsk.attendance.face.FaceTemplate
import com.vsk.attendance.location.AttendanceAction
import com.vsk.attendance.location.AttendanceRules
import com.vsk.attendance.location.LocationHelper
import com.vsk.attendance.util.GeoUtils
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AttendanceUi(
    val checkInAt: String? = null,
    val checkOutAt: String? = null,
    val dayStatus: String? = null,
    val checkInKendra: String? = null,
    val nearestKendraName: String? = null,
    val distanceM: Double? = null,
    val inside: Boolean? = null,
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val lastPulseAt: String? = null,
    val workMinutes: Int? = null,
    val checkOutReason: String? = null,
    val nodal: Boolean = false,
    val visits: List<Visit> = emptyList(),
    val message: String = "Waiting for location…",
    val clock: String = "",
    val running: Boolean = false
)

/** A single-use UI context, not proof of a biometric match. Matching belongs to FaceCaptureCard. */
class PreparedFaceCheck internal constructor(
    val challengeId: String,
    val action: String,
    val template: String,
    internal val staffId: String,
    internal val localRevision: String?,
    internal val serverRevision: String?,
    internal val expiresAt: Long,
    internal val day: Long,
    internal val checkInAt: String?,
    internal val checkOutAt: String?
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val session = SessionStore(app)
    private val api = SupabaseApi { session.deviceToken() }
    private val locator = LocationHelper(app)

    private val _staff = MutableStateFlow<Staff?>(null)
    val staff: StateFlow<Staff?> = _staff.asStateFlow()
    private val _kendras = MutableStateFlow<List<Kendra>>(emptyList())
    val kendras: StateFlow<List<Kendra>> = _kendras.asStateFlow()
    private val _loginBusy = MutableStateFlow(false)
    val loginBusy = _loginBusy.asStateFlow()
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError = _loginError.asStateFlow()
    private val _attendance = MutableStateFlow(AttendanceUi())
    val attendance = _attendance.asStateFlow()
    private val _configError = MutableStateFlow<String?>(null)
    val configError = _configError.asStateFlow()
    private val _dutyBusy = MutableStateFlow(false)
    val dutyBusy = _dutyBusy.asStateFlow()
    private val _enrollmentBusy = MutableStateFlow(false)
    val enrollmentBusy = _enrollmentBusy.asStateFlow()
    private val _faceCheck = MutableStateFlow<PreparedFaceCheck?>(null)
    val faceCheck = _faceCheck.asStateFlow()

    private var attendanceJob: Job? = null
    private var dutyJob: Job? = null
    private var loginJob: Job? = null
    private var enrollmentJob: Job? = null
    private var expiryJob: Job? = null
    private var challengeGeneration = 0L
    private var busyGeneration = 0L
    private var activeKendraId: String? = null
    private var lastPulseElapsedMs = 0L
    private var lastFix: Location? = null
    private var preserveMessage = false
    private var messageExpiresAt = 0L

    init {
        if (!api.configured) {
            _configError.value = "Attendance service is not configured. Ask your administrator for a configured app."
        }
        session.loadSession()?.let { (savedStaff, savedKendras) ->
            _staff.value = savedStaff
            _kendras.value = savedKendras
            _attendance.value = AttendanceUi(nodal = savedStaff.isNodal)
        }
    }

    fun login(mobile: String, activationCode: String? = null) {
        if (_loginBusy.value || _staff.value != null) return
        val number = mobile.trim()
        val code = activationCode?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (!Regex("[0-9]{10}").matches(number)) {
            _loginError.value = "Enter your registered 10-digit mobile number."
            return
        }
        if (code != null && !Regex("[0-9A-F]{12}").matches(code)) {
            _loginError.value = "The admin activation code must contain 12 hexadecimal characters."
            return
        }
        _loginBusy.value = true
        _loginError.value = null
        loginJob = viewModelScope.launch {
            try {
                val res = api.login(number, code)
                currentCoroutineContext().ensureActive()
                val loggedIn = res.staff
                if (res.ok && loggedIn != null) {
                    val updated = loggedIn.copy(faceRevision = res.faceRevision ?: loggedIn.faceRevision)
                    session.save(updated, res.kendras)
                    _kendras.value = res.kendras
                    _attendance.value = AttendanceUi(nodal = updated.isNodal)
                    _staff.value = updated
                    // The resumed app lifecycle starts tracking only after enrollment gating.
                } else {
                    _loginError.value = loginMessage(res.reason, res.error)
                }
            } catch (e: Exception) {
                _loginError.value = failureMessage(e, "Login failed. Please try again.")
            } finally {
                _loginBusy.value = false
            }
        }
    }

    fun logout() {
        loginJob?.cancel()
        enrollmentJob?.cancel()
        stopAttendance()
        session.clear()
        _staff.value = null
        _kendras.value = emptyList()
        _attendance.value = AttendanceUi()
        _enrollmentBusy.value = false
        _loginBusy.value = false
        activeKendraId = null
        lastPulseElapsedMs = 0L
        preserveMessage = false
        _loginError.value = null
    }

    private fun readyStaff(): Staff? = _staff.value?.takeIf {
        it.role == "staff" && !it.faceEnrollmentAllowed && FaceTemplate.isCurrent(it.faceTemplate)
    }

    private fun actionAllowed(action: String, s: Staff): Boolean {
        val att = _attendance.value
        return _kendras.value.isNotEmpty() && att.running && when (action) {
            "on" -> s.isNodal || (att.checkInAt == null && att.checkOutAt == null)
            "off" -> !s.isNodal && att.checkInAt != null && att.checkOutAt == null
            else -> false
        }
    }

    fun prepareFaceCheck(action: String, onReady: (PreparedFaceCheck) -> Unit) {
        if (_dutyBusy.value || _faceCheck.value != null) return
        val s = readyStaff() ?: run {
            showMessage("Supervised face registration is required before marking duty.")
            return
        }
        if (!actionAllowed(action, s)) {
            showMessage("This duty action is not available. Wait for attendance to refresh and try again.")
            return
        }
        val operation = beginBusy()
        showMessage("Preparing a secure face check…")
        val generation = ++challengeGeneration
        dutyJob = viewModelScope.launch {
            try {
                if (!fresh(lastFix)) {
                    val fix = withTimeoutOrNull(15_000L) { locator.currentLocation() }
                    if (fix != null) updateLocation(fix)
                }
                if (!usableLocation()) return@launch
                // Count network time against the challenge lifetime, never extend the server expiry.
                val requestedAt = SystemClock.elapsedRealtime()
                val r = api.beginFaceCheck(s.id, action)
                currentCoroutineContext().ensureActive()
                if (generation != challengeGeneration || readyStaff() != s || !actionAllowed(action, s)) return@launch
                if (!r.ok) {
                    showMessage(r.error ?: r.reason ?: "Could not prepare a face check.")
                    return@launch
                }
                if (s.faceRevision.isNullOrBlank() || s.faceRevision != r.faceRevision) {
                    logout()
                    _loginError.value = "Your face registration changed. Sign in again to refresh it."
                    return@launch
                }
                val id = r.challengeId
                val expiresAt = requestedAt + r.expiresIn.coerceAtMost(90).toLong() * 1_000L
                if (id.isNullOrBlank() || r.faceRevision.isNullOrBlank()) {
                    showMessage("The face challenge is invalid. Please try again.")
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (generation != challengeGeneration || readyStaff() != s || !actionAllowed(action, s)) return@launch
                if (expiresAt <= SystemClock.elapsedRealtime()) {
                    showMessage("Face check expired. Start a new face capture.")
                    return@launch
                }
                if (!usableLocation()) return@launch
                val check = PreparedFaceCheck(
                    id, action, s.faceTemplate!!, s.id, s.faceRevision, r.faceRevision,
                    expiresAt, dayIST(), _attendance.value.checkInAt, _attendance.value.checkOutAt
                )
                _faceCheck.value = check
                expiryJob = viewModelScope.launch {
                    delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    if (_faceCheck.value === check) {
                        cancelFaceCheck()
                        showMessage("Face check expired. Start a new face capture.")
                    }
                }
                onReady(check)
            } catch (e: Exception) {
                showMessage(failureMessage(e, "Could not prepare a face check."))
            } finally {
                endBusy(operation)
            }
        }
    }

    fun cancelFaceCheck() {
        val hadCheck = _faceCheck.value != null
        challengeGeneration++
        expiryJob?.cancel()
        expiryJob = null
        _faceCheck.value = null
        if (hadCheck || _attendance.value.message == "Preparing a secure face check…") {
            preserveMessage = false
            _attendance.value = _attendance.value.copy(message = "Face check closed. Start a new check when ready.")
        }
    }

    fun onDuty(challengeId: String) = submitDuty("on", challengeId)
    fun offDuty(challengeId: String) = submitDuty("off", challengeId)

    private fun submitDuty(action: String, challengeId: String) {
        if (_dutyBusy.value) return
        val check = _faceCheck.value
        val s = readyStaff()
        if (check == null || s == null || check.challengeId != challengeId || check.action != action ||
            check.staffId != s.id || check.template != s.faceTemplate || check.localRevision != s.faceRevision ||
            (s.faceRevision != null && check.serverRevision != s.faceRevision) ||
            check.day != dayIST() || check.expiresAt <= SystemClock.elapsedRealtime() ||
            check.checkInAt != _attendance.value.checkInAt || check.checkOutAt != _attendance.value.checkOutAt ||
            !actionAllowed(action, s)) {
            cancelFaceCheck()
            showMessage("The duty action or face registration changed. Start a new face capture.")
            return
        }
        // Consume before any suspension, including when GPS became stale during capture.
        cancelFaceCheck()
        if (!usableLocation()) {
            requestFreshLocation()
            return
        }
        val fix = lastFix!!
        val operation = beginBusy()
        showMessage(if (action == "on") "Recording duty…" else "Ending duty…")
        dutyJob = viewModelScope.launch {
            try {
                val r = if (action == "on") api.checkIn(s.id, fix.latitude, fix.longitude, challengeId)
                    else api.checkOut(s.id, fix.latitude, fix.longitude, "manual", challengeId)
                currentCoroutineContext().ensureActive()
                if (_staff.value?.id != s.id) return@launch
                if (!r.ok) {
                    showMessage(r.error ?: r.reason ?: "Could not record duty. Start a new face check to retry.")
                    return@launch
                }
                if (action == "off") {
                    applyCheckout(r)
                    showMessage(r.message ?: "Off duty.")
                } else if (s.isNodal) {
                    activeKendraId = r.kendraId ?: activeKendraId
                    lastPulseElapsedMs = SystemClock.elapsedRealtime()
                    showMessage(r.message ?: "Visit recorded.")
                    val today = api.today(s.id)
                    if (today.ok) _attendance.value = _attendance.value.copy(visits = today.visits)
                    else showMessage(today.error ?: today.reason ?: "Visit recorded; could not refresh today's visits.")
                } else if (r.status == "checked_in" || r.status == "already") {
                    activeKendraId = r.kendraId ?: activeKendraId
                    lastPulseElapsedMs = SystemClock.elapsedRealtime()
                    _attendance.value = _attendance.value.copy(
                        checkInAt = r.checkInAt ?: _attendance.value.checkInAt,
                        dayStatus = r.attendanceStatus ?: _attendance.value.dayStatus,
                        checkInKendra = r.kendraName ?: _attendance.value.checkInKendra
                    )
                    showMessage(r.message ?: "On duty.")
                } else showMessage(r.error ?: r.message ?: "Could not start duty.")
            } catch (e: Exception) {
                val message = failureMessage(e, "Could not confirm the duty result.")
                if (_staff.value?.id == s.id) {
                    // A lost response may still have committed on the server. Refresh before retrying.
                    stopAttendance()
                    showMessage("$message Refresh attendance before another face check.")
                }
            } finally {
                endBusy(operation)
            }
        }
    }

    private fun requestFreshLocation() {
        val operation = beginBusy()
        dutyJob = viewModelScope.launch {
            try {
                val fix = withTimeoutOrNull(15_000L) { locator.currentLocation() }
                if (fix != null) updateLocation(fix)
                if (usableLocation()) showMessage("GPS refreshed. Start a new face capture to mark duty.")
            } catch (e: Exception) {
                showMessage(failureMessage(e, "Could not refresh GPS. Move outside and try again."))
            } finally {
                endBusy(operation)
            }
        }
    }

    fun saveFaceTemplate(template: String, onDone: (Boolean, String?) -> Unit) {
        if (_enrollmentBusy.value) return
        val s = _staff.value
        if (s == null || s.role != "staff" || !s.faceEnrollmentAllowed) {
            onDone(false, "Admin approval is required. Sign out and obtain an activation code after in-person verification.")
            return
        }
        if (!FaceTemplate.isCurrent(template)) {
            onDone(false, "A new supervised face capture is required.")
            return
        }
        _enrollmentBusy.value = true
        cancelFaceCheck()
        enrollmentJob = viewModelScope.launch {
            try {
                val r = try {
                    api.saveFaceTemplate(s.id, template)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SessionStorageException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (_staff.value != s) return@launch
                if (r?.ok == true && !r.faceRevision.isNullOrBlank()) {
                    val updated = s.copy(faceTemplate = template, faceEnrollmentAllowed = false, faceRevision = r.faceRevision)
                    session.save(updated, _kendras.value)
                    _staff.value = updated
                    onDone(true, null)
                } else {
                    // A committed enrollment can outlive its lost HTTP response; reconcile before another capture.
                    val latest = api.login(s.mobileNumber)
                    currentCoroutineContext().ensureActive()
                    if (_staff.value != s) return@launch
                    val refreshed = latest.staff
                    if (!latest.ok || refreshed == null || refreshed.id != s.id) {
                        logout()
                        _loginError.value = loginMessage(latest.reason, latest.error)
                        return@launch
                    }
                    val updated = refreshed.copy(faceRevision = latest.faceRevision ?: refreshed.faceRevision)
                    session.save(updated, latest.kendras)
                    _kendras.value = latest.kendras
                    _staff.value = updated
                    if (!updated.faceEnrollmentAllowed && FaceTemplate.isCurrent(updated.faceTemplate)) {
                        onDone(true, null)
                    } else {
                        onDone(false, r?.error ?: "Registration was not saved. Please capture again.")
                    }
                }
            } catch (e: Exception) {
                val message = failureMessage(e, "Could not confirm face registration. Sign in again to refresh it.")
                logout()
                _loginError.value = message
            } finally {
                _enrollmentBusy.value = false
            }
        }
    }

    /** Idempotent, foreground-only; staff refresh copies must not restart this job. */
    fun startAttendance() {
        val s = readyStaff() ?: return
        if (attendanceJob?.isActive == true || _dutyBusy.value || _faceCheck.value != null) return
        val operation = beginBusy()
        attendanceJob = viewModelScope.launch {
            try {
                showMessage("Refreshing attendance…")
                val login = api.login(s.mobileNumber)
                currentCoroutineContext().ensureActive()
                val refreshed = login.staff
                if (!login.ok || refreshed == null || refreshed.id != s.id) {
                    logout()
                    _loginError.value = loginMessage(login.reason, login.error)
                    return@launch
                }
                val updated = refreshed.copy(faceRevision = login.faceRevision ?: refreshed.faceRevision)
                session.save(updated, login.kendras)
                if (s.faceTemplate != updated.faceTemplate || s.faceRevision != updated.faceRevision) cancelFaceCheck()
                _kendras.value = login.kendras
                _staff.value = updated
                if (readyStaff() == null) return@launch
                if (login.kendras.isEmpty()) {
                    showMessage("No duty kendra assigned. Ask your admin to assign you a kendra.")
                    return@launch
                }
                val today = api.today(s.id)
                if (!today.ok) {
                    showMessage(today.error ?: today.reason ?: "Could not load today's attendance. Reopen the app to retry.")
                    return@launch
                }
                activeKendraId = null
                _attendance.value = _attendance.value.copy(
                    checkInAt = today.record?.checkInAt,
                    checkOutAt = today.record?.checkOutAt,
                    dayStatus = today.record?.status,
                    checkInKendra = today.record?.checkInKendra,
                    presentCount = today.presentCount,
                    absentCount = today.absentCount,
                    lastPulseAt = today.lastPulseAt,
                    workMinutes = today.record?.workMinutes,
                    checkOutReason = today.record?.checkOutReason,
                    nodal = updated.isNodal, visits = today.visits,
                    running = true, clock = GeoUtils.nowClockIST(), message = "Waiting for a fresh GPS fix…"
                )
                preserveMessage = false
            } catch (e: Exception) {
                showMessage(failureMessage(e, "Could not refresh attendance. Reopen the app to retry."))
                return@launch
            } finally {
                endBusy(operation)
            }
            locator.locationUpdates(intervalMs = 15_000L)
                .catch { e ->
                    if (e is CancellationException) throw e
                    cancelFaceCheck()
                    showMessage("Location unavailable. Check location permissions and reopen the app.")
                    _attendance.value = _attendance.value.copy(running = false)
                }
                .collect { loc -> onFix(s.id, _kendras.value, loc) }
        }
    }

    private fun isMock(loc: Location): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) loc.isMock
        else loc.isFromMockProvider

    private fun fresh(loc: Location?): Boolean {
        if (loc == null || loc.elapsedRealtimeNanos <= 0L) return false
        return SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos in 0L..60_000_000_000L
    }

    private fun usableLocation(): Boolean {
        if (lastFix?.let(::isMock) == true) {
            showMessage(MOCK_MSG)
            return false
        }
        if (!fresh(lastFix)) {
            showMessage("GPS is missing or older than 60 seconds. Refresh location, then start a new face capture.")
            return false
        }
        return true
    }

    private fun updateLocation(loc: Location) {
        lastFix = Location(loc)
        if (isMock(loc)) {
            cancelFaceCheck()
            showMessage(MOCK_MSG)
            return
        }
        if (!fresh(loc)) return
        val near = AttendanceRules.nearest(_kendras.value, loc.latitude, loc.longitude)
        _attendance.value = _attendance.value.copy(
            nearestKendraName = near?.kendra?.name, distanceM = near?.distM, inside = near?.inside,
            clock = GeoUtils.nowClockIST()
        )
    }

    private suspend fun onFix(staffId: String, allowed: List<Kendra>, loc: Location) {
        updateLocation(loc)
        if (isMock(loc) || !fresh(loc) || readyStaff()?.id != staffId) return
        if (_dutyBusy.value || _faceCheck.value != null) return
        val lat = loc.latitude
        val lon = loc.longitude
        val near = AttendanceRules.nearest(allowed, lat, lon)
        val cur = _attendance.value
        val checkedIn = cur.checkInAt != null
        val checkedOut = cur.checkOutAt != null
        val nodal = cur.nodal
        val activeKendra = activeKendraId?.let { id -> allowed.firstOrNull { it.id == id } } ?: near?.kendra
        val action = AttendanceRules.decide(
            allowed, lat, lon, checkedIn, checkedOut, activeKendra,
            GeoUtils.nowMinutesIST(),
            if (lastPulseElapsedMs == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastPulseElapsedMs,
            nodal
        )
        if (action == AttendanceAction.NONE) {
            if (preserveMessage && SystemClock.elapsedRealtime() < messageExpiresAt) return
            preserveMessage = false
            val visitOpenHere = nodal && near?.inside == true && cur.visits.any { it.kendra == near.kendra.name }
            _attendance.value = cur.copy(message = when {
                nodal && near?.inside != true -> "Outside duty area — ${"%.0f".format(near?.distM ?: 0.0)} m from nearest kendra."
                nodal && visitOpenHere -> "Visit open here — presence being verified."
                nodal -> "Inside ${near?.kendra?.name} — tap On Duty to record your visit."
                checkedOut -> "Duty complete for today."
                checkedIn -> if (near?.inside == true) "On duty — presence being verified." else "On duty — currently outside the kendra area."
                near?.inside == true -> "Inside duty area — tap On Duty during your shift check-in time."
                else -> "Outside duty area — ${"%.0f".format(near?.distM ?: 0.0)} m from nearest kendra."
            })
            return
        }
        val operation = beginBusy()
        try {
            when (action) {
                AttendanceAction.PULSE -> {
                    val r = api.pulse(staffId, lat, lon)
                    if (r.ok) {
                        lastPulseElapsedMs = SystemClock.elapsedRealtime()
                        if (r.autoCheckedOut == true) applyCheckout(r)
                        _attendance.value = _attendance.value.copy(
                            presentCount = r.presentCount ?: _attendance.value.presentCount,
                            absentCount = r.absentCount ?: _attendance.value.absentCount,
                            lastPulseAt = r.at ?: _attendance.value.lastPulseAt
                        )
                        showMessage(when {
                            r.autoCheckedOut == true -> r.message ?: "Duty auto-closed: outside the duty area too long."
                            nodal -> r.message ?: if (r.inside == true) "Presence verified at ${r.kendraName ?: "kendra"} (${r.at})." else "Outside duty area (${r.at})."
                            r.inside == true -> "Presence verified at ${r.kendraName ?: "duty kendra"} (${r.at})."
                            else -> "Absent at last verification (${r.at}) — outside duty area. Duty auto-closes after 30 minutes outside."
                        })
                    } else showMessage(r.error ?: r.reason ?: "Presence verification failed.")
                }
                AttendanceAction.CHECK_OUT -> {
                    // The only challenge-free path is the existing time-rule-driven day end.
                    val r = api.checkOut(staffId, lat, lon, "day_end")
                    if (r.ok) {
                        applyCheckout(r)
                        showMessage(r.message ?: "Checked out at day end.")
                    } else showMessage(r.error ?: r.reason ?: "Day-end check-out failed.")
                }
                AttendanceAction.NONE -> Unit
            }
        } catch (e: Exception) {
            showMessage(failureMessage(e, "Attendance update failed. Please try again."))
        } finally {
            endBusy(operation)
        }
    }

    private fun beginBusy(): Long {
        _dutyBusy.value = true
        return ++busyGeneration
    }

    private fun endBusy(operation: Long) {
        // A cancelled old coroutine must not unlock a newer in-flight operation.
        if (operation == busyGeneration) _dutyBusy.value = false
    }

    private fun applyCheckout(r: ActionResult) {
        _attendance.value = _attendance.value.copy(
            checkOutAt = r.checkOutAt ?: _attendance.value.checkOutAt,
            workMinutes = r.workMinutes ?: _attendance.value.workMinutes,
            checkOutReason = r.checkOutReason ?: _attendance.value.checkOutReason
        )
    }

    private fun showMessage(message: String) {
        preserveMessage = true
        messageExpiresAt = SystemClock.elapsedRealtime() + 20_000L
        _attendance.value = _attendance.value.copy(message = message)
    }

    private fun failureMessage(e: Exception, fallback: String): String {
        if (e is CancellationException) throw e
        if (e is SessionStorageException) {
            logout()
            _loginError.value = e.message
            return e.message ?: fallback
        }
        return if (e is AttendanceApiException) e.message ?: fallback else fallback
    }

    private fun loginMessage(reason: String?, error: String?): String = when (reason) {
        "activation_required" -> "This device needs admin activation. Obtain a one-time code after in-person verification."
        "invalid_activation" -> "The activation code is invalid, expired or locked. Ask your admin for a new code."
        else -> error ?: "Login failed. Please try again."
    }

    fun stopAttendance() {
        cancelFaceCheck()
        busyGeneration++
        dutyJob?.cancel()
        dutyJob = null
        attendanceJob?.cancel()
        attendanceJob = null
        lastFix = null
        _dutyBusy.value = false
        _attendance.value = _attendance.value.copy(running = false)
    }

    override fun onCleared() {
        stopAttendance()
        super.onCleared()
    }

    private companion object {
        const val MOCK_MSG = "Mock location detected — turn off fake-GPS apps to mark duty."
        fun dayIST(): Long = (System.currentTimeMillis() + 19_800_000L) / 86_400_000L
    }
}
