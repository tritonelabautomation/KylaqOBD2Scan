package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed

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

/** The money rows, shared by the trip card and the standalone list. */
@Composable
fun CarpoolEntryRows(
    entry: com.example.data.CarpoolCodec.CarpoolEntry,
    fuelLiters: Double,
    pricePerL: Double
) {
    Text(
        String.format(java.util.Locale.US, "Shared distance %.1f km", entry.distanceKm),
        color = TextSecondaryDark, fontSize = 12.sp
    )
    entry.riders.forEachIndexed { i, r ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(r.name.ifBlank { "Rider ${i + 1}" }, color = TextPrimaryDark, fontSize = 13.sp)
            Text(
                String.format(java.util.Locale.US, "₹%.0f", r.amount),
                color = NeonEmerald, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
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
 * "+" adding a seat. The whenMs handed to onSave is the ride's own instant - the linking key.
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
    var riderNames by remember {
        mutableStateOf((existing?.riders?.map { it.name } ?: listOf("")).toMutableList())
    }
    var riderAmounts by remember {
        mutableStateOf(
            (existing?.riders?.map { String.format(java.util.Locale.US, "%.0f", it.amount) } ?: listOf(""))
                .toMutableList()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Car pool ride") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = { dateStr = it },
                        label = { Text("Date (IST)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = { timeStr = it },
                        label = { Text("Time") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = distance,
                    onValueChange = { distance = it },
                    label = { Text("Shared distance km") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                riderNames.forEachIndexed { i, name ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { v -> riderNames = riderNames.also { it[i] = v } },
                            label = { Text("Rider ${i + 1}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = riderAmounts[i],
                            onValueChange = { v -> riderAmounts = riderAmounts.also { it[i] = v } },
                            label = { Text("₹") },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                        if (riderNames.size > 1) {
                            IconButton(onClick = {
                                riderNames = riderNames.also { it.removeAt(i) }
                                riderAmounts = riderAmounts.also { it.removeAt(i) }
                            }) { Icon(Icons.Default.Close, "Remove rider") }
                        }
                    }
                }
                if (riderNames.size < com.example.data.CarpoolCodec.MAX_RIDERS) {
                    TextButton(onClick = {
                        riderNames = riderNames.also { it.add("") }
                        riderAmounts = riderAmounts.also { it.add("") }
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
                val riders = riderNames.zip(riderAmounts).mapNotNull { (n, a) ->
                    a.toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?.let { com.example.data.CarpoolCodec.Rider(n.trim(), it) }
                }
                // Same IST calendar math as the fuel log: the typed date and time ARE the ride's
                // instant, in the device's IST zone - never UTC-shifted to another day.
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
