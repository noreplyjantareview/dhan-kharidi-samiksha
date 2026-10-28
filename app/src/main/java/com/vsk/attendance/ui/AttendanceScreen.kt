package com.vsk.attendance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vsk.attendance.face.FaceCaptureCard
import kotlinx.coroutines.launch

private val Blue = Color(0xFF1D4ED8)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)
private val Amber = Color(0xFFD97706)
private val Slate = Color(0xFF64748B)
private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun AttendanceScreen(vm: MainViewModel) {
    val staff by vm.staff.collectAsState()
    val kendras by vm.kendras.collectAsState()
    val att by vm.attendance.collectAsState()
    val busy by vm.dutyBusy.collectAsState()
    val faceCheck by vm.faceCheck.collectAsState()
    val scope = rememberCoroutineScope()
    var faceMsg by remember(faceCheck?.challengeId) { mutableStateOf<String?>(null) }
    val actionsEnabled = !busy && faceCheck == null && att.running && kendras.isNotEmpty()
    DisposableEffect(vm) { onDispose { vm.cancelFaceCheck() } }

    val nodal = staff?.isNodal == true
    val checkedIn = !nodal && att.checkInAt != null
    val checkedOut = !nodal && att.checkOutAt != null

    val statusColor = when {
        nodal -> if (att.visits.isEmpty()) Blue else Green
        checkedOut -> Slate
        checkedIn -> if (att.dayStatus == "late") Amber else Green
        else -> Blue
    }
    val statusText = when {
        nodal -> if (att.visits.isEmpty()) "NO VISIT YET" else "VISITS RECORDED"
        checkedOut -> "DUTY COMPLETE"
        checkedIn -> if (att.dayStatus == "late") "ON DUTY • LATE" else "ON DUTY"
        else -> "NOT ON DUTY"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF1F5F9))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dhan Kharidi Samiksha", style = MaterialTheme.typography.labelMedium, color = Slate)
                Text(staff?.fullName ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(staff?.mobileNumber ?: "", style = MaterialTheme.typography.bodySmall, color = Slate)
            }
            TextButton(onClick = { vm.logout() }) { Text("Sign out", color = Slate) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Chip(if (nodal) "Nodal officer" else "Fixed duty", if (nodal) Amber else Blue)
            staff?.employeeCode?.takeIf { it.isNotBlank() }?.let { Chip(it, Slate) }
        }

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = statusColor)
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(statusText, color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall)
                Text(att.clock, color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                if (nodal) {
                    StatusRow("Kendras visited", "${att.visits.size}")
                    StatusRow("Time on site", fmtWork(att.visits.sumOf { it.minutes }))
                    StatusRow("Last verified", att.lastPulseAt ?: "—")
                } else {
                    StatusRow("On duty", att.checkInAt?.let { t ->
                        t + (att.checkInKendra?.let { " • $it" } ?: "")
                    } ?: "—")
                    StatusRow("Off duty", att.checkOutAt ?: "—")
                    if (checkedOut) {
                        Spacer(Modifier.height(10.dp))
                        Text("Total working time: ${fmtWork(att.workMinutes)}",
                            color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Live status
        Card(modifier = Modifier.fillMaxWidth(), shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text("Live status", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(att.message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF334155))
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                    Text("Please wait — attendance request in progress.", style = MaterialTheme.typography.bodySmall)
                } else if (!att.running) {
                    TextButton(onClick = { vm.startAttendance() }) { Text("Retry attendance refresh") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    att.nearestKendraName?.let { Chip(it) }
                    att.distanceM?.let { Chip("${"%.0f".format(it)} m") }
                    att.inside?.let {
                        Chip(if (it) "Inside duty area" else "Outside duty area",
                            if (it) Green else Red)
                    }
                }
            }
        }

        // Nodal: today's visit stamps
        if (nodal) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Today's visits", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (att.visits.isEmpty()) {
                        Text("No visit recorded yet. Stand inside a kendra and tap the button below.",
                            style = MaterialTheme.typography.bodySmall, color = Slate)
                    } else {
                        att.visits.forEach { v ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(v.kendra ?: "Kendra",
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("Arrived ${v.checkInAt ?: "—"} • last seen ${v.lastPulseAt ?: "—"}",
                                        style = MaterialTheme.typography.bodySmall, color = Slate)
                                }
                                Text(fmtWork(v.minutes), fontWeight = FontWeight.Bold, color = Green)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Presence verification
        Card(modifier = Modifier.fillMaxWidth(), shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text("Presence verification today", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Present checks", color = Green)
                    Text("${att.presentCount}", fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Absent checks", color = Red)
                    Text("${att.absentCount}", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text("Last verified: ${att.lastPulseAt ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, color = Slate)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Kendras
        Card(modifier = Modifier.fillMaxWidth(), shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (nodal) "Kendras you may visit" else "Your duty kendra", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (kendras.isEmpty()) {
                    Text("None assigned.", color = Slate)
                } else {
                    kendras.forEach { k ->
                        val shift = when {
                            nodal || k.checkInStart == null -> "visit any time"
                            else -> "your shift ${k.checkInStart} to ${k.checkInEnd ?: "—"}, " +
                                    "day ends ${k.dayEnd ?: "—"}"
                        }
                        Text(
                            "• ${k.name} — $shift, verify every ${k.verifyIntervalMin} min, " +
                                    "radius ${k.radiusM} m",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF334155),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Actions
        when {
            nodal -> Button(
                onClick = { vm.prepareFaceCheck("on") { faceMsg = null } },
                enabled = actionsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Record visit here — face check",
                    fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }

            checkedOut -> Card(
                modifier = Modifier.fillMaxWidth(), shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE2E8F0))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Duty closed for today", fontWeight = FontWeight.Bold)
                    Text(
                        if (att.checkOutReason == "left_area")
                            "Duty was auto-closed because you stayed outside the duty area for " +
                                    "30 minutes or more. Work time is stored up to your last verified " +
                                    "presence. On Duty stays locked until tomorrow — contact your admin " +
                                    "if this is wrong."
                        else
                            "Your working time is stored. On Duty stays locked until tomorrow — " +
                                    "contact your admin if this is wrong.",
                        style = MaterialTheme.typography.bodySmall, color = Slate,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            checkedIn -> Button(
                onClick = { vm.prepareFaceCheck("off") { faceMsg = null } },
                enabled = actionsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Off Duty — face check", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }

            else -> Button(
                onClick = { vm.prepareFaceCheck("on") { faceMsg = null } },
                enabled = actionsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("On Duty — face check", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // A dialog exists only after the server has issued an action-bound, short-lived nonce.
    faceCheck?.let { check ->
        Dialog(onDismissRequest = { vm.cancelFaceCheck(); faceMsg = null }) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(
                        when {
                            check.action == "on" && nodal -> "Face check — record visit"
                            check.action == "on" -> "Face check — On Duty"
                            else -> "Face check — Off Duty"
                        },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Look at the camera and blink when the screen asks. " +
                            "Face and presentation checks run on this phone; no camera image is uploaded.",
                        style = MaterialTheme.typography.bodySmall, color = Slate,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    key(check.challengeId) {
                        FaceCaptureCard(
                            enrolling = false,
                            template = check.template,
                            enabled = !busy,
                            buttonColor = if (check.action == "on") Green else Red,
                            onComplete = {
                                scope.launch {
                                    // Ignore completion from a cancelled/replaced capture, even if it arrives late.
                                    if (vm.faceCheck.value !== check || vm.dutyBusy.value) return@launch
                                    faceMsg = null
                                    if (check.action == "on") vm.onDuty(check.challengeId)
                                    else vm.offDuty(check.challengeId)
                                }
                            },
                            onError = { error ->
                                scope.launch { if (vm.faceCheck.value === check) faceMsg = error }
                            }
                        )
                    }
                    faceMsg?.let {
                        Text(it, color = Red, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                    TextButton(
                        onClick = { vm.cancelFaceCheck(); faceMsg = null },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Cancel", color = Slate) }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color = Blue) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.8f))
        Text(value, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

private fun fmtWork(m: Int?): String = when {
    m == null -> "—"
    m < 60 -> "$m min"
    else -> "${m / 60} h ${m % 60} min"
}
