package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.GapTripType
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
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
    val notes: String = "",
    val gapTripType: GapTripType = GapTripType.MISSED_DRIVE,
    val pollenFilterServiced: Boolean = false,
    val invoiceCost: Double? = null,
    val invoicePdfUri: String? = null
) {
    val distanceKm: Double get() = maxOf(0.1, endOdometerKm - startOdometerKm)
}

/**
 * Dialog for filling an unlogged gap between OBD recording sessions or manually logging a missed drive.
 *
 * [GapTripType.SERVICE_INSPECTION] reveals pollen-filter task tagging, an invoice rupee field,
 * and a PDF invoice picker. Drive/Sport chips use balanced [Modifier.weight] + maxLines=1 so
 * "Sport (S)" never wraps. The body is height-capped, vertically scrollable, and ime-padded.
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
        (dist / 30.0 * 3600.0).toLong().coerceIn(120L, 14400L)
    }

    var gapTripType by remember { mutableStateOf(GapTripType.MISSED_DRIVE) }
    var title by remember { mutableStateOf(GapTripType.MISSED_DRIVE.defaultTitle) }
    var startOdoText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultStartOdo)) }
    var endOdoText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultEndOdo)) }
    var durationMinutesText by remember { mutableStateOf("${maxOf(1L, defaultDurationSec / 60L)}") }

    var driveMode by remember { mutableStateOf("D") }
    var acState by remember { mutableStateOf("ON") }

    var customFuelText by remember { mutableStateOf("") }
    var showCarpoolSection by remember { mutableStateOf(false) }
    var carpoolRidersText by remember { mutableStateOf("") }
    var carpoolFareText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    var pollenFilterServiced by remember { mutableStateOf(false) }
    var invoiceCostText by remember { mutableStateOf("") }
    var invoicePdfUri by remember { mutableStateOf<String?>(null) }
    var invoicePdfLabel by remember { mutableStateOf<String?>(null) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        invoicePdfUri = uri?.toString()
        invoicePdfLabel = uri?.lastPathSegment?.substringAfterLast('/') ?: uri?.toString()
    }

    LaunchedEffect(gapTripType) {
        if (title == GapTripType.MISSED_DRIVE.defaultTitle ||
            title == GapTripType.SERVICE_INSPECTION.defaultTitle
        ) {
            title = gapTripType.defaultTitle
        }
        if (gapTripType != GapTripType.SERVICE_INSPECTION) {
            pollenFilterServiced = false
        }
    }

    val startOdoVal = startOdoText.toDoubleOrNull() ?: defaultStartOdo
    val endOdoVal = endOdoText.toDoubleOrNull() ?: defaultEndOdo
    val distanceKm = maxOf(0.0, endOdoVal - startOdoVal)
    val durationMinVal = durationMinutesText.toLongOrNull() ?: (defaultDurationSec / 60L)
    val durationSecVal = maxOf(60L, durationMinVal * 60L)

    val avgSpeedKmh = if (durationSecVal > 0) (distanceKm / (durationSecVal / 3600.0)).coerceIn(1.0, 160.0) else 30.0
    val autoEstimatedFuelL = (distanceKm / 10.5)
    val finalFuelL = customFuelText.toDoubleOrNull() ?: autoEstimatedFuelL

    val isValid = distanceKm > 0.05 && durationSecVal >= 60L && startOdoVal >= 0.0 && endOdoVal > startOdoVal

    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val chipColorsCyan = FilterChipDefaults.filterChipColors(
        selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
        selectedLabelColor = CyberCyan
    )
    val chipColorsRed = FilterChipDefaults.filterChipColors(
        selectedContainerColor = WarningRed.copy(alpha = 0.2f),
        selectedLabelColor = WarningRed
    )
    val chipColorsAmber = FilterChipDefaults.filterChipColors(
        selectedContainerColor = ElectricAmber.copy(alpha = 0.2f),
        selectedLabelColor = ElectricAmber
    )
    val chipColorsEmerald = FilterChipDefaults.filterChipColors(
        selectedContainerColor = NeonEmerald.copy(alpha = 0.2f),
        selectedLabelColor = NeonEmerald
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
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
                    .heightIn(max = maxBodyHeight)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Reconstruct a trip for distance driven without the OBD logger connected. Telemetry endpoints, odometer delta, and fuel burn will be synthesized into a permanent trip record.",
                    color = TextSecondaryDark,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

                Text("Gap type", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = gapTripType == GapTripType.MISSED_DRIVE,
                        onClick = { gapTripType = GapTripType.MISSED_DRIVE },
                        modifier = Modifier.weight(1f).testTag("chip_gap_type_missed"),
                        label = {
                            Text(
                                GapTripType.MISSED_DRIVE.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = chipColorsCyan
                    )
                    FilterChip(
                        selected = gapTripType == GapTripType.SERVICE_INSPECTION,
                        onClick = { gapTripType = GapTripType.SERVICE_INSPECTION },
                        modifier = Modifier.weight(1f).testTag("chip_gap_type_service"),
                        label = {
                            Text(
                                GapTripType.SERVICE_INSPECTION.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = chipColorsEmerald
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Trip Title / Purpose") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_manual_trip_title"),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberCyan)
                )

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

                OutlinedTextField(
                    value = durationMinutesText,
                    onValueChange = { durationMinutesText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Estimated Duration (Minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, tint = CyberCyan) },
                    modifier = Modifier.fillMaxWidth().testTag("input_manual_duration_mins")
                )

                Text("Transmission mode", color = TextSecondaryDark, fontSize = 10.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = driveMode == "D",
                        onClick = { driveMode = "D" },
                        modifier = Modifier.weight(1f).testTag("chip_drive_d"),
                        label = {
                            Text(
                                "Drive (D)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = chipColorsCyan
                    )
                    FilterChip(
                        selected = driveMode == "S",
                        onClick = { driveMode = "S" },
                        modifier = Modifier.weight(1f).testTag("chip_sport_s"),
                        label = {
                            Text(
                                "Sport (S)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = chipColorsRed
                    )
                }

                Text("Air conditioning", color = TextSecondaryDark, fontSize = 10.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = acState == "ON",
                        onClick = { acState = "ON" },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text("ON", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        },
                        colors = chipColorsCyan
                    )
                    FilterChip(
                        selected = acState == "OFF",
                        onClick = { acState = "OFF" },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text("OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        },
                        colors = chipColorsAmber
                    )
                }

                AnimatedVisibility(visible = gapTripType == GapTripType.SERVICE_INSPECTION) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Service tasks", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        FilterChip(
                            selected = pollenFilterServiced,
                            onClick = { pollenFilterServiced = !pollenFilterServiced },
                            modifier = Modifier.fillMaxWidth().testTag("chip_pollen_filter"),
                            leadingIcon = {
                                Icon(Icons.Default.Air, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = {
                                Text(
                                    "Cabin / pollen filter replaced",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = chipColorsEmerald
                        )
                        OutlinedTextField(
                            value = invoiceCostText,
                            onValueChange = { invoiceCostText = it },
                            label = { Text("Invoice cost (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_invoice_cost")
                        )
                        OutlinedButton(
                            onClick = { pdfPicker.launch("application/pdf") },
                            modifier = Modifier.fillMaxWidth().testTag("btn_attach_invoice_pdf")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = invoicePdfLabel?.let { "PDF: $it" } ?: "Attach invoice PDF",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = TextPrimaryDark,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

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

                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / Context") },
                    placeholder = { Text("e.g. Drove with windows open, no OBD dongle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onSave(
                            ManualTripRequest(
                                title = title.ifBlank { gapTripType.defaultTitle },
                                startOdometerKm = startOdoVal,
                                endOdometerKm = endOdoVal,
                                startTimestampMs = defaultStartMs,
                                durationSeconds = durationSecVal,
                                driveMode = driveMode,
                                acState = acState,
                                estimatedFuelLiters = finalFuelL,
                                carpoolRiders = carpoolRidersText.trim(),
                                carpoolFare = carpoolFareText.toDoubleOrNull() ?: 0.0,
                                notes = notesText.trim(),
                                gapTripType = gapTripType,
                                pollenFilterServiced = pollenFilterServiced,
                                invoiceCost = invoiceCostText.toDoubleOrNull(),
                                invoicePdfUri = invoicePdfUri
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
