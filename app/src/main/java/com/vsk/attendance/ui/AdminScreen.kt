package com.vsk.attendance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vsk.attendance.data.Kendra
import com.vsk.attendance.data.StaffSummary

@Composable
fun AdminScreen(vm: MainViewModel) {
    val staff by vm.staff.collectAsState()
    val allKendras by vm.allKendras.collectAsState()
    val allStaff by vm.allStaff.collectAsState()
    val msg by vm.adminMsg.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.loadAdminData() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Admin — ${staff?.fullName ?: ""}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { vm.logout() }) { Text("Logout") }
        }
        Spacer(Modifier.height(10.dp))

        if (msg != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(msg!!, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    Text("✕", modifier = Modifier.clickable { vm.clearAdminMsg() })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kendras") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Staff") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("+ Kendra") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("+ Staff") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Assign") })
        }
        Spacer(Modifier.height(12.dp))

        when (tab) {
            0 -> KendraList(allKendras)
            1 -> StaffList(allStaff) { row -> vm.openAssign(row); tab = 4 }
            2 -> AddKendraForm(vm)
            3 -> EnrollForm(vm)
            4 -> AssignPanel(vm, allStaff, allKendras)
        }
    }
}

@Composable
private fun KendraList(kendras: List<Kendra>) {
    if (kendras.isEmpty()) { Text("No kendras yet. Add one from '+ Kendra'.", color = Color.Gray); return }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(kendras) { k ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(k.name, fontWeight = FontWeight.Medium)
                    Text("(${k.latitude}, ${k.longitude}) • radius ${k.radiusM} m",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("Window ${k.checkInStart}–${k.checkInEnd} • day ends ${k.dayEnd} • verify every ${k.verifyIntervalMin} min",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun StaffList(rows: List<StaffSummary>, onAssign: (StaffSummary) -> Unit) {
    if (rows.isEmpty()) { Text("No staff yet. Enroll one from '+ Staff'.", color = Color.Gray); return }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${s.fullName} — ${s.mobileNumber}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(s.role.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text(
                        (if (s.dutyType == "multi") "Multi-location" else "Fixed-duty") +
                                " • ${s.kendraNames ?: "no kendra assigned"}",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                    if (s.role != "admin") {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { onAssign(s) }) { Text("Assign kendra(s)") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddKendraForm(vm: MainViewModel) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var inStart by remember { mutableStateOf("08:00") }
    var inEnd by remember { mutableStateOf("10:00") }
    var dayEnd by remember { mutableStateOf("18:00") }
    var verify by remember { mutableStateOf("30") }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Kendra name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, label = { Text("Radius (m)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(verify, { verify = it.filter(Char::isDigit) }, label = { Text("Verify every (min)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(inStart, { inStart = it }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(inEnd, { inEnd = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(dayEnd, { dayEnd = it }, label = { Text("Day ends") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = { vm.createKendra(name, lat, lon, radius, inStart, inEnd, dayEnd, verify) }, modifier = Modifier.fillMaxWidth()) {
            Text("Create kendra")
        }
        Text("Times are HH:MM in IST. Capture the exact lat/long from Google Maps at the kendra.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun EnrollForm(vm: MainViewModel) {
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("staff") }
    var dutyType by remember { mutableStateOf("fixed") }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mobile, { if (it.length <= 10) mobile = it.filter(Char::isDigit) },
            label = { Text("Mobile number") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Role", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { role = "staff" }, modifier = Modifier.weight(1f)) {
                Text("Staff", fontWeight = if (role == "staff") FontWeight.Bold else FontWeight.Normal)
            }
            OutlinedButton(onClick = { role = "admin" }, modifier = Modifier.weight(1f)) {
                Text("Admin", fontWeight = if (role == "admin") FontWeight.Bold else FontWeight.Normal)
            }
        }

        if (role == "staff") {
            Text("Duty type", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { dutyType = "fixed" }, modifier = Modifier.weight(1f)) {
                    Text("Fixed (one kendra)", fontWeight = if (dutyType == "fixed") FontWeight.Bold else FontWeight.Normal)
                }
                OutlinedButton(onClick = { dutyType = "multi" }, modifier = Modifier.weight(1f)) {
                    Text("Multi (several)", fontWeight = if (dutyType == "multi") FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Button(onClick = { vm.enrollStaff(name, mobile, role, dutyType) }, modifier = Modifier.fillMaxWidth()) {
            Text("Enroll")
        }
        Text("After enrolling, use the Assign tab to set the allowed duty kendra(s).",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun AssignPanel(vm: MainViewModel, allStaff: List<StaffSummary>, allKendras: List<Kendra>) {
    val assignStaff by vm.assignStaff.collectAsState()
    val assigned by vm.assignedKendraIds.collectAsState()
    val fieldStaff = allStaff.filter { it.role != "admin" }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text("1. Select staff", style = MaterialTheme.typography.labelLarge)
        if (fieldStaff.isEmpty()) { Text("No field staff enrolled yet.", color = Color.Gray); return }
        fieldStaff.forEach { s ->
            OutlinedButton(
                onClick = { vm.openAssign(s) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    (if (assignStaff?.id == s.id) "• " else "   ") + "${s.fullName} (${if (s.dutyType == "multi") "multi" else "fixed"})",
                    fontWeight = if (assignStaff?.id == s.id) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        val selected = assignStaff
        if (selected != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "2. Allowed kendras for ${selected.fullName}" +
                        if (selected.dutyType == "fixed") " (fixed — pick exactly one)" else " (multi — pick any)",
                style = MaterialTheme.typography.labelLarge
            )
            if (allKendras.isEmpty()) { Text("No kendras exist yet.", color = Color.Gray); return }
            allKendras.forEach { k ->
                val checked = assigned.contains(k.id)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleKendra(k.id, !checked) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { vm.toggleKendra(k.id, !checked) })
                    Text(k.name)
                }
            }
        }
    }
}
