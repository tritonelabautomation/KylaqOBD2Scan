package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.analysis.MidClusterData
import com.example.analysis.MidClusterScanner
import com.example.analysis.ThreeWayFuelComparator
import com.example.analysis.TripFuelSummary
import com.example.data.MidClusterStore
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreeWayFuelCard(
    tripId: String,
    fuelSummary: TripFuelSummary.Summary,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var midData by remember { mutableStateOf(MidClusterStore.get(context, tripId)) }
    var isScanning by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showImageModal by remember { mutableStateOf(false) }

    // Image Picker for MID Photo OCR
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isScanning = true
            val scanned = MidClusterScanner.scanClusterImage(context, uri, tripId)
            isScanning = false
            if (scanned != null && scanned.avgFuelEconomyKmL > 0.0) {
                midData = scanned
                MidClusterStore.save(context, scanned)
                Toast.makeText(
                    context,
                    "MID OCR success: ${scanned.avgFuelEconomyKmL} km/L, ${scanned.distanceKm} km (${scanned.durationText})",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // If OCR failed or was partial, attach the photo and open manual edit dialog
                val partial = scanned ?: MidClusterData(
                    tripId = tripId,
                    durationMinutes = (fuelSummary.durationSeconds / 60).toInt(),
                    durationText = String.format(Locale.US, "%d:%02d h", fuelSummary.durationSeconds / 3600, (fuelSummary.durationSeconds % 3600) / 60),
                    distanceKm = fuelSummary.distanceKm,
                    avgFuelEconomyKmL = 0.0, // force manual input, never fake
                    avgSpeedKmh = fuelSummary.averageSpeedKmh,
                    photoUri = uri.toString()
                )
                midData = partial.copy(photoUri = uri.toString())
                Toast.makeText(context, "Could not auto-read all MID values. Please confirm in the dialog.", Toast.LENGTH_LONG).show()
                showEditDialog = true
            }
        }
    }

    val comparison = remember(fuelSummary, midData) {
        midData?.takeIf { it.avgFuelEconomyKmL > 0.1 }?.let { ThreeWayFuelComparator.compare(tripId, fuelSummary, it) }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (midData != null && midData!!.avgFuelEconomyKmL > 0.1) NeonEmerald.copy(alpha = 0.35f) else DarkBorder, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CompareArrows,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "3-WAY FUEL CALIBRATION",
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "OBD Mass Flow vs Tank Float vs Cluster MID",
                            color = TextSecondaryDark,
                            fontSize = 10.sp
                        )
                    }
                }

                if (midData != null && midData!!.avgFuelEconomyKmL > 0.1) {
                    Surface(
                        color = NeonEmerald.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "MID SYNCED",
                            color = NeonEmerald,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (midData != null && midData!!.avgFuelEconomyKmL > 0.1 && comparison != null) {
                // Section 1: Attached Cluster Snapshot Readout & Actions
                Surface(
                    color = DarkSurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "INSTRUMENT CLUSTER (MID READOUT)",
                                color = TextSecondaryDark,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { showEditDialog = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Edit Values", fontSize = 10.sp, color = CyberCyan)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(
                                    onClick = { imagePicker.launch("image/*") },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Change Photo", fontSize = 10.sp, color = ElectricAmber)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        MidClusterStore.remove(context, tripId)
                                        midData = null
                                        Toast.makeText(context, "MID data removed for trip", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = WarningRed.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Optional thumbnail if photo attached
                            midData?.photoUri?.let { uriStr ->
                                AsyncImage(
                                    model = uriStr,
                                    contentDescription = "Cluster Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, DarkBorder, RoundedCornerShape(6.dp))
                                        .clickable { showImageModal = true }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }

                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", midData!!.avgFuelEconomyKmL)} km/L",
                                        color = NeonEmerald,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("MID ECONOMY", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", midData!!.distanceKm)} km",
                                        color = TextPrimaryDark,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("MID DISTANCE", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = midData!!.durationText,
                                        color = TextPrimaryDark,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("MID DURATION", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.0f", midData!!.avgSpeedKmh)} km/h",
                                        color = TextPrimaryDark,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text("AVG SPEED", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                            }
                        }

                        // Additional cluster telemetry if detected
                        if (midData!!.totalOdometerKm != null || midData!!.rangeKm != null || midData!!.ambientTempC != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                midData!!.totalOdometerKm?.let {
                                    Text("Odo: ${it.toInt()} km", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                                midData!!.rangeKm?.let {
                                    Text("Range: ${it.toInt()} km", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                                midData!!.ambientTempC?.let {
                                    Text("Ambient: ${String.format(Locale.US, "%.1f", it)}°C", color = TextSecondaryDark, fontSize = 9.sp)
                                }
                                Text("Mode: ${midData!!.mode}", color = TextSecondaryDark, fontSize = 9.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Section 2: Three-Way Factor Comparison Matrix
                Text(
                    text = "3-FACTOR CONVERGENCE BREAKDOWN",
                    color = TextSecondaryDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Factor 1 Card
                FactorRowCard(
                    factorLabel = "FACTOR 1 · OBD INJECTION INTEGRATION",
                    methodSubtitle = "Microsecond Mass Rate (PID 019D / 015E)",
                    liters = comparison.factor1ObdIntegration.fuelLiters,
                    kmL = comparison.factor1ObdIntegration.economyKmL,
                    errorPct = comparison.factor1ObdIntegration.errorPctVsMid,
                    verdictBadge = comparison.factor1ObdIntegration.accuracyVerdict,
                    badgeColor = if (kotlin.math.abs(comparison.factor1ObdIntegration.errorPctVsMid ?: 0.0) <= 6.0) NeonEmerald else CyberCyan,
                    explanation = comparison.factor1ObdIntegration.explanation
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Factor 2 Card
                FactorRowCard(
                    factorLabel = "FACTOR 2 · TANK FLOAT LEVEL DELTA",
                    methodSubtitle = "Potentiometer Float % Drop (PID 012F)",
                    liters = comparison.factor2TankFloatDelta.fuelLiters,
                    kmL = comparison.factor2TankFloatDelta.economyKmL,
                    errorPct = comparison.factor2TankFloatDelta.errorPctVsMid,
                    verdictBadge = comparison.factor2TankFloatDelta.accuracyVerdict,
                    badgeColor = if (comparison.factor2TankFloatDelta.errorPctVsMid == null) TextSecondaryDark else if (kotlin.math.abs(comparison.factor2TankFloatDelta.errorPctVsMid) <= 10.0) ElectricAmber else WarningRed,
                    explanation = comparison.factor2TankFloatDelta.explanation
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Factor 3 Card (Baseline)
                FactorRowCard(
                    factorLabel = "FACTOR 3 · INSTRUMENT CLUSTER (MID)",
                    methodSubtitle = "ECU Pulse-Width Readout (Since Start)",
                    liters = comparison.factor3ClusterMid.fuelLiters,
                    kmL = comparison.factor3ClusterMid.economyKmL,
                    errorPct = 0.0,
                    verdictBadge = "Ground Truth Baseline",
                    badgeColor = CyberCyan,
                    explanation = comparison.factor3ClusterMid.explanation
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Section 3: Synthesis Verdict Card
                Surface(
                    color = (if (comparison.closestFactorNumber == 1) NeonEmerald else ElectricAmber).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, (if (comparison.closestFactorNumber == 1) NeonEmerald else ElectricAmber).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (comparison.closestFactorNumber == 1) NeonEmerald else ElectricAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CALIBRATION VERDICT: ${if (comparison.closestFactorNumber == 1) "FACTOR 1 (OBD INTEGRATION) CLOSEST" else "FACTOR 2 CLOSEST"}",
                                color = if (comparison.closestFactorNumber == 1) NeonEmerald else ElectricAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = comparison.primaryInsightVerdict,
                            color = TextPrimaryDark,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "💡 ${comparison.recommendation}",
                            color = TextSecondaryDark,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            } else {
                // Empty State: Prompt User to Attach Cluster Photo or Enter Manually
                Surface(
                    color = DarkSurfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Compare OBD Telemetry with Instrument Cluster",
                            color = TextPrimaryDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Take or upload a photo of your Škoda Virtual Cockpit 'Since start' screen. On-device ML Kit OCR extracts your fuel economy, distance, and duration automatically.",
                            color = TextSecondaryDark,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { imagePicker.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isScanning,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Scanning OCR...", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                } else {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Scan Photo", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = { showEditDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Enter Manually", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal to view full cluster image
    if (showImageModal && midData?.photoUri != null) {
        AlertDialog(
            onDismissRequest = { showImageModal = false },
            title = { Text("Attached Cluster Snapshot", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = midData!!.photoUri,
                        contentDescription = "Full Cluster Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageModal = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Manual Edit / Review Dialog
    if (showEditDialog) {
        var durationInput by remember {
            mutableStateOf(
                midData?.durationText?.takeIf { it.isNotBlank() && it != "0:00 h" }
                    ?: String.format(Locale.US, "%d:%02d h", fuelSummary.durationSeconds / 3600, (fuelSummary.durationSeconds % 3600) / 60)
            )
        }
        var distanceInput by remember {
            mutableStateOf(
                midData?.distanceKm?.takeIf { it > 0.05 }?.toString()
                    ?: String.format(Locale.US, "%.1f", fuelSummary.distanceKm)
            )
        }
        var economyInput by remember {
            mutableStateOf(
                midData?.avgFuelEconomyKmL?.takeIf { it > 0.1 }?.toString() ?: ""
            )
        }
        var speedInput by remember {
            mutableStateOf(
                midData?.avgSpeedKmh?.takeIf { it > 0 }?.toString()
                    ?: String.format(Locale.US, "%.0f", fuelSummary.averageSpeedKmh)
            )
        }
        var odoInput by remember {
            mutableStateOf(midData?.totalOdometerKm?.toString() ?: "")
        }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Enter / Verify Instrument Cluster (MID) Values", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter the exact values shown on your Virtual Cockpit screen.",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = economyInput,
                        onValueChange = { economyInput = it },
                        label = { Text("Avg Fuel Economy (km/L) *") },
                        placeholder = { Text("e.g. 9.8") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = distanceInput,
                        onValueChange = { distanceInput = it },
                        label = { Text("Trip Distance (km) *") },
                        placeholder = { Text("e.g. 31.0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = durationInput,
                        onValueChange = { durationInput = it },
                        label = { Text("Duration (e.g. 1:27 h) *") },
                        placeholder = { Text("1:27 h") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = speedInput,
                        onValueChange = { speedInput = it },
                        label = { Text("Avg Speed (km/h)") },
                        placeholder = { Text("e.g. 21") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = odoInput,
                        onValueChange = { odoInput = it },
                        label = { Text("Total Odometer (km)") },
                        placeholder = { Text("e.g. 4052") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val econ = economyInput.toDoubleOrNull() ?: 0.0
                        if (econ <= 0.1) {
                            Toast.makeText(context, "Please enter a valid fuel economy (e.g. 9.8)", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val durMin = MidClusterScanner.parseDurationTextToMinutes(durationInput)
                        val dist = distanceInput.toDoubleOrNull() ?: fuelSummary.distanceKm
                        val spd = speedInput.toDoubleOrNull() ?: fuelSummary.averageSpeedKmh
                        val odo = odoInput.toDoubleOrNull()
                        val updated = MidClusterData(
                            tripId = tripId,
                            durationMinutes = durMin,
                            durationText = durationInput,
                            distanceKm = dist,
                            avgFuelEconomyKmL = econ,
                            avgSpeedKmh = spd,
                            totalOdometerKm = odo,
                            photoUri = midData?.photoUri
                        )
                        midData = updated
                        MidClusterStore.save(context, updated)
                        showEditDialog = false
                        Toast.makeText(context, "MID calibrated to ${econ} km/L", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save & Calibrate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FactorRowCard(
    factorLabel: String,
    methodSubtitle: String,
    liters: Double?,
    kmL: Double?,
    errorPct: Double?,
    verdictBadge: String,
    badgeColor: Color,
    explanation: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DarkSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(factorLabel, color = TextPrimaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(methodSubtitle, color = TextSecondaryDark, fontSize = 9.sp)
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = verdictBadge,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = liters?.let { "${String.format(Locale.US, "%.2f", it)} L" } ?: "--",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("TOTAL BURN", color = TextSecondaryDark, fontSize = 8.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = kmL?.let { "${String.format(Locale.US, "%.2f", it)} km/L" } ?: "--",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("ECONOMY", color = TextSecondaryDark, fontSize = 8.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    val deltaStr = errorPct?.let {
                        val sign = if (it > 0) "+" else ""
                        "$sign${String.format(Locale.US, "%.1f", it)}%"
                    } ?: "--"
                    Text(
                        text = deltaStr,
                        color = if (errorPct == null) TextSecondaryDark else if (kotlin.math.abs(errorPct) <= 6.0) NeonEmerald else if (kotlin.math.abs(errorPct) <= 12.0) ElectricAmber else WarningRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("DELTA VS MID", color = TextSecondaryDark, fontSize = 8.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(explanation, color = TextSecondaryDark, fontSize = 9.sp, lineHeight = 12.sp)
        }
    }
}
