package com.vsk.attendance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AttendanceScreen(vm: MainViewModel) {
    val staff by vm.staff.collectAsState()
    val kendras by vm.kendras.collectAsState()
    val att by vm.attendance.collectAsState()

    val statusColor = when {
        att.checkOutAt != null -> Color(0xFF616161)
        att.checkInAt != null -> Color(0xFF2E7D32)
        else -> Color(0xFFF9A825)
    }
    val statusText = when {
        att.checkOutAt != null -> "DUTY COMPLETE"
        att.checkInAt != null -> if (att.dayStatus == "late") "ON DUTY (LATE)" else "ON DUTY"
        else -> "NOT CHECKED IN"
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(staff?.fullName ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (staff?.dutyType == "multi") "Multi-location duty" else "Fixed-duty",
                    style = MaterialTheme.typography.bodyMedium, color = Color.Gray
                )
            }
            OutlinedButton(onClick = { vm.logout() }) { Text("Logout") }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f))
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(statusText, color = statusColor, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(att.clock, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                InfoRow("Check-in", att.checkInAt?.let { "${it}${att.checkInKendra?.let { k -> "  •  $k" } ?: ""}" } ?: "—")
                InfoRow("Check-out", att.checkOutAt ?: "—")
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Live status", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(att.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                att.nearestKendraName?.let {
                    Text("Nearest kendra: $it", style = MaterialTheme.typography.bodySmall)
                }
                att.distanceM?.let {
                    Text("Distance: ${"%.0f".format(it)} m", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                att.inside?.let {
                    Text(
                        if (it) "• Inside duty area" else "• Outside duty area",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it) Color(0xFF2E7D32) else Color(0xFFB00020)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Presence verification today", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Present checks", color = Color(0xFF2E7D32))
                    Text("${att.presentCount}", fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Absent checks", color = Color(0xFFB00020))
                    Text("${att.absentCount}", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text("Last verified: ${att.lastPulseAt ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Your duty kendra(s)", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (kendras.isEmpty()) {
                    Text("None assigned.", color = Color.Gray)
                } else {
                    kendras.forEach { k ->
                        Text(
                            "• ${k.name} — window ${k.checkInStart}–${k.checkInEnd}, day ends ${k.dayEnd}, " +
                                    "verify every ${k.verifyIntervalMin} min, radius ${k.radiusM} m",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!att.running && kendras.isNotEmpty()) {
            Button(onClick = { vm.startAttendance() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Start duty tracking")
            }
        } else if (att.running) {
            Button(
                onClick = { vm.stopAttendance() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF616161)),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("Pause duty tracking") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
