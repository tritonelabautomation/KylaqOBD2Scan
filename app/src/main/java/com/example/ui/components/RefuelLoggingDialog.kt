package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.analysis.FuelReceiptScanner
import com.example.analysis.MidClusterScanner
import com.example.data.BunkNozzleStore
import com.example.data.FuelLogCodec
import com.example.data.RefuelBunkRecord
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

/**
 * Advanced Refuel & Auto-Cut Off Logger Dialog with Multi-pass OCR for Pump Receipts
 * and MID "Since Refuel" Cluster Photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelLoggingDialog(
    initialLiters: Double? = null,
    initialPrice: Double? = null,
    initialOdoKm: Double? = null,
    initialPreLevelPct: Double? = null,
    initialPostLevelPct: Double? = null,
    initialStation: String = "",
    initialGrade: String = FuelLogCodec.GRADE_X95,
    initialNote: String = "",
    onDismiss: () -> Unit,
    onSaved: (RefuelBunkRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var liters by remember { mutableStateOf(initialLiters?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var price by remember { mutableStateOf(initialPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "108.50") }
    var total by remember {
        mutableStateOf(
            if (initialLiters != null && initialPrice != null)
                String.format(Locale.US, "%.2f", initialLiters * initialPrice)
            else ""
        )
    }
    var odo by remember { mutableStateOf(initialOdoKm?.let { String.format(Locale.US, "%.0f", it) } ?: "") }
    var station by remember { mutableStateOf(initialStation) }
    var nozzle by remember { mutableStateOf("Nozzle #1") }
    var grade by remember { mutableStateOf(if (initialGrade.isNotBlank() && initialGrade != FuelLogCodec.GRADE_UNKNOWN) initialGrade else FuelLogCodec.GRADE_X95) }
    var preLevelPct by remember { mutableStateOf(initialPreLevelPct?.let { String.format(Locale.US, "%.1f", it) } ?: "") }
    var postLevelPct by remember { mutableStateOf(initialPostLevelPct?.let { String.format(Locale.US, "%.1f", it) } ?: "100.0") }

    var midDistanceKm by remember { mutableStateOf("") }
    var midEconomyKmL by remember { mutableStateOf("") }
    var midRangeKm by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(initialNote) }

    var receiptPhotoUri by remember { mutableStateOf<String?>(null) }
    var midPhotoUri by remember { mutableStateOf<String?>(null) }

    var isScanningReceipt by remember { mutableStateOf(false) }
    var isScanningMid by remember { mutableStateOf(false) }
    var ocrStatusMessage by remember { mutableStateOf<String?>(null) }

    val recentStations = listOf("HPCL HiTech City", "IOCL Gachibowli", "Shell Kondapur", "BPCL Madhapur", "Jio-bp ORR")
    val nozzleOptions = listOf("Nozzle #1", "Nozzle #2", "Nozzle #3", "Nozzle #4", "Nozzle #5", "Nozzle #6")
    val gradeOptions = listOf(FuelLogCodec.GRADE_X95, FuelLogCodec.GRADE_REGULAR, "Speed 97", "Power 95", "XP95")

    // Image Picker for Fuel Receipt / Pump Dispenser Screen OCR
    val receiptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        receiptPhotoUri = uri.toString()
        scope.launch {
            isScanningReceipt = true
            ocrStatusMessage = "Analyzing pump receipt with multi-pass OCR..."
            val result = FuelReceiptScanner.scanReceipt(context, uri)
            isScanningReceipt = false
            if (result != null) {
                result.litres?.let { liters = String.format(Locale.US, "%.2f", it) }
                result.pricePerL?.let { price = String.format(Locale.US, "%.2f", it) }
                result.totalAmount?.let { total = String.format(Locale.US, "%.2f", it) }
                result.stationName?.let { station = it }
                result.fuelGrade?.let { grade = it }
                result.nozzle?.let { nozzle = it }

                // Auto-calculate total if missing
                val l = liters.toDoubleOrNull()
                val p = price.toDoubleOrNull()
                if (total.isBlank() && l != null && p != null && p > 0) {
                    total = String.format(Locale.US, "%.2f", l * p)
                }

                ocrStatusMessage = "Receipt OCR: ${result.litres ?: "?"} L @ ₹${result.pricePerL ?: "?"} (${result.stationName ?: "Pump"})"
                Toast.makeText(context, "Receipt Scanned: ${result.litres ?: 0.0} L", Toast.LENGTH_SHORT).show()
            } else {
                ocrStatusMessage = "OCR couldn't extract all fields automatically. Photo attached."
                Toast.makeText(context, "Could not auto-detect all fields. Please confirm numbers.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Image Picker for MID Cluster "Since Refuel" Photo OCR
    val midPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        midPhotoUri = uri.toString()
        scope.launch {
            isScanningMid = true
            ocrStatusMessage = "Extracting MID Since-Refuel metrics..."
            val scanned = MidClusterScanner.scanClusterImage(context, uri, "refuel_${System.currentTimeMillis()}")
            isScanningMid = false
            if (scanned != null) {
                if (scanned.distanceKm > 0) midDistanceKm = String.format(Locale.US, "%.1f", scanned.distanceKm)
                if (scanned.avgFuelEconomyKmL > 0) midEconomyKmL = String.format(Locale.US, "%.1f", scanned.avgFuelEconomyKmL)
                ocrStatusMessage = "MID OCR: ${scanned.avgFuelEconomyKmL} km/L, ${scanned.distanceKm} km"
                Toast.makeText(context, "MID photo parsed successfully!", Toast.LENGTH_SHORT).show()
            } else {
                ocrStatusMessage = "MID OCR could not detect all digits clearly. Photo attached."
            }
        }
    }

    // Calculations for dynamic 3-factor calibration preview
    val pumpLVal = liters.toDoubleOrNull() ?: 0.0
    val preLvlVal = preLevelPct.toDoubleOrNull() ?: 0.0
    val postLvlVal = postLevelPct.toDoubleOrNull() ?: 100.0
    val deltaLvlVal = maxOf(0.0, postLvlVal - preLvlVal)
    val floatLVal = (deltaLvlVal / 100.0) * 50.0
    val floatErrorPct = if (pumpLVal > 0.1) ((pumpLVal - floatLVal) / pumpLVal) * 100.0 else 0.0
    val calibratedCapL = if (deltaLvlVal > 5.0 && pumpLVal > 0.5) (pumpLVal / (deltaLvlVal / 100.0)) else 50.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Refuel & Auto-Cut Profiling", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimaryDark)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // OCR Quick Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { receiptPicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.25f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isScanningReceipt) "Reading..." else "Scan Receipt",
                            color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = { midPicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricAmber.copy(alpha = 0.25f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricAmber)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isScanningMid) "Reading..." else "MID Photo",
                            color = ElectricAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                ocrStatusMessage?.let { msg ->
                    Text(
                        msg,
                        color = CyberCyan,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Photo Previews if attached
                if (receiptPhotoUri != null || midPhotoUri != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        receiptPhotoUri?.let { uri ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                    .background(DarkSurface)
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                AsyncImage(
                                    model = Uri.parse(uri),
                                    contentDescription = "Receipt Photo",
                                    modifier = Modifier.height(70.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Text("Receipt OCR", color = TextSecondaryDark, fontSize = 10.sp)
                            }
                        }
                        midPhotoUri?.let { uri ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                                    .background(DarkSurface)
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                AsyncImage(
                                    model = Uri.parse(uri),
                                    contentDescription = "MID Cluster Photo",
                                    modifier = Modifier.height(70.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Text("MID Cluster", color = TextSecondaryDark, fontSize = 10.sp)
                            }
                        }
                    }
                }

                HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 2.dp))

                // Fuel Station & Dispenser Nozzle
                OutlinedTextField(
                    value = station,
                    onValueChange = { station = it },
                    label = { Text("Petrol Bunk / Station") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick Station Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    recentStations.take(3).forEach { st ->
                        SuggestionChip(
                            onClick = { station = st },
                            label = { Text(st.substringBefore(" "), fontSize = 10.sp) }
                        )
                    }
                }

                // Dispenser Nozzle Selection
                Text("Dispenser Nozzle (for Auto-Cut profiling):", color = TextSecondaryDark, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    nozzleOptions.take(4).forEach { noz ->
                        FilterChip(
                            selected = nozzle == noz,
                            onClick = { nozzle = noz },
                            label = { Text(noz, fontSize = 10.sp) }
                        )
                    }
                }

                // Fuel Grade
                Text("Fuel Grade:", color = TextSecondaryDark, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gradeOptions.take(3).forEach { g ->
                        FilterChip(
                            selected = grade == g,
                            onClick = { grade = g },
                            label = { Text(g, fontSize = 10.sp) }
                        )
                    }
                }

                // Liters, Price, and Total
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = liters,
                        onValueChange = {
                            liters = it
                            val l = it.toDoubleOrNull()
                            val p = price.toDoubleOrNull()
                            if (l != null && p != null && p > 0) total = String.format(Locale.US, "%.2f", l * p)
                        },
                        label = { Text("Pumped L") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = price,
                        onValueChange = {
                            price = it
                            val p = it.toDoubleOrNull()
                            val l = liters.toDoubleOrNull()
                            if (p != null && l != null && p > 0) total = String.format(Locale.US, "%.2f", l * p)
                        },
                        label = { Text("₹ / Litre") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = total,
                    onValueChange = {
                        total = it
                        val t = it.toDoubleOrNull()
                        val p = price.toDoubleOrNull()
                        if (t != null && p != null && p > 0 && liters.isBlank()) {
                            liters = String.format(Locale.US, "%.2f", t / p)
                        }
                    },
                    label = { Text("Total Amount ₹") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Tank Level Before & Auto-Cut Off Level (PID 012F)
                Text("PID 012F Tank Level & Auto-Cut Off %:", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = preLevelPct,
                        onValueChange = { preLevelPct = it },
                        label = { Text("Start Level %") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = postLevelPct,
                        onValueChange = { postLevelPct = it },
                        label = { Text("Auto-Cut %") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Odometer
                OutlinedTextField(
                    value = odo,
                    onValueChange = { odo = it },
                    label = { Text("Cluster Odometer (km)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // MID Since-Refuel inputs (optional)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = midDistanceKm,
                        onValueChange = { midDistanceKm = it },
                        label = { Text("MID Since Refuel km") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = midEconomyKmL,
                        onValueChange = { midEconomyKmL = it },
                        label = { Text("MID km/L") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Live Calibration & Discrepancy Card
                if (pumpLVal > 0.5 && deltaLvlVal > 2.0) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("3-FACTOR AUTO-CUT CALIBRATION", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Pump Ground Truth:", color = TextSecondaryDark, fontSize = 11.sp)
                                Text(String.format(Locale.US, "%.2f L", pumpLVal), color = NeonEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("PID 012F Float Delta (${String.format(Locale.US, "%.1f", deltaLvlVal)}%):", color = TextSecondaryDark, fontSize = 11.sp)
                                Text(String.format(Locale.US, "%.2f L", floatLVal), color = ElectricAmber, fontSize = 11.sp)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Float Sensor Under/Over Error:", color = TextSecondaryDark, fontSize = 11.sp)
                                val sign = if (floatErrorPct > 0) "+" else ""
                                Text(
                                    String.format(Locale.US, "%s%.1f%% (%s%.2f L)", sign, floatErrorPct, sign, pumpLVal - floatLVal),
                                    color = if (kotlin.math.abs(floatErrorPct) <= 3.0) NeonEmerald else WarningRed,
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Derived Calibrated Tank Size:", color = TextSecondaryDark, fontSize = 11.sp)
                                Text(String.format(Locale.US, "%.1f L (vs 50.0L nominal)", calibratedCapL), color = CyberCyan, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lVal = liters.toDoubleOrNull()
                    val pVal = price.toDoubleOrNull() ?: 0.0
                    val totVal = total.toDoubleOrNull() ?: (if (lVal != null) lVal * pVal else 0.0)
                    val cutVal = postLevelPct.toDoubleOrNull() ?: 100.0
                    val startVal = preLevelPct.toDoubleOrNull() ?: 0.0
                    val odoVal = odo.toDoubleOrNull()
                    val distVal = midDistanceKm.toDoubleOrNull()
                    val econVal = midEconomyKmL.toDoubleOrNull()
                    val rangeVal = midRangeKm.toDoubleOrNull()

                    if (lVal != null && lVal > 0.0) {
                        val record = RefuelBunkRecord(
                            stationName = station.ifBlank { "Petrol Pump" },
                            nozzleId = nozzle,
                            fuelGrade = grade,
                            autoCutPercent = cutVal,
                            startLevelPercent = startVal,
                            pumpLitres = lVal,
                            floatDeltaLitres = maxOf(0.0, (cutVal - startVal) / 100.0 * 50.0),
                            pricePerL = pVal,
                            totalCost = totVal,
                            odometerKm = odoVal,
                            midSinceRefuelKm = distVal,
                            midEconomyKmL = econVal,
                            midRangeKm = rangeVal,
                            receiptPhotoUri = receiptPhotoUri,
                            midPhotoUri = midPhotoUri,
                            note = note
                        )
                        BunkNozzleStore.save(context, record)
                        onSaved(record)
                    } else {
                        Toast.makeText(context, "Please enter pumped litres", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("Save & Calibrate", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}
