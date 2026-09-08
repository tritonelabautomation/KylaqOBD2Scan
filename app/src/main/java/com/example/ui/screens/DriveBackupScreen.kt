package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.backup.DriveBackupClient
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Fuelio-style Google Drive backup that needs NO OAuth client: the system folder picker opens the
 * user's Drive (with the normal Google account chooser when signed out), we keep a persistable
 * permission, and backups flow as ZIP files both ways.
 */
@Composable
fun DriveBackupScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings = viewModel.settingsRepository
    val scope = rememberCoroutineScope()
    var treeUri by remember { mutableStateOf(settings.driveTreeUri()?.let { Uri.parse(it) }) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<DriveBackupClient.BackupFile>>(emptyList()) }
    val autoBackup by settings.autoCloudBackup.collectAsState()
    val lastBackup by settings.lastBackupTimestamp.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settings.setDriveTreeUri(uri.toString())
            treeUri = uri
            status = "Drive folder linked. Backups will be written here."
        }
    }

    fun refreshList() {
        treeUri?.let { uri -> backups = DriveBackupClient.listBackups(context, uri) }
    }

    LaunchedEffect(treeUri) { refreshList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Backup", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "No OAuth setup needed",
                            color = NeonEmerald, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Unlike sign-in based sync, this uses Android's system folder picker: " +
                                "choose (or create) a folder inside your Google Drive. If Drive asks " +
                                "you to choose an account, that is Google's normal chooser. The app " +
                                "then keeps permission to that folder only - no cloud credentials " +
                                "are stored.",
                            color = TextSecondaryDark, fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { picker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (treeUri == null) "Choose folder on Google Drive" else "Change Drive folder")
                        }
                        if (treeUri != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        busy = true
                                        status = "Sending backup…"
                                        scope.launch {
                                            status = try {
                                                val name = DriveBackupClient.sendBackup(
                                                    context, treeUri!!, viewModel.recordingManager
                                                )
                                                settings.setLastBackupTimestamp(System.currentTimeMillis())
                                                refreshList()
                                                "Sent $name to Drive."
                                            } catch (e: Exception) {
                                                "Backup failed: ${e.message}"
                                            }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Send backup now") }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Auto-backup after every recording", color = TextSecondaryDark, fontSize = 12.sp)
                                Switch(checked = autoBackup, onCheckedChange = { settings.setAutoCloudBackup(it) })
                            }
                            Text(
                                "Last backup: " + (if (lastBackup > 0) java.util.Date(lastBackup).toString() else "never"),
                                color = TextSecondaryDark, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            item {
                status?.let {
                    Text(
                        it,
                        color = if (it.startsWith("Backup failed") || it.startsWith("Restore failed")) WarningRed else ElectricAmber,
                        fontSize = 11.sp
                    )
                }
            }
            item {
                Text(
                    "BACKUPS IN THIS FOLDER (${backups.size})",
                    color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            if (backups.isEmpty() && treeUri != null) {
                item { Text("No backup ZIPs yet.", color = TextSecondaryDark, fontSize = 11.sp) }
            }
            items(backups, key = { it.name }) { file ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, color = CyberCyan, fontSize = 12.sp)
                            Text(
                                "${file.sizeBytes / 1024} KB · ${java.util.Date(file.modifiedMs)}",
                                color = TextSecondaryDark, fontSize = 10.sp
                            )
                        }
                        TextButton(
                            onClick = {
                                busy = true
                                status = "Restoring ${file.name}…"
                                scope.launch {
                                    status = try {
                                        "Restore finished: " + DriveBackupClient.restoreBackup(
                                            context, file.uri, viewModel.recordingManager
                                        )
                                    } catch (e: Exception) {
                                        "Restore failed: ${e.message}"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("Restore") }
                    }
                }
            }
        }
    }
}
