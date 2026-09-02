package com.example.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val vehicleName by viewModel.vehicleName.collectAsState()
    val canHeader by viewModel.canHeader.collectAsState()
    val sppUuid by viewModel.sppUuid.collectAsState()
    val pollingMode by viewModel.pollingMode.collectAsState()

    val settingsRepo = viewModel.settingsRepository
    val cloudManager = viewModel.cloudBackupManager
    val googleEmail by settingsRepo.googleAccountEmail.collectAsState()
    val autoCloudBackup by settingsRepo.autoCloudBackup.collectAsState()
    val lastBackupTimestamp by settingsRepo.lastBackupTimestamp.collectAsState()

    val isSyncing by cloudManager.isSyncing.collectAsState()
    val syncStatusMessage by cloudManager.syncStatusMessage.collectAsState()

    var tempVehicleName by remember(vehicleName) { mutableStateOf(vehicleName) }
    var tempCanHeader by remember(canHeader) { mutableStateOf(canHeader) }
    var tempSppUuid by remember(sppUuid) { mutableStateOf(sppUuid) }

    LaunchedEffect(syncStatusMessage) {
        syncStatusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            cloudManager.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App & Cloud Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_settings_back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Google Drive & Cloud Backup
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_cloud_backup"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Google Drive / Cloud Backup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sync and preserve OBD trips across reinstalls & devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    // Account Status Row
                    if (googleEmail != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Connected Google Account", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(googleEmail ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NeonEmerald)
                            }
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        cloudManager.signOut()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("btn_google_sign_out")
                            ) {
                                Text("Sign Out", fontSize = 12.sp)
                            }
                        }

                        // Auto-Backup Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automatic Cloud Backup", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Automatically sync trip bundles after each recording session finishes",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoCloudBackup,
                                onCheckedChange = { settingsRepo.setAutoCloudBackup(it) },
                                modifier = Modifier.testTag("switch_auto_backup")
                            )
                        }

                        // Last Backup Info
                        val lastBackupFormatted = if (lastBackupTimestamp > 0) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastBackupTimestamp))
                        } else {
                            "Never"
                        }
                        Text(
                            text = "Last synchronized: $lastBackupFormatted",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Backup / Restore Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        cloudManager.performBackupNow()
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("btn_backup_now"),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyanDark),
                                enabled = !isSyncing
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text("Back Up Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        cloudManager.restoreFromCloud()
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("btn_restore_cloud"),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isSyncing
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonEmerald)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore / Sync", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonEmerald)
                            }
                        }
                    } else {
                        // Sign-in CTA
                        Text(
                            text = "Sign in to securely backup your diagnostic trip logs to Google Drive. Your records will be preserved even if the app is reinstalled or transferred to a new phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    cloudManager.signInWithGoogle(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("btn_google_sign_in"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyanDark)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign in with Google", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Section 2: Vehicle & Hardware Profile
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Vehicle & Adapter Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    OutlinedTextField(
                        value = tempVehicleName,
                        onValueChange = {
                            tempVehicleName = it
                            settingsRepo.setVehicleName(it)
                        },
                        label = { Text("Vehicle Descriptor") },
                        modifier = Modifier.fillMaxWidth().testTag("input_vehicle_name"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = tempCanHeader,
                        onValueChange = {
                            tempCanHeader = it
                            settingsRepo.setCanHeader(it)
                        },
                        label = { Text("Broadcast CAN Header (e.g. 7DF / 7E0)") },
                        modifier = Modifier.fillMaxWidth().testTag("input_can_header"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = tempSppUuid,
                        onValueChange = {
                            tempSppUuid = it
                            settingsRepo.setSppUuid(it)
                        },
                        label = { Text("Bluetooth Classic SPP UUID") },
                        modifier = Modifier.fillMaxWidth().testTag("input_spp_uuid"),
                        singleLine = true
                    )
                }
            }

            // Section 3: Safe Storage & Retention Policy
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Data Retention Guarantee", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(
                        text = "• Local logs and Room database files will NEVER be pruned or wiped automatically.\n" +
                               "• Android AutoBackup and Device-to-Device transfer rules are configured to preserve diagnostic history across OS upgrades.\n" +
                               "• Failure of cloud sync will NEVER delete local trip files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
