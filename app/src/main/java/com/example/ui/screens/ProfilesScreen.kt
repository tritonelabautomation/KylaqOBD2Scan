package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.model.DiagnosticProfile
import com.example.model.DiagnosticResult
import com.example.model.ProfileTestStatus
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ProfilesViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val testResults by viewModel.testResults.collectAsState()
    val isRunningTest by viewModel.isRunningTest.collectAsState()
    val testProgress by viewModel.testProgress.collectAsState()
    val connectionState by mainViewModel.connectionState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("1.0 TSI Protocol Profiles") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            
            // Warning block
            Surface(
                color = WarningRed.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    text = "CAUTION: Experimental VW/VAG and UDS identifiers require ECU verification. Do not assume values are confirmed until physically verified. Strictly READ-ONLY.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningRed,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (selectedProfile == null) {
                // Profile selection
                Text(
                    text = "Select a Diagnostic Profile",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(profiles) { profile ->
                        ProfileCard(profile = profile) {
                            viewModel.selectProfile(profile.id)
                        }
                    }
                }
            } else {
                // Testing View
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedProfile!!.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { viewModel.selectProfile("") }) {
                        Text("Change")
                    }
                }
                
                Text(
                    text = selectedProfile!!.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.runProfileTest(mainViewModel.activeTransport) },
                        enabled = !isRunningTest && connectionState == com.example.bluetooth.ConnectionState.CONNECTED,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start Test")
                    }
                    
                    if (testResults.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { 
                                val path = viewModel.exportResults(testResults)
                                val file = File(path)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Export"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export CSV")
                        }
                    }
                }

                if (isRunningTest) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { testProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = CyberCyan
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(testResults) { result ->
                        ResultCard(result)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileCard(profile: DiagnosticProfile, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = profile.name, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = profile.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Badge(containerColor = CyberCyan.copy(alpha = 0.2f), contentColor = CyberCyan) {
                Text("${profile.requests.size} Requests", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun ResultCard(result: DiagnosticResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${result.request.name} (${result.request.requestCommand})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                val statusColor = when (result.status) {
                    ProfileTestStatus.PARSED, ProfileTestStatus.RESPONSE_RECEIVED -> NeonEmerald
                    ProfileTestStatus.EXPERIMENTAL -> ElectricAmber
                    ProfileTestStatus.TIMEOUT, ProfileTestStatus.UNSUPPORTED -> WarningRed
                    else -> Color.Gray
                }
                
                Text(
                    text = result.status.name,
                    color = statusColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Parsed Value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = result.parsedValue,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = CyberCyan
                    )
                }
                Text(
                    text = "${result.responseTimeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Surface(
                color = Color(0xFF090D12),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "TX: ${result.rawTx}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "RX: ${result.rawRx}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = ResearchPurple
                    )
                }
            }
        }
    }
}
