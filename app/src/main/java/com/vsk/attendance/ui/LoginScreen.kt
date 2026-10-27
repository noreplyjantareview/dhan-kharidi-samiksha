package com.vsk.attendance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun LoginScreen(vm: MainViewModel) {
    val busy by vm.loginBusy.collectAsState()
    val error by vm.loginError.collectAsState()
    val configError by vm.configError.collectAsState()
    var mobile by rememberSaveable { mutableStateOf("") }
    // One-time activation codes must not be persisted in saved instance state.
    var activationCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1D4ED8))
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Dhan Kharidi Samiksha", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = Color.White)
            Text("Field-staff duty attendance", style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(20.dp)) {
                if (configError != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0E0)),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(configError!!, modifier = Modifier.padding(12.dp), color = Color(0xFFB00020))
                    }
                    Spacer(Modifier.height(16.dp))
                }

                OutlinedTextField(
                    value = mobile,
                    onValueChange = { if (it.length <= 10) mobile = it.filter(Char::isDigit) },
                    label = { Text("Registered mobile number") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = activationCode,
                    onValueChange = { input ->
                        activationCode = input.uppercase(Locale.ROOT)
                            .filter { it in '0'..'9' || it in 'A'..'F' }.take(12)
                    },
                    label = { Text("Admin activation code (optional)") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "New phone or supervised face registration? Ask your admin for a 12-character " +
                        "activation code from the dashboard after in-person verification. " +
                        "Enter it once; an activated phone normally needs only your mobile number, not a daily code. " +
                        "No SMS or OTP is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        vm.login(mobile, activationCode.takeIf { it.isNotBlank() })
                        activationCode = ""
                    },
                    enabled = !busy && configError == null && mobile.length == 10 &&
                        (activationCode.isEmpty() || activationCode.length == 12),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.height(22.dp))
                    else Text("Login", fontSize = MaterialTheme.typography.titleMedium.fontSize)
                }

                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error!!, color = Color(0xFFDC2626))
                }
            }
        }
    }
}
