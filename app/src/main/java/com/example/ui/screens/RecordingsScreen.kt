package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.example.analysis.DailyTripCostAggregator
import com.example.analysis.FuelBrandTagger
import com.example.data.SavedRecording
import com.example.data.db.StorageStats
import com.example.ui.components.DayCostGroupCard
import com.example.ui.components.FuelBrandBadge
import com.example.ui.components.FuelTagSelectorDialog
import com.example.ui.components.MonthlyPriceCard
import com.example.ui.screens.CarpoolDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

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
    val isMerging by viewModel.isMerging.collectAsState()
    val mergeNotice by viewModel.mergeNotice.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshUnsavedRawLogs() }

    var renamingRecording by remember { mutableStateOf<SavedRecording?>(null) }
    var deletingRecording by remember { mutableStateOf<SavedRecording?>(null) }
    var storageStats by remember { mutableStateOf<StorageStats?>(null) }
    var showStorageDialog by remember { mutableStateOf(false) }

    // Merge selection (owner 2026-09-21: "there is no option make two trips to merge")
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(mergeNotice) {
        mergeNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMergeNotice()
            selectedIds = emptySet()
        }
    }

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

    // Monthly Price Card & Day-Wise Grouping Aggregator (owner request 2026-09-30)
    val carpoolTick by viewModel.carpoolRepository.changeTick.collectAsState()
    var aggregationResult by remember { mutableStateOf<DailyTripCostAggregator.AggregationResult?>(null) }
    var selectedMonthIndex by remember { mutableStateOf(0) }
    var viewAllMonths by remember { mutableStateOf(false) }
    var carpoolTargetTrip by remember { mutableStateOf<DailyTripCostAggregator.DayTripItem?>(null) }
    var fuelTagTargetTrip by remember { mutableStateOf<DailyTripCostAggregator.DayTripItem?>(null) }
    var showFillGapDialog by remember { mutableStateOf(false) }
    var gapToFill by remember { mutableStateOf<DailyTripCostAggregator.DayTripItem?>(null) }

    LaunchedEffect(savedRecordings, carpoolTick) {
        val res = runCatching { viewModel.loadDayWiseAggregation() }.getOrNull()
        aggregationResult = res
        if (res != null) {
            val months = res.allMonthSummaries
            val idxWithTrips = months.indexOfFirst { it.totalTrips > 0 }
            if (idxWithTrips >= 0 && (selectedMonthIndex >= months.size || months.getOrNull(selectedMonthIndex)?.totalTrips == 0)) {
                selectedMonthIndex = idxWithTrips
            }
        }
    }

    // Sync status — OneDrive-style (owner 2026-09-21)
    val lastBackupMs by viewModel.settingsRepository.lastBackupTimestamp.collectAsState()

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
                        gapToFill = null
                        showFillGapDialog = true
                    },
                    modifier = Modifier.testTag("btn_add_manual_trip")
                ) {
                    Icon(Icons.Default.AddRoad, contentDescription = "Fill Missing Trip", tint = NeonEmerald)
                }
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

        // Merge banner — appears when selection mode is active
        if (selectionMode) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CyberCyan.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CallMerge, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${selectedIds.size} trip(s) selected — tap to toggle, long-press any card to start. Merge stitches them by wall time into ONE trip (your 31 km fragments become one).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.mergeRecordings(selectedIds.toList()) },
                    enabled = selectedIds.size >= 2 && !isMerging,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                ) {
                    if (isMerging) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Merging…", color = Color.Black, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.CallMerge, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Merge into ONE trip", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = { selectedIds = emptySet() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel selection")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.SettingsBackupRestore,
                                contentDescription = null,
                                tint = ElectricAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${unsavedRawLogs.size} unsaved log session(s) found",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Button(
                            onClick = { viewModel.forceRecoverAllNow() },
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricAmber),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Recover All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
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
                                    text = com.example.data.RecordTime.format("yyyy-MM-dd HH:mm", logFile.lastModified()) +
                                        " · ${logFile.length() / 1024} KB raw log",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Never a dead grey disabled button (owner 2026-09-16: the
                            // greyed spinner read as "UI stuck"). Stays amber and says
                            // what it is doing; re-entry is guarded in the ViewModel.
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = { viewModel.dismissRawLog(logFile) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
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

        val agg = aggregationResult
        val months = agg?.allMonthSummaries ?: emptyList()
        val activeMonth = months.getOrNull(selectedMonthIndex) ?: agg?.currentMonthSummary
        val savedRecordingsMap = remember(savedRecordings) {
            savedRecordings.associateBy { it.metadata.sessionId }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp)
        ) {
            if (activeMonth != null) {
                item(key = "monthly_price_card") {
                    MonthlyPriceCard(
                        monthSummary = activeMonth,
                        availableMonths = months,
                        selectedMonthIndex = selectedMonthIndex,
                        onPreviousMonth = { if (selectedMonthIndex > 0) selectedMonthIndex-- },
                        onNextMonth = { if (selectedMonthIndex < months.size - 1) selectedMonthIndex++ },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                if (savedRecordings.isNotEmpty()) {
                    item(key = "month_filter_tabs") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item(key = "tab_all_days") {
                                FilterChip(
                                    selected = viewAllMonths,
                                    onClick = { viewAllMonths = true },
                                    label = {
                                        Text(
                                            text = "All Days (${savedRecordings.size})",
                                            fontSize = 12.sp,
                                            fontWeight = if (viewAllMonths) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = CyberCyan
                                    )
                                )
                            }

                            itemsIndexed(months, key = { _, m -> "tab_month_${m.monthKey}" }) { idx, m ->
                                FilterChip(
                                    selected = !viewAllMonths && selectedMonthIndex == idx,
                                    onClick = {
                                        selectedMonthIndex = idx
                                        viewAllMonths = false
                                    },
                                    label = {
                                        Text(
                                            text = "${m.displayMonth} (${m.totalTrips})",
                                            fontSize = 12.sp,
                                            fontWeight = if (!viewAllMonths && selectedMonthIndex == idx) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                                        selectedLabelColor = CyberCyan
                                    )
                                )
                            }
                        }
                    }
                }
            }

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
                val dayGroupsToShow = if (viewAllMonths) {
                    agg?.dayGroups ?: emptyList()
                } else {
                    agg?.dayGroups?.filter { it.dateKey.startsWith(activeMonth?.monthKey ?: "") } ?: emptyList()
                }

                val monthFuelLogs = activeMonth?.fuelLogs ?: emptyList()
                val monthCarpools = activeMonth?.carpoolLogs ?: emptyList()

                if (monthFuelLogs.isNotEmpty() || monthCarpools.isNotEmpty()) {
                    item(key = "month_fuel_ledger_card") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${activeMonth?.displayMonth} Fuel Fill-Ups (${monthFuelLogs.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryDark
                                        )
                                    }
                                    Text(
                                        text = "₹${String.format(Locale.US, "%.0f", activeMonth?.totalRefuelSpend ?: 0.0)}",
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricAmber,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                                monthFuelLogs.forEach { log ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = com.example.data.RecordTime.format("d MMM yyyy", log.idMs) +
                                                    if (log.station.isNotBlank()) " · ${log.station}" else "",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimaryDark
                                            )
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f L", log.liters)} @ ₹${String.format(Locale.US, "%.1f", log.pricePerL)}/L (${log.grade})" +
                                                    (log.odometerKm?.let { " · ODO: ${String.format(Locale.US, "%,.0f km", it)}" } ?: ""),
                                                fontSize = 11.sp,
                                                color = TextSecondaryDark,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        Text(
                                            text = "₹${String.format(Locale.US, "%.0f", log.totalCost)}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryDark,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                if (monthCarpools.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Groups, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Carpool Rides (${monthCarpools.size})",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonEmerald
                                            )
                                        }
                                        Text(
                                            text = "+₹${String.format(Locale.US, "%.0f", activeMonth?.totalCarpoolEarned ?: 0.0)}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonEmerald,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (dayGroupsToShow.isNotEmpty()) {
                    dayGroupsToShow.forEach { dayGroup ->
                        item(key = "day_header_${dayGroup.dateKey}") {
                            DayCostGroupCard(dayGroup = dayGroup)
                        }

                        items(dayGroup.trips, key = { it.tripId }) { tripItem ->
                            val rec = savedRecordingsMap[tripItem.tripId]
                            if (rec != null) {
                                val isSelected = rec.metadata.sessionId in selectedIds
                                val dirMtime = rec.transactionCsvFile.parentFile?.lastModified()
                                val syncState = com.example.data.BackupSyncStatus.forTrip(
                                    lastBackupMs = lastBackupMs,
                                    endMs = null,
                                    startMs = com.example.data.RecordTime.parseMillis(rec.metadata.startTimeUtc),
                                    dirLastModifiedMs = dirMtime
                                )
                                Column {
                                    if (tripItem.unloggedOdoGapKm != null && tripItem.unloggedOdoGapKm >= 0.5) {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = 3.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            color = WarningRed.copy(alpha = 0.12f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, WarningRed.copy(alpha = 0.45f))
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Warning,
                                                        contentDescription = null,
                                                        tint = WarningRed,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "MISSED TRIP / UNLOGGED GAP: +${String.format(java.util.Locale.US, "%.1f", tripItem.unloggedOdoGapKm)} km",
                                                            color = WarningRed,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                        Text(
                                                            text = "Cluster ODO advanced from ${String.format(java.util.Locale.US, "%.1f", tripItem.prevTripEndOdoKm ?: 0.0)} km → ${String.format(java.util.Locale.US, "%.1f", tripItem.startOdometerKm ?: 0.0)} km between trips without OBD logging.",
                                                            color = TextSecondaryDark,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            gapToFill = tripItem
                                                            showFillGapDialog = true
                                                        },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
                                                        shape = RoundedCornerShape(6.dp),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(30.dp)
                                                    ) {
                                                        Icon(Icons.Default.AddRoad, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyberCyan)
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Fill Missing Trip (+${String.format(java.util.Locale.US, "%.1f", tripItem.unloggedOdoGapKm)} km)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    RecordingItemCard(
                                        recording = rec,
                                        tripItem = tripItem,
                                        isSelected = isSelected,
                                        selectionMode = selectionMode,
                                        syncState = syncState,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedIds = if (isSelected) selectedIds - rec.metadata.sessionId else selectedIds + rec.metadata.sessionId
                                            } else {
                                                onNavigateToTripDetail(rec.metadata.sessionId)
                                            }
                                        },
                                        onLongClick = {
                                            selectedIds = if (isSelected) selectedIds - rec.metadata.sessionId else selectedIds + rec.metadata.sessionId
                                        },
                                        onToggleSelect = {
                                            selectedIds = if (isSelected) selectedIds - rec.metadata.sessionId else selectedIds + rec.metadata.sessionId
                                        },
                                        onShareFile = { file, mimeType -> shareFile(context, file, mimeType) },
                                        onRecalculate = {
                                            coroutineScope.launch {
                                                val ok = viewModel.recalculateTripMetrics(rec.metadata.sessionId)
                                                aggregationResult = runCatching { viewModel.loadDayWiseAggregation() }.getOrNull()
                                                Toast.makeText(context, if (ok) "Recalculated metrics from disk transactions" else "Recalculation complete", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onRename = { renamingRecording = rec },
                                        onDelete = { deletingRecording = rec },
                                        onAddOrEditCarpool = { carpoolTargetTrip = tripItem },
                                        onEditFuelTag = { fuelTagTargetTrip = tripItem }
                                    )
                                }
                            }
                        }
                    }
                } else if (savedRecordings.isNotEmpty()) {
                    // Fallback when dayGroups are empty for selected month or while aggregator is computing:
                    item(key = "no_trips_in_month_fallback_header") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (activeMonth != null && !viewAllMonths) "No trips in ${activeMonth.displayMonth} · Showing all ${savedRecordings.size} trips" else "All Recorded Trips (${savedRecordings.size})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = { viewAllMonths = true }) {
                                    Text("View All", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    items(savedRecordings, key = { it.metadata.sessionId }) { rec ->
                        val isSelected = rec.metadata.sessionId in selectedIds
                        val dirMtime = rec.transactionCsvFile.parentFile?.lastModified()
                        val syncState = com.example.data.BackupSyncStatus.forTrip(
                            lastBackupMs = lastBackupMs,
                            endMs = null,
                            startMs = com.example.data.RecordTime.parseMillis(rec.metadata.startTimeUtc),
                            dirLastModifiedMs = dirMtime
                        )
                        RecordingItemCard(
                            recording = rec,
                            tripItem = null,
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            syncState = syncState,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (isSelected) selectedIds - rec.metadata.sessionId else selectedIds + rec.metadata.sessionId
                                } else {
                                    onNavigateToTripDetail(rec.metadata.sessionId)
                                }
                            },
                            onLongClick = {
                                selectedIds = if (isSelected) selectedIds - rec.metadata.sessionId else selectedIds + rec.metadata.sessionId
                            },
                            onToggleSelect = {
                                selectedIds = if (isSelected) selectedIds - rec.metadata.sessionId else selectedIds + rec.metadata.sessionId
                            },
                            onShareFile = { file, mimeType -> shareFile(context, file, mimeType) },
                            onRecalculate = {
                                coroutineScope.launch {
                                    val ok = viewModel.recalculateTripMetrics(rec.metadata.sessionId)
                                    aggregationResult = runCatching { viewModel.loadDayWiseAggregation() }.getOrNull()
                                    Toast.makeText(context, if (ok) "Recalculated metrics from disk transactions" else "Recalculation complete", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRename = { renamingRecording = rec },
                            onDelete = { deletingRecording = rec },
                            onAddOrEditCarpool = null,
                            onEditFuelTag = null
                        )
                    }
                }

                item(key = "vehicle_trends_card") {
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

    // Carpool Add / Edit Dialog (owner request 2026-09-30)
    carpoolTargetTrip?.let { tripItem ->
        CarpoolDialog(
            existing = tripItem.carpoolEntry,
            defaultDistanceKm = tripItem.distanceKm,
            defaultWhenMs = tripItem.startMs,
            onDismiss = { carpoolTargetTrip = null },
            onSave = { d, riders, whenMs ->
                coroutineScope.launch {
                    viewModel.saveCarpool(
                        com.example.data.CarpoolCodec.CarpoolEntry(
                            idMs = tripItem.carpoolEntry?.idMs ?: whenMs,
                            tripId = tripItem.tripId,
                            dateUtc = com.example.data.RecordTime.stamp(whenMs),
                            distanceKm = d,
                            riders = riders
                        )
                    )
                    carpoolTargetTrip = null
                    Toast.makeText(context, "Carpool details saved for trip", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Fuel Tag Selector Dialog
    fuelTagTargetTrip?.let { tripItem ->
        val currentTag = tripItem.fuelTag ?: FuelBrandTagger.resolveFuelTag(tripItem.startMs)
        FuelTagSelectorDialog(
            currentTag = currentTag,
            onDismiss = { fuelTagTargetTrip = null },
            onTagSelected = { station, grade, additive, dosageMl ->
                viewModel.setTripFuelTag(tripItem.tripId, station, grade, additive, dosageMl)
                fuelTagTargetTrip = null
                Toast.makeText(context, "Fuel tag updated to $station ($grade)", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showFillGapDialog) {
        com.example.ui.components.FillMissingTripDialog(
            initialStartOdoKm = gapToFill?.prevTripEndOdoKm,
            initialEndOdoKm = gapToFill?.startOdometerKm,
            initialStartMs = gapToFill?.prevTripEndMs,
            initialEndMs = gapToFill?.startMs,
            onDismiss = {
                showFillGapDialog = false
                gapToFill = null
            },
            onSave = { request ->
                viewModel.createManualTrip(request)
                showFillGapDialog = false
                gapToFill = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingItemCard(
    recording: SavedRecording,
    tripItem: com.example.analysis.DailyTripCostAggregator.DayTripItem? = null,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    syncState: com.example.data.BackupSyncStatus.State = com.example.data.BackupSyncStatus.State.NEVER,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null,
    onShareFile: (File, String) -> Unit,
    onRecalculate: (() -> Unit)? = null,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddOrEditCarpool: (() -> Unit)? = null,
    onEditFuelTag: (() -> Unit)? = null
) {
    val meta = recording.metadata

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("recording_card_${meta.sessionId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CyberCyan.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, CyberCyan) else null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: Checkbox, Name, Commute Tag, Fuel Brand Badge, Sync icon, Action icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect?.invoke() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = meta.sessionName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))

                        // OneDrive-style sync icon (owner 2026-09-21) — use core-safe icons
                        val syncIcon = when (syncState) {
                            com.example.data.BackupSyncStatus.State.SYNCED -> Icons.Default.CheckCircle
                            com.example.data.BackupSyncStatus.State.PENDING -> Icons.Default.CloudUpload
                            com.example.data.BackupSyncStatus.State.NEVER -> Icons.Default.Warning
                        }
                        val syncTint = when (syncState) {
                            com.example.data.BackupSyncStatus.State.SYNCED -> NeonEmerald
                            com.example.data.BackupSyncStatus.State.PENDING -> ElectricAmber
                            com.example.data.BackupSyncStatus.State.NEVER -> TextSecondaryDark
                        }
                        val syncDesc = when (syncState) {
                            com.example.data.BackupSyncStatus.State.SYNCED -> "Synced to Drive"
                            com.example.data.BackupSyncStatus.State.PENDING -> "Local — needs backup"
                            com.example.data.BackupSyncStatus.State.NEVER -> "Never backed up"
                        }
                        Icon(
                            syncIcon,
                            contentDescription = syncDesc,
                            tint = syncTint,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Commute Slot Tag & Time + Fuel Used Tag Badge (e.g. Nayara X95, IOCL XP95, Jio-bp, Shell)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (tripItem != null) {
                            val slotColor = when (tripItem.commuteSlot) {
                                com.example.analysis.CommuteComparator.CommuteSlot.MORNING -> CyberCyan
                                com.example.analysis.CommuteComparator.CommuteSlot.EVENING -> ElectricAmber
                                com.example.analysis.CommuteComparator.CommuteSlot.OFF_PEAK -> TextSecondaryDark
                            }
                            Surface(
                                color = slotColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "${tripItem.commuteSlotLabel} · ${tripItem.timeLabel}",
                                    color = slotColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Fuel Used Tag (e.g. Nayara X95 + mileX, IOCL XP95, Jio-bp, Shell)
                        val startMs = tripItem?.startMs ?: com.example.data.RecordTime.parseMillis(meta.startTimeUtc) ?: recording.jsonFile.lastModified()
                        val fuelTag = tripItem?.fuelTag ?: FuelBrandTagger.resolveFuelTag(startMs)
                        FuelBrandBadge(
                            fuelTag = fuelTag,
                            onClick = onEditFuelTag
                        )
                    }

                    Text(
                        text = "ID: ${meta.sessionId} • ${meta.vehicle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Row {
                    if (onRecalculate != null) {
                        IconButton(onClick = onRecalculate, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recalculate", tint = CyberCyan, modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = WarningRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Distance, Duration, Fuel, Economy, Transactions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tripItem != null && (tripItem.distanceKm > 0.05 || tripItem.fuelLiters > 0.01)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.1f", tripItem.distanceKm)} km",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            if (tripItem.durationSeconds > 0) {
                                val mins = tripItem.durationSeconds / 60
                                Text(
                                    text = " · ${mins}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = " · ${String.format(java.util.Locale.US, "%.2f", tripItem.fuelLiters)} L (₹${String.format(java.util.Locale.US, "%.0f", tripItem.fuelCost)})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = ElectricAmber,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                        tripItem.kmPerLiter?.let { kmL ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.1f", kmL)} km/L",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonEmerald,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                tripItem.fuelTag?.let { ft ->
                                    Text(
                                        text = "· ⛽ ${ft.displayBadge}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ft.brand.secondaryColor,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${recording.transactionCount} transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonEmerald,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                }

                Text(
                    text = com.example.data.RecordTime.dateTime(meta.startTimeUtc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }

            // Odometer Start & End display (PID 01A6)
            if (tripItem?.startOdometerKm != null || tripItem?.endOdometerKm != null) {
                val startOdo = tripItem.startOdometerKm
                val endOdo = tripItem.endOdometerKm
                val startOdoStr = startOdo?.let { String.format(java.util.Locale.US, "%,.1f km", it) } ?: "--"
                val endOdoStr = endOdo?.let { String.format(java.util.Locale.US, "%,.1f km", it) } ?: "--"
                val delta = if (startOdo != null && endOdo != null) endOdo - startOdo else null
                val deltaStr = delta?.let { String.format(java.util.Locale.US, " (Δ +%.1f km)", it) } ?: ""
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = CyberCyan.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(12.dp))
                        Text(
                            text = "ODO: Start $startOdoStr → End $endOdoStr$deltaStr",
                            color = CyberCyan,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Row 3: Carpool details & Net Trip cost
            if (tripItem?.carpoolEntry != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = NeonEmerald.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Top row: Rider Names & Total Carpool Earned
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                val riderText = if (tripItem.riderNames.isNotEmpty()) {
                                    "Carpool: ${tripItem.carpoolRiderCount} rider(s) (${tripItem.riderNames.joinToString(", ")})"
                                } else {
                                    "Carpool: ${tripItem.carpoolRiderCount} rider(s)"
                                }
                                Text(
                                    text = riderText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "₹${String.format(java.util.Locale.US, "%.0f", tripItem.carpoolEarned)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonEmerald,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        // Bottom row: Net Cost/Surplus Profit & Edit Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val netColor = if (tripItem.isTripProfit) NeonEmerald else ElectricAmber
                            val netTxt = if (tripItem.isTripProfit) {
                                "Net: +₹${String.format(java.util.Locale.US, "%.0f", tripItem.carpoolEarned - tripItem.fuelCost)} Surplus (Profit)"
                            } else {
                                "Net Cost: ₹${String.format(java.util.Locale.US, "%.0f", tripItem.netTripCost)}"
                            }
                            Text(
                                text = netTxt,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = netColor,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )

                            if (onAddOrEditCarpool != null) {
                                Text(
                                    text = "Edit",
                                    fontSize = 11.sp,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { onAddOrEditCarpool() }
                                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            } else if (onAddOrEditCarpool != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onAddOrEditCarpool,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Add Carpool", fontSize = 11.sp, color = NeonEmerald)
                    }
                }
            }

            // Row 4: Tank level % (PID 012F)
            if (meta.startFuelPercent != null && meta.endFuelPercent != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val delta = meta.fuelDeltaPercent ?: (meta.endFuelPercent - meta.startFuelPercent)
                    val isRefuel = delta >= 3.0
                    val deltaCol = if (isRefuel) NeonEmerald else if (delta < 0) ElectricAmber else TextSecondaryDark
                    val deltaSign = if (delta > 0) "+" else ""
                    Surface(
                        color = deltaCol.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = deltaCol, modifier = Modifier.size(12.dp))
                            Text(
                                text = "⛽ ${String.format(java.util.Locale.US, "%.1f", meta.startFuelPercent)}% → ${String.format(java.util.Locale.US, "%.1f", meta.endFuelPercent)}% ($deltaSign${String.format(java.util.Locale.US, "%.1f", delta)}%)" +
                                    if (isRefuel) " • Refuel Brim" else "",
                                color = deltaCol,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(6.dp))

            // Export Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (recording.zipFile != null && recording.zipFile.exists()) {
                    OutlinedButton(
                        onClick = { onShareFile(recording.zipFile, "application/zip") },
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("ZIP BUNDLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyberCyan)
                    }
                }

                OutlinedButton(
                    onClick = { onShareFile(recording.transactionCsvFile, "text/csv") },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("TX CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onShareFile(recording.samplesCsvFile, "text/csv") },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("SAMPLES", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onShareFile(recording.jsonFile, "application/json") },
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
