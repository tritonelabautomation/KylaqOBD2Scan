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
                            Text("${vehicle.make} ${vehicle.model}", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                            Text("Year: ${vehicle.year}", style = MaterialTheme.typography.bodyMedium)
                            Text("VIN: ${vehicle.vin ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
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
