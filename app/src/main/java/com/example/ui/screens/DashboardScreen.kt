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
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToRawMonitor: () -> Unit,
    onNavigateToPidDetail: (String) -> Unit,
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

    val isConnected = connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("dashboard_screen")
    ) {
        // Vehicle & Connection Status Banner
        VehicleStatusHeader(
            vehicleName = vehicleName,
            adapterName = connectedDeviceName ?: "Not Connected",
            connectionState = connectionState,
            protocol = "ISO 15765-4 (CAN 11/500)",
            onConnectClick = onOpenConnectDialog,
            onDisconnectClick = { viewModel.disconnect() }
        )

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
        TelemetryCardsGrid(
            liveMap = liveDecodedMap,
            onPidClick = { pidId -> onNavigateToPidDetail(pidId) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Section Title: Experimental Research Channels (016D & 0170)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(ResearchPurple)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RESEARCH CHANNELS (PASSIVE CAPTURE)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ResearchPurple,
                    letterSpacing = 1.sp
                )
            }
            TextButton(onClick = onNavigateToRawMonitor) {
                Text("Raw Monitor", color = CyberCyan)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 016D Research Card
        ResearchPidCard(
            pidId = "016D",
            name = "016D Fuel Pressure Control",
            description = "High-Pressure Direct Injection Rail Channel",
            history = pidRawHistory["016D"] ?: emptyList(),
            onClick = { onNavigateToPidDetail("016D") }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 0170 Research Card
        ResearchPidCard(
            pidId = "0170",
            name = "0170 Boost Pressure Control",
            description = "EA211 Turbo Wastegate & Charge Control Channel",
            history = pidRawHistory["0170"] ?: emptyList(),
            onClick = { onNavigateToPidDetail("0170") }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun VehicleStatusHeader(
    vehicleName: String,
    adapterName: String,
    connectionState: ConnectionState,
    protocol: String,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val statusColor by animateColorAsState(
        when (connectionState) {
            ConnectionState.CONNECTED -> NeonEmerald
            ConnectionState.CONNECTING, ConnectionState.INITIALIZING -> ElectricAmber
            ConnectionState.ERROR -> WarningRed
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .testTag("vehicle_status_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> "CONNECTED"
                                ConnectionState.CONNECTING -> "CONNECTING..."
                                ConnectionState.INITIALIZING -> "INITIALIZING..."
                                ConnectionState.ERROR -> "CONNECTION ERROR"
                                ConnectionState.DISCONNECTED -> "DISCONNECTED"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = vehicleName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (connectionState == ConnectionState.CONNECTED) {
                    FilledTonalButton(
                        onClick = onDisconnectClick,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.testTag("btn_disconnect")
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color(0xFF00363D)),
                        modifier = Modifier.testTag("btn_connect")
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connect", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Adapter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(adapterName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("CAN Protocol", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(protocol, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                }
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

@Composable
fun TelemetryCardsGrid(
    liveMap: Map<String, String>,
    onPidClick: (String) -> Unit
) {
    val items = listOf(
        TelemetryItem("010C", "Engine RPM", liveMap["010C"] ?: "-- RPM", Icons.Default.Speed, CyberCyan),
        TelemetryItem("010D", "Vehicle Speed", liveMap["010D"] ?: "-- km/h", Icons.Default.Navigation, CyberCyan),
        TelemetryItem("010B", "Intake MAP (Boost)", liveMap["010B"] ?: "-- kPa", Icons.Default.Compress, NeonEmerald),
        TelemetryItem("0104", "Engine Load", liveMap["0104"] ?: "-- %", Icons.Default.FitnessCenter, NeonEmerald),
        TelemetryItem("0111", "Throttle Position", liveMap["0111"] ?: "-- %", Icons.Default.Tune, ElectricAmber),
        TelemetryItem("0149", "Accelerator D", liveMap["0149"] ?: "-- %", Icons.Default.ElectricCar, ElectricAmber),
        TelemetryItem("0105", "Coolant Temp", liveMap["0105"] ?: "-- °C", Icons.Default.DeviceThermostat, WarningRed),
        TelemetryItem("010F", "Intake Air Temp", liveMap["010F"] ?: "-- °C", Icons.Default.Air, WarningRed),
        TelemetryItem("0146", "Ambient Temp", liveMap["0146"] ?: "-- °C", Icons.Default.WbSunny, Color(0xFF81D4FA)),
        TelemetryItem("019D", "Fuel Rate", liveMap["019D"] ?: "-- L/h", Icons.Default.LocalGasStation, Color(0xFFFF8A80)),
        TelemetryItem("0162", "Engine Torque", liveMap["0162"] ?: "-- %", Icons.Default.Bolt, Color(0xFFFFD54F)),
        TelemetryItem("0142", "ECU Voltage", liveMap["0142"] ?: "-- V", Icons.Default.BatteryChargingFull, Color(0xFF80CBC4))
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in items.indices step 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryCard(item = items[i], modifier = Modifier.weight(1f), onClick = { onPidClick(items[i].pidId) })
                if (i + 1 < items.size) {
                    TelemetryCard(item = items[i + 1], modifier = Modifier.weight(1f), onClick = { onPidClick(items[i + 1].pidId) })
                } else {
                    Spacer(modifier = Modifier.weight(1f))
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
