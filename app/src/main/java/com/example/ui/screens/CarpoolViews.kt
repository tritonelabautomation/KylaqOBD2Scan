package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.CarpoolReceiptScanner
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import kotlinx.coroutines.launch

/**
 * Car-pool views shared by the trip card and the standalone Car Pool screen (owner 2026-09-19:
 * "give me an separate option for car pool logging ... based on date & time input"). The dialog
 * always takes the ride's date and time in IST: that stamp is what lets a ride join the trip
 * whose recorded window covers it, even when the trip was recovered after an abrupt stop.
 */
@Composable
fun CarpoolCard(
    entry: com.example.data.CarpoolCodec.CarpoolEntry?,
    fuelLiters: Double,
    pricePerL: Double,
    onAdd: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = NeonEmerald, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Car pool", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (entry != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete car-pool entry", tint = WarningRed, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (entry == null) {
                Text(
                    "No car-pool logged for this trip. Add the ride's date and time, the shared " +
                        "distance and what each rider paid (up to 4) to see this trip's effective " +
                        "fuel cost.",
                    color = TextSecondaryDark, fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onAdd) { Text("Add car-pool details") }
            } else {
                CarpoolEntryRows(entry, fuelLiters, pricePerL)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onAdd) { Text("Edit") }
            }
        }
    }
}

/**
 * A tappable field that LOOKS like an input but opens a picker (owner 2026-09-20: "showing
 * calender to select ... give time option to select time"). Read-only by design: the value can
 * only change through the system calendar/clock, so it can never hold an unparseable string.
 */
@Composable
private fun PickerField(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 10.sp, color = TextSecondaryDark)
                Text(value, fontSize = 14.sp, color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
            }
            Icon(icon, null, tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
        }
    }
}

/** The money rows, shared by the trip card and the standalone list. */
@Composable
fun CarpoolEntryRows(
    entry: com.example.data.CarpoolCodec.CarpoolEntry,
    fuelLiters: Double,
    pricePerL: Double
) {
    Text(
        String.format(java.util.Locale.US, "Trip shared %.1f km", entry.distanceKm),
        color = TextSecondaryDark, fontSize = 12.sp
    )
    entry.riders.forEachIndexed { i, r ->
        val effDist = r.effectiveDistance(entry.distanceKm)
        val perKm = if (effDist > 0.1) r.amount / effDist else null
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.name.ifBlank { "Rider ${i + 1}" }, color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    String.format(java.util.Locale.US, "₹%.0f", r.amount),
                    color = NeonEmerald, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    String.format(java.util.Locale.US, "%.1f km shared", effDist),
                    color = TextSecondaryDark, fontSize = 11.sp
                )
                if (perKm != null) {
                    Text(
                        String.format(java.util.Locale.US, "₹%.1f/km", perKm),
                        color = TextSecondaryDark, fontSize = 11.sp
                    )
                }
            }
        }
    }
    val fuelCost = if (pricePerL > 0.0 && fuelLiters > 0.01) fuelLiters * pricePerL else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Earned", color = TextSecondaryDark, fontSize = 13.sp)
        Text(
            String.format(java.util.Locale.US, "₹%.0f", entry.earned),
            color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 14.sp
        )
    }
    if (fuelCost != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Fuel cost (app litres x last price)", color = TextSecondaryDark, fontSize = 12.sp)
            Text(
                String.format(java.util.Locale.US, "₹%.0f", fuelCost),
                color = TextPrimaryDark, fontSize = 13.sp
            )
        }
        val eff = fuelCost - entry.earned
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (eff > 0) "Your effective cost" else "Surplus over fuel",
                color = TextSecondaryDark, fontSize = 13.sp
            )
            Text(
                if (eff > 0) String.format(java.util.Locale.US, "₹%.0f", eff)
                else String.format(java.util.Locale.US, "+₹%.0f", -eff),
                color = if (eff > 0) ElectricAmber else NeonEmerald,
                fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
        }
    } else {
        Text(
            "Fuel cost needs this trip's logged litres and a price in the fuel log.",
            color = TextSecondaryDark, fontSize = 11.sp
        )
    }
}

/**
 * Car-pool entry dialog: ride date and time (IST), shared distance, up to four rider rows with
 * "+" adding a seat. Includes instant OCR scanning for ride-share confirmation screenshots.
 */
@Composable
fun CarpoolDialog(
    existing: com.example.data.CarpoolCodec.CarpoolEntry?,
    defaultDistanceKm: Double,
    defaultWhenMs: Long,
    onDismiss: () -> Unit,
    onSave: (Double, List<com.example.data.CarpoolCodec.Rider>, Long) -> Unit
) {
    val baseMs = existing?.let { com.example.data.CarpoolCodec.whenMs(it) } ?: defaultWhenMs
    var distance by remember {
        mutableStateOf(
            existing?.let { String.format(java.util.Locale.US, "%.1f", it.distanceKm) }
                ?: if (defaultDistanceKm > 0.1) String.format(java.util.Locale.US, "%.1f", defaultDistanceKm) else ""
        )
    }
    var dateStr by remember {
        mutableStateOf(com.example.data.RecordTime.format("yyyy-MM-dd", baseMs))
    }
    var timeStr by remember {
        mutableStateOf(com.example.data.RecordTime.format("HH:mm", baseMs))
    }
    val pickerContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    var scanNotice by remember { mutableStateOf<String?>(null) }

    fun openDatePicker() {
        val parts = dateStr.split('-')
        val now = java.util.Calendar.getInstance()
        val y = parts.getOrNull(0)?.toIntOrNull() ?: now.get(java.util.Calendar.YEAR)
        val mo = (parts.getOrNull(1)?.toIntOrNull() ?: (now.get(java.util.Calendar.MONTH) + 1)) - 1
        val d = parts.getOrNull(2)?.toIntOrNull() ?: now.get(java.util.Calendar.DAY_OF_MONTH)
        android.app.DatePickerDialog(
            pickerContext,
            { _, yy, mm, dd -> dateStr = com.example.data.RecordTime.pickedDate(yy, mm + 1, dd) },
            y, mo, d
        ).show()
    }
    fun openTimePicker() {
        val parts = timeStr.split(':')
        val now = java.util.Calendar.getInstance()
        val h = parts.getOrNull(0)?.toIntOrNull() ?: now.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = parts.getOrNull(1)?.toIntOrNull() ?: now.get(java.util.Calendar.MINUTE)
        android.app.TimePickerDialog(
            pickerContext,
            { _, hh, mm -> timeStr = com.example.data.RecordTime.pickedTime(hh, mm) },
            h, mi, true
        ).show()
    }

    val riderNames = remember {
        mutableStateListOf<String>().apply { addAll(existing?.riders?.map { it.name } ?: listOf("")) }
    }
    val riderAmounts = remember {
        mutableStateListOf<String>().apply {
            addAll(existing?.riders?.map { String.format(java.util.Locale.US, "%.0f", it.amount) }
                ?: listOf(""))
        }
    }
    val riderDistances = remember {
        mutableStateListOf<String>().apply {
            addAll(existing?.riders?.map { it.distanceKm?.let { d -> String.format(java.util.Locale.US, "%.1f", d) } ?: "" }
                ?: listOf(""))
        }
    }

    val ocrPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isScanning = true
            scanNotice = "Scanning carpool screenshot..."
            val result = CarpoolReceiptScanner.scanCarpoolImage(pickerContext, uri)
            isScanning = false
            if (result != null && (result.riders.isNotEmpty() || (result.totalEarned ?: 0.0) > 0.0)) {
                riderNames.clear()
                riderAmounts.clear()
                riderDistances.clear()

                if (result.riders.isNotEmpty()) {
                    result.riders.forEach { r ->
                        riderNames.add(r.name)
                        riderAmounts.add(String.format(java.util.Locale.US, "%.0f", r.amount))
                        riderDistances.add(r.distanceKm?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "")
                    }
                } else if (result.totalEarned != null && result.totalEarned > 0.0) {
                    riderNames.add("Rider 1")
                    riderAmounts.add(String.format(java.util.Locale.US, "%.0f", result.totalEarned))
                    riderDistances.add("")
                }

                if (result.totalDistanceKm != null && result.totalDistanceKm > 0.0) {
                    distance = String.format(java.util.Locale.US, "%.1f", result.totalDistanceKm)
                }

                val routeInfo = if (result.routeFrom != null && result.routeTo != null) {
                    " (${result.routeFrom} → ${result.routeTo})"
                } else ""
                val totStr = String.format(java.util.Locale.US, "₹%.0f", result.totalEarned ?: result.riders.sumOf { it.amount })
                scanNotice = "OCR: ${result.riders.size} rider(s) detected · $totStr$routeInfo"
                Toast.makeText(pickerContext, "Carpool OCR Success: ${result.riders.size} rider(s), $totStr", Toast.LENGTH_SHORT).show()
            } else {
                scanNotice = "Could not auto-read carpool data from image. Please enter manually."
                Toast.makeText(pickerContext, "Could not auto-read carpool screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Car pool ride") },
        text = {
            Column {
                OutlinedButton(
                    onClick = { ocrPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonEmerald),
                    border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.6f)),
                    enabled = !isScanning
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonEmerald)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isScanning) "Scanning Screenshot..." else "Scan Carpool Screenshot (OCR)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (scanNotice != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        scanNotice!!,
                        fontSize = 10.sp,
                        color = if (scanNotice!!.startsWith("OCR:")) NeonEmerald else ElectricAmber
                    )
                }
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PickerField(
                        label = "Date (IST)",
                        value = dateStr,
                        icon = Icons.Default.CalendarMonth,
                        modifier = Modifier.weight(1.2f),
                        onClick = ::openDatePicker
                    )
                    PickerField(
                        label = "Time",
                        value = timeStr,
                        icon = Icons.Default.Schedule,
                        modifier = Modifier.weight(0.8f),
                        onClick = ::openTimePicker
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = distance,
                    onValueChange = { distance = it },
                    label = { Text("Total trip distance km") },
                    singleLine = true,
                    supportingText = { Text("Trip's full distance; per-rider below may differ", fontSize = 10.sp) }
                )
                Spacer(Modifier.height(8.dp))
                riderNames.forEachIndexed { i, name ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { v -> riderNames[i] = v },
                                label = { Text("Rider ${i + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = riderAmounts[i],
                                onValueChange = { v -> riderAmounts[i] = v },
                                label = { Text("₹") },
                                singleLine = true,
                                modifier = Modifier.weight(0.6f)
                            )
                            if (riderNames.size > 1) {
                                IconButton(onClick = {
                                    riderNames.removeAt(i)
                                    riderAmounts.removeAt(i)
                                    riderDistances.removeAt(i)
                                }) { Icon(Icons.Default.Close, "Remove rider") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = riderDistances[i],
                                onValueChange = { v -> riderDistances[i] = v },
                                label = { Text("Rider km (optional)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                supportingText = { Text("Leave blank = same as trip", fontSize = 9.sp) }
                            )
                            val d = riderDistances[i].toDoubleOrNull() ?: distance.toDoubleOrNull() ?: 0.0
                            val a = riderAmounts[i].toDoubleOrNull() ?: 0.0
                            val perKm = if (d > 0.1 && a > 0) String.format(java.util.Locale.US, "₹%.1f/km", a / d) else ""
                            if (perKm.isNotEmpty()) {
                                Text(perKm, color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                            }
                        }
                    }
                }
                if (riderNames.size < com.example.data.CarpoolCodec.MAX_RIDERS) {
                    TextButton(onClick = {
                        riderNames.add("")
                        riderAmounts.add("")
                        riderDistances.add("")
                    }) { Text("+ Add rider") }
                } else {
                    Text(
                        "Four riders max - the car seats five including you.",
                        fontSize = 11.sp, color = TextSecondaryDark
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = distance.toDoubleOrNull() ?: return@TextButton
                val riders = riderNames.indices.mapNotNull { idx ->
                    val n = riderNames[idx]
                    val aStr = riderAmounts.getOrNull(idx) ?: ""
                    val distStr = riderDistances.getOrNull(idx) ?: ""
                    val a = aStr.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return@mapNotNull null
                    val rd = distStr.toDoubleOrNull()?.takeIf { it > 0.0 }
                    com.example.data.CarpoolCodec.Rider(n.trim(), a, rd)
                }
                val cal = java.util.Calendar.getInstance()
                val dp = dateStr.split('-')
                val tp = timeStr.split(':')
                if (dp.size == 3 && tp.size == 2) {
                    cal.set(
                        dp[0].toIntOrNull() ?: cal.get(java.util.Calendar.YEAR),
                        (dp[1].toIntOrNull() ?: 1) - 1,
                        dp[2].toIntOrNull() ?: 1,
                        tp[0].toIntOrNull() ?: 12,
                        tp[1].toIntOrNull() ?: 0,
                        0
                    )
                }
                if (d > 0.0 && riders.isNotEmpty()) onSave(d, riders, cal.timeInMillis)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
