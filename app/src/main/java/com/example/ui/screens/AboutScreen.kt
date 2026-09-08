package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.engine.PowertrainModel
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import kotlin.math.abs

/**
 * Version info plus the two explanations the owner asked for: what "log fuel" means in this app,
 * and how the app's recorded fuel compares with the car's own MID/MFA display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val trip by viewModel.tripEconomy.collectAsState()
    var midInput by remember {
        mutableStateOf(viewModel.settingsRepository.midDisplayKmL.value?.toString() ?: "")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("About & Fuel Guide") },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("btn_about_back")) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Kylaq TSI Coach", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "version ${BuildConfig.VERSION_NAME} • build ${BuildConfig.GIT_COMMIT_COUNT} • " +
                            "commit ${BuildConfig.GIT_COMMIT.take(7)}" +
                            (BuildConfig.GITHUB_RUN_NUMBER.takeIf { it.isNotBlank() }?.let { " • CI #$it" } ?: ""),
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(modelSummary(), color = TextSecondaryDark, fontSize = 12.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("What is “log fuel”?", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "“Log fuel” is fuel measured from the OBD log instead of guessed: the app polls " +
                            "PID 015E (engine fuel rate, L/h) — or 019D mass flow converted at 0.745 kg/L — " +
                            "and integrates it over time (Riemann sum of rate × dt). Distance comes from " +
                            "PID 010D, so km/L = distance ÷ integrated litres.\n\n" +
                            "Because integration sees every injector decision, log fuel captures what the " +
                            "car's display smooths away: idle burn, fuel-cut coasting at exactly 0 L/h, " +
                            "enrichment under boost, and cold-start penalties. That is also why the " +
                            "Insights trend can show a flat 0 during a coast — the ECU really is " +
                            "injecting nothing.",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("MID (car display) vs Recorded (this app)", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "The MID/MFA average in the instrument cluster is the manufacturer's estimate: " +
                            "injector pulse models, rounded and filtered. Enter what the cluster shows " +
                            "for the same trip and see the honest difference.",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = midInput,
                            onValueChange = { value ->
                                midInput = value
                                viewModel.settingsRepository.setMidDisplayKmL(value.toDoubleOrNull())
                            },
                            label = { Text("MID display (km/L)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_mid_kml")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val recorded = trip.averageKmL
                    val mid = midInput.toDoubleOrNull()
                    Row {
                        Column {
                            Text(
                                recorded.takeIf { trip.isFuelIntegrated && it > 0 }?.let { String.format("%.2f", it) } ?: "--",
                                color = NeonEmerald,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text("RECORDED km/L", color = TextSecondaryDark, fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        Column {
                            Text(
                                mid?.let { String.format("%.2f", it) } ?: "--",
                                color = CyberCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text("MID km/L", color = TextSecondaryDark, fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        Column {
                            val delta = if (mid != null && recorded > 0) recorded - mid else null
                            Text(
                                delta?.let { String.format("%+.2f", it) } ?: "--",
                                color = when {
                                    delta == null -> TextSecondaryDark
                                    abs(delta) <= 0.75 -> NeonEmerald
                                    else -> WarningRed
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text("DIFFERENCE", color = TextSecondaryDark, fontSize = 10.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Trip basis: ${String.format("%.1f", trip.distanceKm)} km, " +
                            "${String.format("%.2f", trip.totalFuelLiters)} L integrated, " +
                            "avg speed ${String.format("%.0f", trip.averageSpeedKmh)} km/h. " +
                            "A gap up to ~0.5-0.8 km/L is normal (cluster rounding and calibration); " +
                            "a large gap usually means the cluster is optimistically calibrated.",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("What this app tracks for your 1.0 TSI", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    listOf(
                        "Live telemetry from up to two ECUs (7E8/7E9) with stale-sample protection",
                        "Power & torque vs rpm, measured against the factory 178 Nm / 85 kW curve",
                        "Fuel-vs-speed curve and your efficiency sweet spot for the learned top gear",
                        "Driving-in-neutral / coasting events (20-130 km/h, no pedals) with fuel saved",
                        "Turbo: boost = MAP − baro, boost curve, tip-in lag, over-boost counts",
                        "Per-tank fuel evidence for X95 vs regular (cruise timing, trims, knock retard)",
                        "Auto-connect, auto-reconnect and always-on recording whenever the engine runs",
                        "Android Auto dashboard + details screen with every enabled PID",
                        "DTC scan, VIN decode, raw ISO-TP monitor and PID discovery for research PIDs"
                    ).forEach { line ->
                        Text("•  $line", color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Reference physics: ${PowertrainModel.PEAK_POWER_KW.toInt()} kW, " +
                            "${PowertrainModel.PEAK_TORQUE_NM.toInt()} Nm, drag area " +
                            "${PowertrainModel.DRAG_AREA_M2} m², fuel energy " +
                            "${PowertrainModel.FUEL_ENERGY_MJ_PER_L} MJ/L.",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
