package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluetooth.ConnectionState
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivingDashboardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isPolling by viewModel.isPolling.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val liveDecodedMap by viewModel.liveDecodedMap.collectAsStateWithLifecycle()

    val realtimeEconomy by viewModel.realtimeEconomy.collectAsStateWithLifecycle()
    val tripEconomy by viewModel.tripEconomy.collectAsStateWithLifecycle()
    val drivingState by viewModel.drivingState.collectAsStateWithLifecycle()
    val transmissionState by viewModel.transmissionState.collectAsStateWithLifecycle()

    val isConnected = connectionState == ConnectionState.CONNECTED

    val rpm = liveDecodedMap["010C"] ?: "--"
    val speed = liveDecodedMap["010D"] ?: "--"
    val coolant = liveDecodedMap["0105"] ?: "--"
    val map = liveDecodedMap["010B"] ?: "--"
    val throttle = liveDecodedMap["0111"] ?: "--"
    val load = liveDecodedMap["0104"] ?: "--"
    val voltage = liveDecodedMap["0142"] ?: "--"
    val iat = liveDecodedMap["010F"] ?: "--"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DRIVING HUD • AUTO",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) NeonEmerald else WarningRed)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
                        }
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.StopCircle else Icons.Default.FiberManualRecord,
                            contentDescription = "Toggle Recording",
                            tint = if (isRecording) WarningRed else NeonEmerald
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkCanvas)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkCanvas)
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Powertrain Intelligence Banner (Driving State, 6-AT Gear, Fuel Economy)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Driving State & Brake Indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val stateColor = when (drivingState.state.name) {
                            "FUEL_CUT_DECELERATION", "COASTING" -> NeonEmerald
                            "CRUISING" -> CyberCyan
                            "ACCELERATING" -> ElectricAmber
                            "BRAKING" -> WarningRed
                            else -> Color.White
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(stateColor.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = drivingState.state.name.replace("_", " "),
                                color = stateColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (drivingState.isBrakeActive == true) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(WarningRed.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text("BRAKE", color = WarningRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Transmission Gear
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GEAR: ", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        val gearText = when {
                            transmissionState.actualGear != null -> "G${transmissionState.actualGear}"
                            transmissionState.estimatedGear != null -> "G${transmissionState.estimatedGear} (Est)"
                            else -> transmissionState.selectedRange.ifBlank { "—" }
                        }
                        Text(
                            text = gearText,
                            color = NeonEmerald,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Real-time Economy
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ECON: ", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        val econText = if (realtimeEconomy.isIdle) {
                            "${realtimeEconomy.idleConsumptionLh?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"} L/h"
                        } else {
                            realtimeEconomy.smoothedKmLDisplay
                        }
                        Text(
                            text = econText,
                            color = ElectricAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Speed & RPM Primary Cluster (Large Glanceable HUD)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Vehicle Speed Hero
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan.copy(alpha = 0.4f))
                ) {
                    val pidHistory by viewModel.pidRawHistory.collectAsStateWithLifecycle()
                    val speedData = pidHistory["010D"]?.mapNotNull { it.decodedValue?.toFloat() }?.takeLast(30) ?: emptyList()
                    Box(modifier = Modifier.fillMaxSize()) {
                        SimpleLineChart(data = speedData, modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 20.dp, start = 8.dp, end = 8.dp), lineColor = CyberCyan.copy(alpha = 0.5f))
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SPEED",
                                color = TextSecondaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = speed.replace(Regex("[^0-9.]"), "").ifEmpty { "--" },
                                fontSize = 54.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = CyberCyan
                            )
                            Text(
                                text = "KM / H",
                                color = TextSecondaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Engine RPM Hero
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, NeonEmerald.copy(alpha = 0.4f))
                ) {
                    val pidHistory by viewModel.pidRawHistory.collectAsStateWithLifecycle()
                    val rpmData = pidHistory["010C"]?.mapNotNull { it.decodedValue?.toFloat() }?.takeLast(30) ?: emptyList()
                    Box(modifier = Modifier.fillMaxSize()) {
                        SimpleLineChart(data = rpmData, modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 20.dp, start = 8.dp, end = 8.dp), lineColor = NeonEmerald.copy(alpha = 0.5f))
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "TACHOMETER",
                                color = TextSecondaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = rpm.replace(Regex("[^0-9.]"), "").ifEmpty { "--" },
                                fontSize = 54.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = NeonEmerald
                            )
                            Text(
                                text = "RPM",
                                color = TextSecondaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Secondary Metric Matrix (Thermal, Electrical & Boost)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.7f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudGaugeCard(
                        title = "COOLANT",
                        value = coolant,
                        icon = Icons.Default.DeviceThermostat,
                        color = ElectricAmber,
                        modifier = Modifier.weight(1f)
                    )
                    HudGaugeCard(
                        title = "MAP / BOOST",
                        value = map,
                        icon = Icons.Default.Compress,
                        color = CyberCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudGaugeCard(
                        title = "VOLTAGE",
                        value = voltage,
                        icon = Icons.Default.BatteryChargingFull,
                        color = NeonEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    HudGaugeCard(
                        title = "THROTTLE",
                        value = throttle,
                        icon = Icons.Default.Tune,
                        color = ElectricAmber,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudGaugeCard(
                        title = "ENGINE LOAD",
                        value = load,
                        icon = Icons.Default.FitnessCenter,
                        color = CyberCyanDark,
                        modifier = Modifier.weight(1f)
                    )
                    HudGaugeCard(
                        title = "INTAKE TEMP",
                        value = iat,
                        icon = Icons.Default.Air,
                        color = Color(0xFF81D4FA),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Quick Status Bottom Strip
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                color = DarkSurface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRecording) "RECORDING ACTIVE" else "MONITORING",
                        color = if (isRecording) WarningRed else NeonEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Skoda Kylaq 1.0 TSI • EA211",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HudGaugeCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = TextSecondaryDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = value,
                color = color,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
