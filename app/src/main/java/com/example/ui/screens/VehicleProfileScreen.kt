package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.catalog.CatalogRepository
import com.example.data.catalog.CatalogVariantDetails
import com.example.data.db.entities.VehicleEntity
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleProfileScreen(
    vehicle: VehicleEntity?,
    catalogRepository: CatalogRepository,
    onBack: () -> Unit,
    onNavigateToDtc: () -> Unit,
    onNavigateToPidScanner: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vehicle?.nickname ?: vehicle?.make ?: "Vehicle Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkCanvas,
                    titleContentColor = TextPrimaryDark,
                    navigationIconContentColor = TextPrimaryDark
                )
            )
        },
        containerColor = DarkCanvas
    ) { padding ->
        var variantDetails by remember { mutableStateOf<CatalogVariantDetails?>(null) }
        
        LaunchedEffect(vehicle?.catalogVariantId) {
            vehicle?.catalogVariantId?.let {
                variantDetails = catalogRepository.getVariantDetails(it)
            }
        }

        if (vehicle == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                Text("Vehicle not found", modifier = Modifier.padding(16.dp), color = TextPrimaryDark)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "${vehicle.year} ${vehicle.make} ${vehicle.model}",
                style = MaterialTheme.typography.headlineMedium,
                color = CyberCyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                "VIN: ${vehicle.vin ?: "UNAVAILABLE"}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondaryDark,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Vehicle Details
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CyberCyan)
                        Text(" Specs & Details", color = TextPrimaryDark, style = MaterialTheme.typography.titleMedium)
                    }
                    Divider(color = DarkBorder, modifier = Modifier.padding(bottom = 8.dp))
                    

                    val engineName = variantDetails?.engine?.let { "${it.name} (${it.displacementCc ?: "?"} cc)" } ?: "Unknown"
                    val transName = variantDetails?.transmission?.let { "${it.name} (${it.type ?: "?"})" } ?: "Unknown"
                    val fuelName = variantDetails?.engine?.fuelType ?: "Unknown"

                    ProfileDetailRow("Engine", engineName)
                    ProfileDetailRow("Transmission", transName)
                    ProfileDetailRow("Fuel Type", fuelName)

                    ProfileDetailRow("Mileage", vehicle.odometerKm?.let { "$it km" } ?: "Not recorded")
                }
            }

            // Health & Diagnostics
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = CyberCyan)
                        Text(" Vehicle Health", color = TextPrimaryDark, style = MaterialTheme.typography.titleMedium)
                    }
                    Divider(color = DarkBorder, modifier = Modifier.padding(bottom = 8.dp))
                    
                    ProfileDetailRow("Overall Health", "Unknown (Scan Required)")
                    ProfileDetailRow("Last Scan", "Never")
                    ProfileDetailRow("Active DTCs", "Unknown")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateToDtc,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.HealthAndSafety, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DTC Scanner", fontSize = 13.sp)
                        }
                        Button(
                            onClick = onNavigateToPidScanner,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ResearchPurple, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PID Discovery", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Ownership
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        Icon(Icons.Default.CarRepair, contentDescription = null, tint = CyberCyan)
                        Text(" Ownership", color = TextPrimaryDark, style = MaterialTheme.typography.titleMedium)
                    }
                    Divider(color = DarkBorder, modifier = Modifier.padding(bottom = 8.dp))
                    
                    ProfileDetailRow("Avg Fuel Economy", "Not enough data")
                    ProfileDetailRow("Next Maintenance", "Not set")
                }
            }
        }
    }
}

@Composable
fun ProfileDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondaryDark)
        Text(value, color = TextPrimaryDark, fontWeight = FontWeight.Medium)
    }
}
