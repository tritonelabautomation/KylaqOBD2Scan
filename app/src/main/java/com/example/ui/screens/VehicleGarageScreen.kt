package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.db.entities.VehicleEntity
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface

@Composable
fun VehicleGarageScreen(
    vehicles: List<VehicleEntity>,
    onAddVehicle: () -> Unit,
    onSelectVehicle: (VehicleEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("My Garage", style = MaterialTheme.typography.headlineMedium, color = CyberCyan)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (vehicles.isEmpty()) {
            Text("No vehicles configured.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn {
                items(vehicles) { vehicle ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        onClick = { onSelectVehicle(vehicle) },
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val displayName = vehicle.nickname ?: "${vehicle.make} ${vehicle.model}"
                            Text(displayName, style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                            if (vehicle.nickname != null) {
                                Text("${vehicle.make} ${vehicle.model} (${vehicle.year})", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("Year: ${vehicle.year}", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (vehicle.vin != null) {
                                Text("VIN: ${vehicle.vin}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (vehicle.catalogVariantId != null) {
                                Text("Catalog Variant: ${vehicle.catalogVariantId}", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddVehicle, modifier = Modifier.fillMaxWidth()) {
            Text("Add Vehicle")
        }
    }
}
