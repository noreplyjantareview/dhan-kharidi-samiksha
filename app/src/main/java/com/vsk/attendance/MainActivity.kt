package com.vsk.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vsk.attendance.ui.AdminScreen
import com.vsk.attendance.ui.AttendanceScreen
import com.vsk.attendance.ui.LoginScreen
import com.vsk.attendance.ui.MainViewModel
import com.vsk.attendance.ui.theme.VSKTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VSKTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

private fun hasLocationPermission(context: android.content.Context): Boolean =
    LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val staff by vm.staff.collectAsState()
    val context = LocalContext.current
    var hasPerm by remember { mutableStateOf(hasLocationPermission(context)) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPerm = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(staff, hasPerm) {
        val s = staff
        if (s != null && s.role == "staff" && hasPerm) vm.startAttendance()
    }

    when {
        staff == null -> LoginScreen(vm)
        staff?.role == "admin" -> AdminScreen(vm)
        else -> {
            if (hasPerm) AttendanceScreen(vm)
            else PermissionGate { permissionLauncher.launch(LOCATION_PERMISSIONS) }
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Location permission required", fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge)
        Text(
            "This app reads your device GPS to verify you are present at your assigned duty kendra " +
                    "and to auto-mark attendance. Location is only used while the app is open.",
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Grant location permission")
        }
    }
}
