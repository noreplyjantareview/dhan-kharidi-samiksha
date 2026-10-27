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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vsk.attendance.face.FaceCaptureCard
import com.vsk.attendance.face.FaceGeometry

private val Blue = Color(0xFF1D4ED8)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)
private val Amber = Color(0xFFD97706)
private val Slate = Color(0xFF64748B)
private val CardShape = RoundedCornerShape(20.dp)

private enum class FaceAction { ON, OFF }

@Composable
fun AttendanceScreen(vm: MainViewModel) {
    val staff by vm.staff.collectAsState()
    val kendras by vm.kendras.collectAsState()
    val att by vm.attendance.collectAsState()
    var faceAction by remember { mutableStateOf<FaceAction?>(null) }
    var faceMsg by remember { mutableStateOf<String?>(null) }

    val checkedIn = att.checkInAt != null
    val checkedOut = att.checkOutAt != null

    val statusColor = when {
        checkedOut -> Slate
        checkedIn -> if (att.dayStatus == "late") Amber else Green
        else -> Blue
    }
    val statusText = when {
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
            }
            TextButton(onClick = { vm.logout() }) { Text("Sign out", color = Slate) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Chip(if (staff?.dutyType == "multi") "Multi-location duty" else "Fixed duty")
            Chip(staff?.mobileNumber ?: "")
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

        Spacer(Modifier.height(12.dp))

        // Live status
        Card(modifier = Modifier.fillMaxWidth(), shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text("Live status", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(att.message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF334155))
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
                Text("Your duty kendra(s)", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (kendras.isEmpty()) {
                    Text("None assigned.", color = Slate)
                } else {
                    kendras.forEach { k ->
                        Text(
                            "• ${k.name} — check-in ${k.checkInStart} to ${k.checkInEnd}, " +
                                    "day ends ${k.dayEnd}, verify every ${k.verifyIntervalMin} min, " +
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
            checkedOut -> Card(
                modifier = Modifier.fillMaxWidth(), shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE2E8F0))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Duty closed for today", fontWeight = FontWeight.Bold)
                    Text(
                        "Your working time is stored. On Duty stays locked until tomorrow — " +
                                "contact your admin if this is wrong.",
                        style = MaterialTheme.typography.bodySmall, color = Slate,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            checkedIn -> Button(
                onClick = { faceMsg = null; faceAction = FaceAction.OFF },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Off Duty — face check", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }

            else -> Button(
                onClick = { faceMsg = null; faceAction = FaceAction.ON },
                enabled = kendras.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("On Duty — face check", fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // Face verification dialog
    faceAction?.let { action ->
        val template = staff?.faceTemplate
        Dialog(onDismissRequest = { faceAction = null; faceMsg = null }) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (action == FaceAction.ON) "Face check — On Duty" else "Face check — Off Duty",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Look straight at the camera, then capture. The face must match your registration.",
                        style = MaterialTheme.typography.bodySmall, color = Slate,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    if (template == null) {
                        Text("No face registration found — sign out and register again.", color = Red)
                    } else {
                        FaceCaptureCard(
                            buttonLabel = "Capture & verify",
                            buttonColor = if (action == FaceAction.ON) Green else Red,
                            onFace = { face, w, h, bitmap ->
                                val issue = FaceGeometry.qualityIssue(face, w, h)
                                if (issue != null) { faceMsg = issue; return@FaceCaptureCard }
                                val res = FaceGeometry.match(template, face, bitmap)
                                if (res.ok) {
                                    faceAction = null
                                    faceMsg = null
                                    if (action == FaceAction.ON) vm.onDuty() else vm.offDuty()
                                } else {
                                    faceMsg = res.message ?: "Face does not match — try again."
                                }
                            },
                            onError = { faceMsg = it }
                        )
                    }
                    if (faceMsg != null) {
                        Text(faceMsg!!, color = Red, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                    TextButton(
                        onClick = { faceAction = null; faceMsg = null },
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

private fun fmtWork(m: Int?): String =
    if (m == null) "—" else "${m / 60} h ${m % 60} min"
