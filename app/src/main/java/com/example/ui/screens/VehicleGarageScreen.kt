package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.entities.VehicleEntity
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed

/**
 * My Garage (VehIQ parity): vehicle cards with tap-to-open detail, inline reorder (move up/down
 * persists a manual sort order), quick edit (nickname / plate / odometer / notes) and delete with
 * confirmation. No paywall, no cloud account needed.
 */
@Composable
fun VehicleGarageScreen(
    vehicles: List<VehicleEntity>,
    onAddVehicle: () -> Unit,
    onAutoScan: () -> Unit,
    onSelectVehicle: (VehicleEntity) -> Unit,
    onOpenProfiles: () -> Unit = {},
    onUpdateVehicle: (VehicleEntity) -> Unit = {},
    onDeleteVehicle: (VehicleEntity) -> Unit = {},
    onMoveVehicle: (VehicleEntity, Int) -> Unit = { _, _ -> }
) {
    var editTarget by remember { mutableStateOf<VehicleEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<VehicleEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "My Garage",
                style = MaterialTheme.typography.headlineMedium,
                color = CyberCyan,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${vehicles.size} vehicle(s)",
                color = TextSecondaryDark,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Tap a card for its detail page · ▲▼ reorder · pencil to edit · bin to remove",
            color = TextSecondaryDark,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (vehicles.isEmpty()) {
            Text("No vehicles configured.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(vehicles, key = { it.id }) { vehicle ->
                    val index = vehicles.indexOfFirst { it.id == vehicle.id }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        onClick = { onSelectVehicle(vehicle) },
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val displayName = vehicle.nickname ?: "${vehicle.make} ${vehicle.model}"
                                    Text(displayName, style = MaterialTheme.typography.titleMedium, color = CyberCyan, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${vehicle.make} ${vehicle.model} · ${vehicle.year}" +
                                            (vehicle.licensePlate?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondaryDark
                                    )
                                    val meta = buildString {
                                        vehicle.odometerKm?.let { append("${it} km") }
                                        vehicle.vin?.takeIf { it.isNotBlank() }?.let {
                                            if (isNotEmpty()) append(" · ")
                                            append("VIN ${it.take(11)}…")
                                        }
                                        vehicle.notes?.takeIf { it.isNotBlank() }?.let {
                                            if (isNotEmpty()) append(" · ")
                                            append(it)
                                        }
                                    }
                                    if (meta.isNotBlank()) {
                                        Text(meta, style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark, maxLines = 2)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = { onMoveVehicle(vehicle, -1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDropUp, contentDescription = "Move up", tint = if (index > 0) CyberCyan else TextSecondaryDark)
                                    }
                                    IconButton(
                                        onClick = { onMoveVehicle(vehicle, 1) },
                                        enabled = index < vehicles.lastIndex,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Move down", tint = if (index < vehicles.lastIndex) CyberCyan else TextSecondaryDark)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(onClick = { editTarget = vehicle }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { deleteTarget = vehicle }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WarningRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onAddVehicle, modifier = Modifier.weight(1f)) {
                Text("Manual Add")
            }
            OutlinedButton(onClick = onAutoScan, modifier = Modifier.weight(1f)) {
                Text("Auto Scan (OBD)")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenProfiles,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("1.0 TSI Protocol Profiles")
        }
    }

    editTarget?.let { vehicle ->
        GarageEditDialog(
            vehicle = vehicle,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                onUpdateVehicle(updated)
                editTarget = null
            }
        )
    }

    deleteTarget?.let { vehicle ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove vehicle?") },
            text = {
                Text(
                    "${vehicle.nickname ?: "${vehicle.make} ${vehicle.model}"} and its stored profile " +
                        "leave the garage. Trip recordings already saved stay on this device."
                )
            },
            confirmButton = {
                TextButton(onClick = { onDeleteVehicle(vehicle); deleteTarget = null }) {
                    Text("Delete", color = WarningRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } }
        )
    }
}

@Composable
private fun GarageEditDialog(
    vehicle: VehicleEntity,
    onDismiss: () -> Unit,
    onSave: (VehicleEntity) -> Unit
) {
    var nickname by remember { mutableStateOf(vehicle.nickname ?: "") }
    var plate by remember { mutableStateOf(vehicle.licensePlate ?: "") }
    var odo by remember { mutableStateOf(vehicle.odometerKm?.toString() ?: "") }
    var notes by remember { mutableStateOf(vehicle.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${vehicle.make} ${vehicle.model}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("Nickname") }, singleLine = true)
                OutlinedTextField(value = plate, onValueChange = { plate = it }, label = { Text("Licence plate") }, singleLine = true)
                OutlinedTextField(value = odo, onValueChange = { odo = it.filter(Char::isDigit) }, label = { Text("Odometer km") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        vehicle.copy(
                            nickname = nickname.trim().ifBlank { null },
                            licensePlate = plate.trim().uppercase().ifBlank { null },
                            odometerKm = odo.toIntOrNull(),
                            notes = notes.trim().ifBlank { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
