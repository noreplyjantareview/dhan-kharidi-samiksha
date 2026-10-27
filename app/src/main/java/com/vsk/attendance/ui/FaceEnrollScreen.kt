package com.vsk.attendance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vsk.attendance.face.FaceCaptureCard
import com.vsk.attendance.face.FaceGeometry

@Composable
fun FaceEnrollScreen(vm: MainViewModel) {
    val staff by vm.staff.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Face registration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "One-time setup for ${staff?.fullName ?: "this user"}. Hold the phone at face level in good " +
                "light and capture. Only the geometry of your face is stored on your account — no photo " +
                "is kept. Every On Duty / Off Duty after this must match this registration.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                FaceCaptureCard(
                    buttonLabel = if (busy) "Saving registration…" else "Capture my face",
                    enabled = !busy,
                    onFace = { face, w, h ->
                        val issue = FaceGeometry.qualityIssue(face, w, h)
                        if (issue != null) { msg = issue; return@FaceCaptureCard }
                        val template = FaceGeometry.buildTemplate(face)
                        if (template == null) {
                            msg = "Face not clear enough — try again in better light."
                            return@FaceCaptureCard
                        }
                        busy = true
                        msg = null
                        vm.saveFaceTemplate(template) { ok, err ->
                            busy = false
                            if (!ok) msg = err ?: "Could not save face registration."
                        }
                    },
                    onError = { msg = it }
                )
            }
        }

        if (msg != null) {
            Spacer(Modifier.height(12.dp))
            Text(msg!!, color = Color(0xFFDC2626), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
