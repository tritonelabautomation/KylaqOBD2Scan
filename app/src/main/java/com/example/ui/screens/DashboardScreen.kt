package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.ConnectionState
import com.example.data.PollingSpeedMode
import com.example.model.TransactionRecord
import com.example.data.GpsData
import com.example.model.CanProtocol
import com.example.model.ProtocolHealth
import com.example.model.ProtocolVerificationResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToRawMonitor: () -> Unit,
    onNavigateToPidDetail: (String) -> Unit,
    onNavigateToCarDoctor: () -> Unit,
    onNavigateToTrips: () -> Unit,
    onNavigateToHud: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPidScanner: () -> Unit = {},
    onOpenConnectDialog: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val isPolling by viewModel.isPolling.collectAsState()
    val transactionCount by viewModel.transactionCount.collectAsState()
    val canResponseCount by viewModel.canResponseCount.collectAsState()
    val errorCount by viewModel.errorCount.collectAsState()
    val liveDecodedMap by viewModel.liveDecodedMap.collectAsState()
    val pidRawHistory by viewModel.pidRawHistory.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDurationSeconds by viewModel.recordingDurationSeconds.collectAsState()
    val pollingMode by viewModel.pollingMode.collectAsState()
    val vehicleName by viewModel.vehicleName.collectAsState()
    val vehicleVin by viewModel.vehicleVin.collectAsState()
    val savedRecordings by viewModel.savedRecordings.collectAsState()
    
    val selectedCanProtocol by viewModel.selectedCanProtocol.collectAsState()
    val protocolHealth by viewModel.protocolHealth.collectAsState()
    val protocolResult by viewModel.protocolVerificationResult.collectAsState()
    val gpsData by viewModel.gpsData.collectAsState()

    val realtimeEconomy by viewModel.realtimeEconomy.collectAsState()
    val tripEconomy by viewModel.tripEconomy.collectAsState()
    val drivingState by viewModel.drivingState.collectAsState()
    val transmissionState by viewModel.transmissionState.collectAsState()
    val ecuDiscoveryReport by viewModel.ecuDiscoveryReport.collectAsState()
    val isDiscoveringEcus by viewModel.isDiscoveringEcus.collectAsState()
    val discoveryProgressText by viewModel.discoveryProgressText.collectAsState()

    val isConnected = connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("dashboard_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("OBD Logger & Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("v${com.example.BuildConfig.VERSION_NAME} • EA211 India Platform", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.testTag("btn_dashboard_settings")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = CyberCyan)
                }
            }
        }
        
        // Vehicle & Connection Status Banner
        VehicleStatusHeader(
            vehicleName = vehicleName,
            vehicleVin = vehicleVin,
            adapterName = connectedDeviceName ?: "Not Connected",
            connectionState = connectionState,
            protocol = selectedCanProtocol.displayName,
            protocolHealth = protocolHealth,
            onConnectClick = onOpenConnectDialog,
            onDisconnectClick = { viewModel.disconnect() },
            onFetchVinClick = { viewModel.fetchVehicleVin() }
        )
        
        Spacer(modifier = Modifier.height(14.dp))

        // Quick Access Hub
        QuickAccessHub(
            tripCount = savedRecordings.size,
            isRecording = isRecording,
            onCarDoctorClick = onNavigateToCarDoctor,
            onHudClick = onNavigateToHud,
            onTripsClick = onNavigateToTrips,
            onRawMonitorClick = onNavigateToRawMonitor,
            onPidScannerClick = onNavigateToPidScanner
        )

        Spacer(modifier = Modifier.height(14.dp))
        
        var showBatchTestDialog by remember { mutableStateOf(false) }
        var showDiagnosticsDialog by remember { mutableStateOf(false) }

        // Protocol Verification Selector
        ProtocolVerificationControl(
            selectedProtocol = selectedCanProtocol,
            health = protocolHealth,
            result = protocolResult,
            isConnected = isConnected,
            onProtocolSelected = { viewModel.selectCanProtocol(it) },
            onVerify = { viewModel.verifySelectedProtocol() },
            onShowBatchTest = { showBatchTestDialog = true },
            onShowDiagnostics = { showDiagnosticsDialog = true }
        )

        if (showBatchTestDialog) {
            val batchResults by viewModel.batchTestResults.collectAsState()
            val isBatchTesting by viewModel.isBatchTesting.collectAsState()
            ProtocolComparisonDialog(
                results = batchResults,
                isTesting = isBatchTesting,
                onStartTest = { viewModel.testAllCanProtocols() },
                onDismiss = { showBatchTestDialog = false },
                onApply = { 
                    if(it != null) {
                        viewModel.selectCanProtocol(it.protocol)
                        showBatchTestDialog = false
                    }
                }
            )
        }
        
        protocolResult?.let { pr ->
        if (showDiagnosticsDialog) {
            ProtocolDiagnosticsDialog(
                result = pr,
                onDismiss = { showDiagnosticsDialog = false }
            )
        }
    }

        Spacer(modifier = Modifier.height(14.dp))

        // Recording & Polling Control Bar
        RecordingControlBar(
            isRecording = isRecording,
            recordingDurationSeconds = recordingDurationSeconds,
            isPolling = isPolling,
            isConnected = isConnected,
            transactionCount = transactionCount,
            canResponseCount = canResponseCount,
            errorCount = errorCount,
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = { viewModel.stopRecording() },
            onTogglePolling = { viewModel.togglePolling() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Polling Mode Selector (Safe / Normal / Fast)
        PollingModeSelector(
            currentMode = pollingMode,
            onModeSelected = { viewModel.setPollingSpeedMode(it) }
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Section Title: Live Telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "LIVE TELEMETRY (EA211)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                letterSpacing = 1.sp
            )
            Text(
                text = "${liveDecodedMap.size} PIDs Active",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Primary Live Telemetry Cards Grid
        if (protocolHealth == ProtocolHealth.TESTING) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CyberCyan)
                    Text("Verifying ELM327 protocol profile with ECU...", color = CyberCyan, fontSize = 12.sp)
                }
            }
        } else if (protocolHealth == ProtocolHealth.NO_RESPONSE && isConnected) {
            Surface(
                color = WarningRed.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningRed, modifier = Modifier.size(18.dp))
                    Text("No ECU response received on current protocol profile.", color = WarningRed, fontSize = 12.sp)
                }
            }
        }

        // ECU Discovery & Capability Banner
        if (isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ECU & HARDWARE DISCOVERY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        Button(
                            onClick = { viewModel.runEcuDiscovery() },
                            enabled = !isDiscoveringEcus,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            if (isDiscoveringEcus) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("PROBING...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("RUN DISCOVERY", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isDiscoveringEcus) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = discoveryProgressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    ecuDiscoveryReport?.let { report ->
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        val primaryEcu = report.detectedEcus.firstOrNull { it.rxCanId == "7E8" } ?: report.detectedEcus.firstOrNull()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Primary ECU:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(primaryEcu?.let { "${it.ecuName ?: it.ecuRole} (${it.rxCanId})" } ?: "Not detected", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        if (primaryEcu?.calibrationId != null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Calibration ID:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(primaryEcu.calibrationId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Transmission TCU:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(transmissionTcu?.let { "${it.ecuName ?: it.ecuRole} (${it.rxCanId})" } ?: "Unified Gateway / Integrated", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Verified Supported PIDs:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${report.totalSupportedPids} validated", style = MaterialTheme.typography.bodySmall, color = NeonEmerald, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        TelemetryDashboardContent(
            gpsData = gpsData,
            liveMap = liveDecodedMap,
            realtimeEconomy = realtimeEconomy,
            tripEconomy = tripEconomy,
            drivingState = drivingState,
            transmissionState = transmissionState,
            onPidClick = { pidId -> onNavigateToPidDetail(pidId) }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}


@Composable
fun VehicleStatusHeader(
    vehicleName: String,
    vehicleVin: String?,
    adapterName: String,
    connectionState: ConnectionState,
    protocol: String,
    protocolHealth: ProtocolHealth,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onFetchVinClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = vehicleName.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = vehicleVin ?: "VIN Unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = CyberCyan
                        )
                        if (connectionState == ConnectionState.CONNECTED && vehicleVin == null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onFetchVinClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(20.dp)) {
                                Text("READ VIN", fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = adapterName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (connectionState == ConnectionState.CONNECTED) {
                    OutlinedButton(
                        onClick = onDisconnectClick,
                        border = androidx.compose.foundation.BorderStroke(1.dp, WarningRed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningRed)
                    ) {
                        Text("Disconnect", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                    ) {
                        Text(if (connectionState == ConnectionState.CONNECTING) "Connecting..." else "Connect", fontSize = 12.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val isBt = connectionState == ConnectionState.CONNECTED
                val isEcu = protocolHealth == ProtocolHealth.WORKING || protocolHealth == ProtocolHealth.PARTIAL
                StatusBadge(label = "Bluetooth", isActive = isBt)
                StatusBadge(label = "Adapter", isActive = isBt)
                StatusBadge(label = "CAN", isActive = isEcu)
                StatusBadge(label = "ECU", isActive = isEcu)
            }
        }
    }
}

@Composable
fun RecordingControlBar(
    isRecording: Boolean,
    recordingDurationSeconds: Long,
    isPolling: Boolean,
    isConnected: Boolean,
    transactionCount: Long,
    canResponseCount: Long,
    errorCount: Long,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePolling: () -> Unit
) {
    val durationFormatted = remember(recordingDurationSeconds) {
        val min = recordingDurationSeconds / 60
        val sec = recordingDurationSeconds % 60
        String.format("%02d:%02d", min, sec)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_control_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = if (isRecording) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NeonEmerald)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isRecording) {
                    Button(
                        onClick = onStartRecording,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp)
                            .testTag("btn_start_recording"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonEmerald,
                            contentColor = Color(0xFF00391A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("START RECORDING", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onStopRecording,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp)
                            .testTag("btn_stop_recording"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarningRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("STOP ($durationFormatted)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Polling pause/resume toggle button
                OutlinedButton(
                    onClick = onTogglePolling,
                    enabled = isConnected,
                    modifier = Modifier
                        .weight(0.8f)
                        .height(48.dp)
                        .testTag("btn_toggle_polling"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (isPolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isPolling) "Pause" else "Resume", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Counters telemetry row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCounter(label = "TX/RX Total", value = "$transactionCount")
                MetricCounter(label = "CAN Frames", value = "$canResponseCount")
                MetricCounter(label = "Errors", value = "$errorCount", isError = errorCount > 0)
                MetricCounter(
                    label = "Rec Status",
                    value = if (isRecording) "ACTIVE ($durationFormatted)" else "IDLE",
                    isSuccess = isRecording
                )
            }
        }
    }
}

@Composable
fun MetricCounter(label: String, value: String, isError: Boolean = false, isSuccess: Boolean = false) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = when {
                isError -> WarningRed
                isSuccess -> NeonEmerald
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
fun PollingModeSelector(
    currentMode: PollingSpeedMode,
    onModeSelected: (PollingSpeedMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("polling_mode_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Polling Mode: ${currentMode.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PollingSpeedMode.values().forEach { mode ->
                    val isSelected = mode == currentMode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onModeSelected(mode) },
                        label = {
                            Text(
                                text = "${mode.displayName} (${(mode.multiplier * 250).toInt()}ms)",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("chip_mode_${mode.name}")
                    )
                }
            }
        }
    }
}

data class TelemetryItem(
    val pidId: String,
    val label: String,
    val value: String,
    val icon: ImageVector,
    val accentColor: Color
)

@Composable
fun TelemetryCard(
    item: TelemetryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("telemetry_card_${item.pidId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = item.accentColor
            )
        }
    }
}

@Composable
fun ResearchPidCard(
    pidId: String,
    name: String,
    description: String,
    history: List<TransactionRecord>,
    onClick: () -> Unit
) {
    val lastRecord = history.lastOrNull()
    val observationCount = history.size
    val lastRawHex = lastRecord?.rawPayload ?: "AWAITING TELEMETRY"
    val timestamp = lastRecord?.timestampUtc ?: "--"
    val rxCanId = lastRecord?.canRxId ?: "7E8"
    val byteLength = if (lastRecord != null && lastRecord.rawPayload.length % 2 == 0) lastRecord.rawPayload.length / 2 else 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("research_card_$pidId"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(ResearchPurple.copy(alpha = 0.5f)))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(containerColor = ResearchPurple) {
                        Text(pidId, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "$observationCount obs",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = CyberCyan
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Raw response hex box
            Surface(
                color = Color(0xFF090D12),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (lastRawHex.isNotEmpty()) lastRawHex.chunked(2).joinToString(" ") else "NO DATA",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = ResearchPurple,
                        fontSize = 13.sp
                    )
                    Text(
                        text = if (byteLength > 0) "$byteLength bytes" else "",
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "CAN ID: $rxCanId",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                Text(
                    text = timestamp.takeLast(12),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolVerificationControl(
    selectedProtocol: CanProtocol,
    health: ProtocolHealth,
    result: ProtocolVerificationResult?,
    isConnected: Boolean,
    onProtocolSelected: (CanProtocol) -> Unit,
    onVerify: () -> Unit,
    onShowBatchTest: () -> Unit,
    onShowDiagnostics: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Selected OBD-II Profile", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedProtocol.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    CanProtocol.values().forEach { proto ->
                        DropdownMenuItem(
                            text = { Text(proto.displayName) },
                            onClick = {
                                onProtocolSelected(proto)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (health) {
                    ProtocolHealth.WORKING -> NeonEmerald
                    ProtocolHealth.PARTIAL -> ElectricAmber
                    ProtocolHealth.NO_RESPONSE -> WarningRed
                    ProtocolHealth.ADAPTER_ERROR -> WarningRed
                    ProtocolHealth.TESTING -> CyberCyan
                    ProtocolHealth.UNKNOWN -> Color.Gray
                }
                val statusText = when (health) {
                    ProtocolHealth.WORKING -> "🟢 WORKING"
                    ProtocolHealth.PARTIAL -> "🟡 PARTIAL"
                    ProtocolHealth.NO_RESPONSE -> "🔴 NO_RESPONSE"
                    ProtocolHealth.ADAPTER_ERROR -> "🔴 ADAPTER_ERROR"
                    ProtocolHealth.TESTING -> "🔵 TESTING..."
                    ProtocolHealth.UNKNOWN -> "⚪ UNVERIFIED"
                }
                
                Column {
                    Text("Verification", style = MaterialTheme.typography.labelSmall)
                    Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onShowBatchTest,
                        enabled = isConnected,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Test All", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onVerify,
                        enabled = isConnected && health != ProtocolHealth.TESTING,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, disabledContainerColor = Color.DarkGray),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(if (health == ProtocolHealth.TESTING) "Testing..." else "Test Protocol", fontSize = 12.sp)
                    }
                }
            }

            if (result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ECU responses: ${result.successCount}/${result.totalRequests}", style = MaterialTheme.typography.bodySmall, color = NeonEmerald)
                        Text("Unsupported: ${result.unsupportedCount}", style = MaterialTheme.typography.bodySmall, color = ElectricAmber)
                        Text("Timeouts: ${result.timeoutCount}", style = MaterialTheme.typography.bodySmall, color = WarningRed)
                        Text("CAN errors: ${result.canErrorCount}", style = MaterialTheme.typography.bodySmall, color = WarningRed)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = onShowDiagnostics, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                            Text("Diagnostics", fontSize = 12.sp, color = CyberCyan)
                        }
                        Text("Avg Latency: ${result.avgResponseTimeMs} ms", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(label: String, isActive: Boolean) {
    val color = if (isActive) NeonEmerald else Color.DarkGray
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) MaterialTheme.colorScheme.onSurface else Color.Gray,
            fontSize = 10.sp
        )
    }
}

@Composable
fun QuickAccessHub(
    tripCount: Int,
    isRecording: Boolean,
    onCarDoctorClick: () -> Unit,
    onHudClick: () -> Unit,
    onTripsClick: () -> Unit,
    onRawMonitorClick: () -> Unit,
    onPidScannerClick: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCard(
                title = "AI Car Doctor",
                subtitle = "Diagnostic Review & Ask AI",
                icon = Icons.Default.HealthAndSafety,
                accentColor = CyberCyan,
                modifier = Modifier.weight(1f),
                onClick = onCarDoctorClick
            )
            QuickActionCard(
                title = "Driving HUD",
                subtitle = "High-Contrast Live Cluster",
                icon = Icons.Default.DirectionsCar,
                accentColor = NeonEmerald,
                modifier = Modifier.weight(1f),
                onClick = onHudClick
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCard(
                title = "PID Discovery",
                subtitle = "Mode 01 Capability Scanner",
                icon = Icons.Default.Search,
                accentColor = ResearchPurple,
                modifier = Modifier.weight(1f),
                onClick = onPidScannerClick
            )
            QuickActionCard(
                title = "Raw CAN Stream",
                subtitle = "7E8/7E9 Filter & Hex Log",
                icon = Icons.Default.FormatListBulleted,
                accentColor = Color(0xFF81D4FA),
                modifier = Modifier.weight(1f),
                onClick = onRawMonitorClick
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCard(
                title = "Trips ($tripCount)",
                subtitle = if (isRecording) "Recording in progress..." else "Room Database Storage",
                icon = Icons.Default.Folder,
                accentColor = ElectricAmber,
                modifier = Modifier.weight(1f),
                onClick = onTripsClick
            )
        }
    }
}

@Composable
fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

