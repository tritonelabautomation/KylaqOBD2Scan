package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.data.SavedRecording
import com.example.data.db.StorageStats
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RecordingsScreen(
    viewModel: MainViewModel,
    onNavigateToTripDetail: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val savedRecordings by viewModel.savedRecordings.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importStatusMessage by viewModel.importStatusMessage.collectAsState()
    val tripRepo = viewModel.recordingManager.tripRepository

    // Unsaved raw-log recovery (owner 2026-09-15): drives killed before STOP could
    // finalize them never reached Room, but their raw OBD log was flushed to disk line
    // by line, so they can still be rebuilt here — no PC, no adb, no re-recording.
    val unsavedRawLogs by viewModel.unsavedRawLogs.collectAsState()
    val isRecovering by viewModel.isRecovering.collectAsState()
    val recoveryNotice by viewModel.recoveryNotice.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshUnsavedRawLogs() }

    var renamingRecording by remember { mutableStateOf<SavedRecording?>(null) }
    var deletingRecording by remember { mutableStateOf<SavedRecording?>(null) }
    var storageStats by remember { mutableStateOf<StorageStats?>(null) }
    var showStorageDialog by remember { mutableStateOf(false) }

    // SAF Open Multiple Documents Launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importZipUris(uris)
        }
    }

    LaunchedEffect(savedRecordings) {
        storageStats = tripRepo.getStorageStats()
    }

    // Cross-trip trends (rpm / speed / load / torque / idle-vs-model) computed from the
    // stored Room telemetry samples of the newest recorded trips.
    var trends by remember { mutableStateOf<List<com.example.analysis.TripTrendPoint>>(emptyList()) }
    LaunchedEffect(savedRecordings.size) {
        trends = runCatching { viewModel.computeTripTrends() }.getOrDefault(emptyList())
    }

    LaunchedEffect(importStatusMessage) {
        importStatusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearImportStatusMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("recordings_screen")
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("btn_recordings_back")) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Trips & Recordings (${savedRecordings.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Persistent Room Database & Diagnostic Files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    modifier = Modifier.testTag("btn_import_zip")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Import ZIP", tint = CyberCyan)
                }
                IconButton(onClick = { showStorageDialog = true }) {
                    Icon(Icons.Default.Storage, contentDescription = "Storage", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Import & Storage Actions Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Policy: Persist Until User Deletes",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Button(
                    onClick = {
                        zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    modifier = Modifier.height(34.dp).testTag("btn_import_logs_zip"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyanDark),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    enabled = !isImporting
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Importing...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import ZIP", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        // ── Recovery banner: unsaved raw-log sessions still on this phone ──────────
        if (unsavedRawLogs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("recovery_banner"),
                shape = RoundedCornerShape(12.dp),
                color = ElectricAmber.copy(alpha = 0.14f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SettingsBackupRestore,
                            contentDescription = null,
                            tint = ElectricAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${unsavedRawLogs.size} unsaved log session(s) found on this phone",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recovery now runs BY ITSELF when the app or its keep-alive service " +
                            "starts, so a killed drive is normally rebuilt before you ever reach " +
                            "this screen. Anything listed here still needs a tap: its journal was " +
                            "unreadable, or it was recorded by an older build. Every OBD line stayed " +
                            "on disk, and Recover rebuilds the full trip — fuel, trends and X-ray.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    unsavedRawLogs.forEach { logFile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "session " +
                                        (com.example.analysis.RawLogRecovery.sessionIdOf(logFile.name) ?: logFile.name),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                        .format(java.util.Date(logFile.lastModified())) +
                                        " · ${logFile.length() / 1024} KB raw log",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Never a dead grey disabled button (owner 2026-09-16: the
                            // greyed spinner read as "UI stuck"). Stays amber and says
                            // what it is doing; re-entry is guarded in the ViewModel.
                            Button(
                                onClick = { viewModel.recoverRawLog(logFile) },
                                modifier = Modifier.height(30.dp).testTag("btn_recover_raw_log"),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricAmber),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                if (isRecovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Color.Black,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Recovering…", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                } else {
                                    Text("Recover", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                        }
                    }
                    recoveryNotice?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            it,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = ElectricAmber
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp)
        ) {
            if (savedRecordings.isEmpty()) {
                item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No saved recording runs yet.\nStart a diagnostic recording from Dashboard or import existing ZIP log bundles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Select .ZIP File to Import", color = CyberCyan)
                        }
                    }
                }
                }
            } else {
                items(savedRecordings, key = { it.metadata.sessionId }) { rec ->
                    RecordingItemCard(
                        recording = rec,
                        onClick = { onNavigateToTripDetail(rec.metadata.sessionId) },
                        onShareFile = { file, mimeType -> shareFile(context, file, mimeType) },
                        onRename = { renamingRecording = rec },
                        onDelete = { deletingRecording = rec }
                    )
                }
                item {
                // ---- Vehicle trends across recorded trips (owner-requested) ----
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "VEHICLE TRENDS - your last ${trends.size} recorded trip(s)",
                            color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        if (trends.size >= 2) {
                            TrendRow("avg engine rpm", trends.mapNotNull { it.avgRpm }, "%.0f", NeonEmerald)
                            TrendRow("avg speed (km/h)", trends.mapNotNull { it.avgSpeedKmh }, "%.1f", CyberCyan)
                            TrendRow("avg engine load (%)", trends.mapNotNull { it.avgLoadPct }, "%.1f", ElectricAmber)
                            TrendRow("avg torque (Nm, from PID 0162)", trends.mapNotNull { it.avgTorqueNm }, "%.1f", NeonEmerald)
                            TrendRow("est. mech power (kW, 2π·N·T/60)", trends.mapNotNull { it.avgPowerKw }, "%.1f", ResearchPurple)
                            TrendRow("est. gear (most-used while moving)", trends.mapNotNull { it.modeGear?.toDouble() }, "%.0f", NeonEmerald)
                            trends.lastOrNull()?.takeIf { it.modeGear != null }?.let { t ->
                                Text(
                                    "latest trip: gears ${t.gearMin}–${t.gearMax}, mostly gear ${t.modeGear} (est. from rpm/speed ratio)",
                                    color = TextSecondaryDark, fontSize = 12.sp
                                )
                            }
                            val idlePts = trends.filter { it.idleActualLh != null }
                            if (idlePts.size >= 2) {
                                Text(
                                    "idle burn vs math model (model = %.2f L/h):".format(java.util.Locale.US, com.example.analysis.TripTrendAnalyzer.MODEL_IDLE_LH),
                                    color = TextSecondaryDark, fontSize = 12.sp
                                )
                                SimpleLineChart(
                                    idlePts.mapNotNull { it.idleActualLh }.map { it.toFloat() },
                                    Modifier.fillMaxWidth().height(56.dp),
                                    ElectricAmber
                                )
                                val f = idlePts.first()
                                val l = idlePts.last()
                                Text(
                                    "idle ${String.format(java.util.Locale.US, "%.2f", f.idleActualLh!!)} → ${String.format("%.2f", l.idleActualLh!!)} L/h " +
                                        "(model %.2f)".format(java.util.Locale.US, com.example.analysis.TripTrendAnalyzer.MODEL_IDLE_LH) +
                                        (l.idleExcessPct?.let { " · latest ${if (it >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.0f", it)}% vs model" } ?: ""),
                                    color = if ((l.idleExcessPct ?: 0.0) > 25.0) WarningRed else TextSecondaryDark,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Text(
                                "Record at least 2 trips with OBD logging and the rpm / speed / load / torque " +
                                    "and idle-vs-model trend charts appear here.",
                                color = TextSecondaryDark, fontSize = 12.sp
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // Storage Management Dialog
    if (showStorageDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDialog = false },
            title = { Text("Diagnostic Storage & Retention") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Saved Trips: ${storageStats?.tripCount ?: 0}", fontSize = 14.sp)
                    Text("• Telemetry Samples: ${storageStats?.sampleCount ?: 0}", fontSize = 14.sp)
                    Text("• Raw Communication Frames: ${storageStats?.rawLogCount ?: 0}", fontSize = 14.sp)
                    Text("• Storage Engine: Room Database (Indexed) + Local Files", fontSize = 14.sp)
                    Text("• Auto-Pruning: Disabled (All diagnostic history is preserved permanently).", fontSize = 14.sp, color = NeonEmerald)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.recordingManager.deleteAllRecordings()
                            storageStats = tripRepo.getStorageStats()
                            showStorageDialog = false
                            Toast.makeText(context, "All recordings cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Clear All Storage", color = WarningRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStorageDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Rename Dialog
    renamingRecording?.let { rec ->
        var newName by remember { mutableStateOf(rec.metadata.sessionName) }
        Dialog(onDismissRequest = { renamingRecording = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Rename Recording", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Session Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renamingRecording = null }) { Text("Cancel") }
                        Button(onClick = {
                            viewModel.renameRecording(rec.metadata.sessionId, newName)
                            renamingRecording = null
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    deletingRecording?.let { rec ->
        AlertDialog(
            onDismissRequest = { deletingRecording = null },
            title = { Text("Delete Trip ${rec.metadata.sessionName}?") },
            text = { Text("This will permanently remove the trip, Room telemetry samples, and exported logs from local storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecording(rec.metadata.sessionId)
                        deletingRecording = null
                    }
                ) {
                    Text("Delete", color = WarningRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingRecording = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RecordingItemCard(
    recording: SavedRecording,
    onClick: () -> Unit,
    onShareFile: (File, String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val meta = recording.metadata

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recording_card_${meta.sessionId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = meta.sessionName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${meta.sessionId} • ${meta.vehicle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row {
                    IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = WarningRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${recording.transactionCount} transactions",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonEmerald,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Text(
                    text = meta.startTimeUtc.take(19).replace("T", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))

            // Export Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (recording.zipFile != null && recording.zipFile.exists()) {
                    OutlinedButton(
                        onClick = { onShareFile(recording.zipFile, "application/zip") },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("ZIP BUNDLE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberCyan)
                    }
                }

                OutlinedButton(
                    onClick = { onShareFile(recording.transactionCsvFile, "text/csv") },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("TX CSV", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onShareFile(recording.samplesCsvFile, "text/csv") },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("SAMPLES", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onShareFile(recording.jsonFile, "application/json") },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun shareFile(context: Context, file: File, mimeType: String) {
    if (!file.exists() || file.length() == 0L) {
        Toast.makeText(context, "File does not exist or is empty", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ${file.name}")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Share error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

/** One labelled sparkline with first → last and drift % (needs >= 2 points to draw). */
@Composable
private fun TrendRow(label: String, values: List<Double>, fmt: String, color: Color) {
    if (values.size < 2) return
    val first = values.first()
    val last = values.last()
    val driftLabel = com.example.data.Fmt.driftLabel(first, last)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondaryDark, fontSize = 12.sp)
            Text(
                "${String.format(fmt, first)} → ${String.format(fmt, last)} ($driftLabel)",
                color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
        SimpleLineChart(
            values.map { it.toFloat() },
            Modifier.fillMaxWidth().height(48.dp),
            color
        )
    }
}
