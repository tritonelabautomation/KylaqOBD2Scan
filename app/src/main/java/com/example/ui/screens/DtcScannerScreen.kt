package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScannerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val dtcs by viewModel.recordingManager.tripRepository.dtcRecordsFlow.collectAsState(initial = emptyList())
    val isMultiEcuScanning by viewModel.isMultiEcuDtcScanning.collectAsState()
    val multiEcuSummaries by viewModel.udsMultiEcuDtcSummaries.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic Scanner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // FIX: Removed Clear DTCs (Delete) button. This app is READ-ONLY.
                    // Mode 04 (Clear DTCs) is blocked by SafetyValidator at the transport layer.
                    // The clearDtcs() function in MainViewModel only clears local cache.
                    IconButton(onClick = { viewModel.clearDtcs() }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Read-only mode info",
                            tint = CyberCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkCanvas,
                    titleContentColor = TextPrimaryDark,
                    navigationIconContentColor = TextPrimaryDark
                )
            )
        },
        containerColor = DarkCanvas
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            // Full-Vehicle OEM DTC Scan (Service 0x19)
            Button(
                onClick = { viewModel.scanFullVehicleUdsDtcs() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isMultiEcuScanning,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                if (isMultiEcuScanning) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning 9 ECUs (UDS Service 0x19)...", color = Color.Black)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Full-Vehicle Multi-ECU OEM Scan (Service 0x19)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            if (multiEcuSummaries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("MULTI-ECU SCAN SUMMARY", color = CyberCyan, fontWeight = FontWeight.Black, fontSize = 12.sp)
                        multiEcuSummaries.forEach { summary ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(summary.moduleName, color = TextPrimaryDark, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                val isClean = summary.dtcs.isEmpty()
                                Text(
                                    summary.status,
                                    color = if (isClean) NeonEmerald else WarningRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { viewModel.fetchActiveDtcs() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mode 03 Active")
                }
                
                OutlinedButton(
                    onClick = { viewModel.fetchPendingDtcs() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan)
                ) {
                    Text("Mode 07 Pending")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // I/M readiness (owner gap analysis 2026-09-19): the monitors J1979 exposes, for
            // inspection / PUC prep. Read-only like everything else on this screen.
            val readiness by viewModel.readinessReport.collectAsState()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "I/M Readiness (inspection / PUC)",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.fetchReadiness() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                    ) { Text("Check Monitors") }
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        readiness == null -> Text(
                            "Not checked yet. Uses the same live link as the DTC scan: the ECU " +
                                "reports which of its emission self-tests ran and finished.",
                            color = TextSecondaryDark, fontSize = 12.sp
                        )
                        readiness!!.error != null -> Text(
                            readiness!!.error!!, color = WarningRed, fontSize = 12.sp
                        )
                        else -> {
                            readiness!!.cycle?.let { ReadinessBlock("This drive cycle (0141)", it) }
                            readiness!!.sinceCleared?.let { ReadinessBlock("Since codes cleared (0101)", it) }
                            if (readiness!!.cycle == null && readiness!!.sinceCleared == null) {
                                Text(
                                    "ECU returned frames but no monitor bits decoded.",
                                    color = WarningRed, fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            if (dtcs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Diagnostic Trouble Codes Found", color = TextSecondaryDark)
                }
            } else {
                LazyColumn {
                    items(dtcs) { dtc ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = WarningRed, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(dtc.code, style = MaterialTheme.typography.titleLarge, color = WarningRed, fontWeight = FontWeight.Bold)
                                    Text(dtc.description, color = TextPrimaryDark)
                                    Text("Status: ${dtc.status}", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One readiness window: verdict line plus every fitted monitor's completion state. */
@Composable
private fun ReadinessBlock(label: String, s: com.example.analysis.ReadinessMonitors.Status) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondaryDark, fontSize = 12.sp)
        Text(
            when {
                s.milOn -> "MIL ON + ${s.confirmedDtcCount} DTC"
                s.ready -> "READY"
                else -> "NOT READY"
            },
            color = when {
                s.milOn -> WarningRed
                s.ready -> Color(0xFF34C759)
                else -> ElectricAmber
            },
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
    s.monitors.filter { it.supported }.forEach { m ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                m.name + if (m.continuous) " (continuous)" else "",
                color = TextPrimaryDark, fontSize = 12.sp
            )
            Text(
                if (m.complete) "complete" else "not complete",
                color = if (m.complete) TextSecondaryDark else WarningRed,
                fontSize = 12.sp
            )
        }
    }
    val unfitted = s.monitors.count { !it.supported }
    if (unfitted > 0) {
        Text(
            "$unfitted monitor(s) not fitted to this car - excluded from the verdict.",
            color = TextSecondaryDark, fontSize = 10.sp
        )
    }
}
