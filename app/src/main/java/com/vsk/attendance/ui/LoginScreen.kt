package com.vsk.attendance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(vm: MainViewModel) {
    val busy by vm.loginBusy.collectAsState()
    val error by vm.loginError.collectAsState()
    val configError by vm.configError.collectAsState()
    var mobile by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Dhan Kharidi Samiksha", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Field-staff duty attendance", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Spacer(Modifier.height(28.dp))

        if (configError != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0E0)), modifier = Modifier.fillMaxWidth()) {
                Text(configError!!, modifier = Modifier.padding(12.dp), color = Color(0xFFB00020))
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = mobile,
            onValueChange = { if (it.length <= 10) mobile = it.filter(Char::isDigit) },
            label = { Text("Registered mobile number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { vm.login(mobile) },
            enabled = !busy && mobile.length == 10,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.height(22.dp))
            else Text("Login", fontSize = MaterialTheme.typography.titleMedium.fontSize)
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Color(0xFFB00020))
        }
    }
}
