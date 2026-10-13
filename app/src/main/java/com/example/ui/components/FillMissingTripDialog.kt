package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RecordTime
import com.example.ui.theme.*
import java.util.Locale

/**
 * Data payload for creating a reconstructed / manual trip to fill an unlogged gap.
 */
data class ManualTripRequest(
    val title: String,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val startTimestampMs: Long,
    val durationSeconds: Long,
    val driveMode: String = "D",
    val acState: String = "ON",
    val estimatedFuelLiters: Double? = null,
    val carpoolRiders: String = "",
    val carpoolFare: Double = 0.0,
    val notes: String = ""
) {
    val distanceKm: Double get() = maxOf(0.1, endOdometerKm - startOdometerKm)
}

/**
 * Dialog for filling an unlogged gap between OBD recording sessions or manually logging a missed drive.
 */
@Composable
fun FillMissingTripDialog(
    initialStartOdoKm: Double? = null,
    initialEndOdoKm: Double? = null,
    initialStartMs: Long? = null,
    initialEndMs: Long? = null,
    onDismiss: () -> Unit,
    onSave: (ManualTripRequest) -> Unit
) {
    val defaultStartOdo = initialStartOdoKm ?: 0.0
    val defaultEndOdo = initialEndOdoKm ?: (defaultStartOdo + 5.0)
    val defaultStartMs = initialStartMs ?: (System.currentTimeMillis() - 3600_000L)
    val defaultDurationSec = if (initialEndMs != null && initialEndMs > defaultStartMs) {
        maxOf(60L, (initialEndMs - defaultStartMs) / 1000L)
    } else {
        val dist = maxOf(0.5, defaultEndOdo - defaultStartOdo)
        // Assume ~30 km/h city average
        (dist / 30.0 * 3600.0).toLong().coerceIn(120L, 14400L)
    }

    var title by remember { mutableStateOf("Missed Drive (Gap Filled)") }
    var startOdoText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultStartOdo)) }
    var endOdoText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultEndOdo)) }
    var durationMinutesText by remember { mutableStateOf("${maxOf(1L, defaultDurationSec / 60L)}") }
    
    var driveMode by remember { mutableStateOf("D") } // "D" or "S"
    var acState by remember { mutableStateOf("ON") } // "ON", "OFF", "Auto"
    
    var customFuelText by remember { mutableStateOf("") }
    var showCarpoolSection by remember { mutableStateOf(false) }
    var carpoolRidersText by remember { mutableStateOf("") }
    var carpoolFareText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    val startOdoVal = startOdoText.toDoubleOrNull() ?: defaultStartOdo
    val endOdoVal = endOdoText.toDoubleOrNull() ?: defaultEndOdo
    val distanceKm = maxOf(0.0, endOdoVal - startOdoVal)
    val durationMinVal = durationMinutesText.toLongOrNull() ?: (defaultDurationSec / 60L)
    val durationSecVal = maxOf(60L, durationMinVal * 60L)
    
    val avgSpeedKmh = if (durationSecVal > 0) (distanceKm / (durationSecVal / 3600.0)).coerceIn(1.0, 160.0) else 30.0
    val autoEstimatedFuelL = (distanceKm / 10.5) // ~10.5 km/L default EA211 city economy
    val finalFuelL = customFuelText.toDoubleOrNull() ?: autoEstimatedFuelL

    val isValid = distanceKm > 0.05 && durationSecVal >= 60L && startOdoVal >= 0.0 && endOdoVal > startOdoVal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddRoad, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fill Missing / Unlogged Trip", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimaryDark)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Reconstruct a trip for distance driven without the OBD logger connected. Telemetry endpoints, odometer delta, and fuel burn will be synthesized into a permanent trip record.",
                    color = TextSecondaryDark,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

                // Trip Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Trip Title / Purpose") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_manual_trip_title"),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberCyan)
                )

                // Odometer Gap Endpoints
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startOdoText,
                                onValueChange = { startOdoText = it },
                                label = { Text("Start ODO (km)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_manual_start_odo")
                            )
                            OutlinedTextField(
                                value = endOdoText,
                                onValueChange = { endOdoText = it },
                                label = { Text("End ODO (km)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_manual_end_odo")
                            )
                        }

                        // Calculated Delta & Avg Speed summary
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Route, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(16.dp))
                                Text(
                                    text = String.format(Locale.US, "Distance: +%.1f km", distanceKm),
                                    fontWeight = FontWeight.Bold,
                                    color = NeonEmerald,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                                Text(
                                    text = String.format(Locale.US, "Avg: %.0f km/h", avgSpeedKmh),
                                    fontWeight = FontWeight.Medium,
                                    color = CyberCyan,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Estimated Duration in Minutes
                OutlinedTextField(
                    value = durationMinutesText,
                    onValueChange = { durationMinutesText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Estimated Duration (Minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, tint = CyberCyan) },
                    modifier = Modifier.fillMaxWidth().testTag("input_manual_duration_mins")
                )

                // Drive Mode & AC State Selectors
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Drive Mode Switch
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Transmission Mode", color = TextSecondaryDark, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = driveMode == "D",
                                    onClick = { driveMode = "D" },
                                    label = { Text("Drive (D)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberCyan.copy(alpha = 0.2f), selectedLabelColor = CyberCyan)
                                )
                                FilterChip(
                                    selected = driveMode == "S",
                                    onClick = { driveMode = "S" },
                                    label = { Text("Sport (S)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WarningRed.copy(alpha = 0.2f), selectedLabelColor = WarningRed)
                                )
                            }
                        }
                    }

                    // AC State Switch
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Air Conditioning", color = TextSecondaryDark, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = acState == "ON",
                                    onClick = { acState = "ON" },
                                    label = { Text("ON", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberCyan.copy(alpha = 0.2f), selectedLabelColor = CyberCyan)
                                )
                                FilterChip(
                                    selected = acState == "OFF",
                                    onClick = { acState = "OFF" },
                                    label = { Text("OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricAmber.copy(alpha = 0.2f), selectedLabelColor = ElectricAmber)
                                )
                            }
                        }
                    }
                }

                // Estimated Fuel Burn
                OutlinedTextField(
                    value = customFuelText,
                    onValueChange = { customFuelText = it },
                    label = { Text(String.format(Locale.US, "Estimated Fuel (L) [Default: %.2f L]", autoEstimatedFuelL)) },
                    placeholder = { Text(String.format(Locale.US, "%.2f", autoEstimatedFuelL)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = NeonEmerald) },
                    modifier = Modifier.fillMaxWidth().testTag("input_manual_fuel_liters")
                )

                // Optional Carpool Section Toggle
                Surface(
                    color = if (showCarpoolSection) NeonEmerald.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (showCarpoolSection) NeonEmerald.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth().clickable { showCarpoolSection = !showCarpoolSection }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Groups, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(18.dp))
                            Text("Attach Carpool / Riders", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(
                            imageVector = if (showCarpoolSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondaryDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = showCarpoolSection) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = carpoolRidersText,
                            onValueChange = { carpoolRidersText = it },
                            label = { Text("Rider Names (Comma Separated)") },
                            placeholder = { Text("e.g. Rahul, Sneha, Amit") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_manual_carpool_riders")
                        )
                        OutlinedTextField(
                            value = carpoolFareText,
                            onValueChange = { carpoolFareText = it },
                            label = { Text("Total Fare Earned (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_manual_carpool_fare")
                        )
                    }
                }

                // Optional Notes
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / Context") },
                    placeholder = { Text("e.g. Drove with windows open, no OBD dongle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onSave(
                            ManualTripRequest(
                                title = title.ifBlank { "Missed Drive (Gap Filled)" },
                                startOdometerKm = startOdoVal,
                                endOdometerKm = endOdoVal,
                                startTimestampMs = defaultStartMs,
                                durationSeconds = durationSecVal,
                                driveMode = driveMode,
                                acState = acState,
                                estimatedFuelLiters = finalFuelL,
                                carpoolRiders = carpoolRidersText.trim(),
                                carpoolFare = carpoolFareText.toDoubleOrNull() ?: 0.0,
                                notes = notesText.trim()
                            )
                        )
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                modifier = Modifier.testTag("btn_confirm_manual_trip")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save & Fill Trip", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}
