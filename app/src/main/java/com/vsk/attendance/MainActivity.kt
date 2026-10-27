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
import com.vsk.attendance.face.FaceGeometry
import com.vsk.attendance.ui.AttendanceScreen
import com.vsk.attendance.ui.FaceEnrollScreen
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

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.CAMERA
)

private fun hasLocationPermission(context: android.content.Context): Boolean =
    REQUIRED_PERMISSIONS.dropLast(1).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val staff by vm.staff.collectAsState()
    val context = LocalContext.current
    var hasPerm by remember {
        mutableStateOf(hasLocationPermission(context) && hasCameraPermission(context))
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val loc = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val cam = result[Manifest.permission.CAMERA] == true
        hasPerm = loc && cam
    }

    LaunchedEffect(staff, hasPerm) {
        val s = staff
        if (s != null && s.role == "staff" && hasPerm) vm.startAttendance()
    }

    when {
        staff == null -> LoginScreen(vm)
        staff?.role == "admin" -> AdminNotice(vm)
        !hasPerm -> PermissionGate { permissionLauncher.launch(REQUIRED_PERMISSIONS) }
        staff?.faceTemplate == null || FaceGeometry.needsReenroll(staff?.faceTemplate.orEmpty()) ->
            FaceEnrollScreen(vm)
        else -> AttendanceScreen(vm)
    }
}

@Composable
private fun AdminNotice(vm: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Admins use the web dashboard", fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge)
        Text(
            "This mobile app is for field staff duty attendance only. To view logs, add staff and " +
                    "manage kendras, sign in to the Dhan Kharidi Samiksha admin dashboard in a browser.",
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
        Button(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Sign out")
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
        Text("Location + camera required", fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge)
        Text(
            "Location verifies you are present at your assigned duty kendra. The camera is used " +
                    "only for the face check when you go On Duty or Off Duty — no photos are stored. " +
                    "Both are used only while the app is open.",
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Grant permissions")
        }
    }
}
