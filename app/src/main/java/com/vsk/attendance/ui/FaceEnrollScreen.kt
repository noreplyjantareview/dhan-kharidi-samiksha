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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vsk.attendance.face.FaceCaptureCard
import kotlinx.coroutines.launch

@Composable
fun FaceEnrollScreen(vm: MainViewModel) {
    val staff by vm.staff.collectAsState()
    val busy by vm.enrollmentBusy.collectAsState()
    val scope = rememberCoroutineScope()
    val staffId = staff?.id
    var msg by remember(staffId) { mutableStateOf<String?>(null) }
    var submitted by remember(staffId) { mutableStateOf(false) }
    var saved by remember(staffId) { mutableStateOf(false) }
    val allowed = staff?.faceEnrollmentAllowed == true

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Supervised face registration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Register ${staff?.fullName ?: "this user"} with your administrator present. Hold the phone at " +
                "eye level in good light, look at the camera and blink when the screen asks. Face matching " +
                "and presentation-attack checks run on this phone. A biometric template is saved to your " +
                "account and encrypted on this phone; camera images are not uploaded. Duty checks use this " +
                "registration.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
        )

        if (!allowed && !saved) {
            Text(
                "Admin approval is required before a new face template can be saved. An older registration " +
                    "cannot be upgraded automatically. Sign out, ask your administrator for an activation " +
                    "code after in-person verification, and sign in with that code.",
                color = Color(0xFFB45309), modifier = Modifier.padding(bottom = 16.dp)
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    FaceCaptureCard(
                        enrolling = true,
                        enabled = allowed && !busy && !submitted && !saved,
                        onComplete = { template ->
                            // The engine owns inference. Marshal completion onto the UI dispatcher.
                            scope.launch {
                                if (submitted || saved || vm.enrollmentBusy.value ||
                                    vm.staff.value?.id != staffId || vm.staff.value?.faceEnrollmentAllowed != true) return@launch
                                if (template == null) {
                                    msg = "Face capture did not return a registration. Please try again."
                                    return@launch
                                }
                                submitted = true
                                msg = null
                                vm.saveFaceTemplate(template) { ok, err ->
                                    saved = ok
                                    submitted = ok
                                    if (!ok) msg = err ?: "Could not save face registration."
                                }
                            }
                        },
                        onError = { error -> scope.launch { if (!saved && !submitted) msg = error } }
                    )
                    if (busy || (submitted && !saved)) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                        Text("Saving registration…", modifier = Modifier.padding(top = 8.dp))
                    }
                    if (saved) Text("Registration saved.", color = Color(0xFF16A34A))
                }
            }
        }
        msg?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFDC2626), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { vm.logout() }) { Text("Sign out") }
    }
}
