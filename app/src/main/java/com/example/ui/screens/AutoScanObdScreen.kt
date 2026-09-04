package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bluetooth.ConnectionState
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoScanObdScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onManualSelect: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val vehicleVin by viewModel.vehicleVin.collectAsState()
    val vinDecodeResult by viewModel.vinDecodeResult.collectAsState()
    
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            viewModel.fetchVehicleVin()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto Scan (OBD)") },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (connectionState != ConnectionState.CONNECTED) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = TextSecondaryDark
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "OBD Adapter Not Connected",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Please connect to an ELM327 adapter on the Dashboard before starting an auto-scan.",
                    color = TextSecondaryDark,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkCanvas)) {
                    Text("Go Back")
                }
            } else if (vehicleVin.isNullOrBlank() || vehicleVin == "VIN Unavailable" || vehicleVin == "Failed to parse VIN") {
                CircularProgressIndicator(color = CyberCyan)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Reading VIN from ECU...",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This may take a few moments...",
                    color = TextSecondaryDark
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { viewModel.fetchVehicleVin() },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface, contentColor = CyberCyan)
                ) {
                    Text("Retry Request")
                }
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = CyberCyan
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "VIN Decoded Successfully",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Detected VIN", color = TextSecondaryDark, style = MaterialTheme.typography.labelMedium)
                        Text(vehicleVin!!, color = CyberCyan, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    "Currently, automated catalogue mapping is in development.\nPlease select your vehicle manually from the catalogue, and we will automatically attach this VIN.",
                    color = TextSecondaryDark,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onManualSelect,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkCanvas),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text("Continue to Catalogue Selection")
                }
            }
        }
    }
}
