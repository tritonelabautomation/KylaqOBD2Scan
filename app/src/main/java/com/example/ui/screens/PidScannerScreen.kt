package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.bluetooth.ConnectionState
import com.example.discovery.PidScanStatus
import com.example.model.PidDefinition
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

enum class PidFilterMode {
    ALL,
    SUPPORTED_ONLY,
    UNSUPPORTED_ONLY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PidScannerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val isScanning by viewModel.isPidScanning.collectAsState()
    val isValidating by viewModel.isPidValidating.collectAsState()
    val scanStatus by viewModel.pidScanStatus.collectAsState()
    val progress by viewModel.pidScanProgress.collectAsState()
    val currentRangeText by viewModel.pidScanRangeText.collectAsState()
    val discoveredPids by viewModel.discoveredPids.collectAsState()
    val validatedPids by viewModel.validatedPids.collectAsState()
    val supportedCount by viewModel.discoveredSupportedCount.collectAsState()
    val validatedPidsCount by viewModel.validatedPidsCount.collectAsState()
    val discoveredEcus by viewModel.discoveredEcus.collectAsState()
    val errorMessage by viewModel.pidScanErrorMessage.collectAsState()
    val rawLogs by viewModel.pidScanRawLogs.collectAsState()
    val discoveredRanges by viewModel.discoveredRanges.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(PidFilterMode.ALL) }
    var showRawLogsDialog by remember { mutableStateOf(false) }
    var showApplySuccessDialog by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var appliedCount by remember { mutableStateOf(0) }

    val isConnected = connectionState == ConnectionState.CONNECTED

    // Filtered list
    val filteredPids = remember(discoveredPids, searchQuery, filterMode) {
        discoveredPids.filter { pid ->
            val matchesFilter = when (filterMode) {
                PidFilterMode.ALL -> true
                PidFilterMode.SUPPORTED_ONLY -> pid.supported
                PidFilterMode.UNSUPPORTED_ONLY -> !pid.supported
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val q = searchQuery.trim().lowercase()
                pid.hexPid.lowercase().contains(q) ||
                        pid.name.lowercase().contains(q) ||
                        pid.shortName.lowercase().contains(q) ||
                        pid.unit.lowercase().contains(q) ||
                        pid.description.lowercase().contains(q)
            }
            matchesFilter && matchesSearch
        }
    }

    val totalScanned = discoveredPids.size
    val unsupportedCount = (totalScanned - supportedCount).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "OBD-II PID Discovery",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "SAE J1979 Mode 01 Availability Scanner",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_pid_scanner_back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showExportMenu = true },
                            enabled = discoveredPids.isNotEmpty(),
                            modifier = Modifier.testTag("btn_export_menu")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Export Report", tint = CyberCyan)
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy JSON Report") },
                                onClick = {
                                    showExportMenu = false
                                    val json = viewModel.exportDiscoveryReportJson()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("PID Discovery JSON", json))
                                    Toast.makeText(context, "Exported JSON to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy CSV Catalog") },
                                onClick = {
                                    showExportMenu = false
                                    val csv = viewModel.exportDiscoveryReportCsv()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("PID Catalog CSV", csv))
                                    Toast.makeText(context, "Exported CSV to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) }
                            )
                        }
                    }
                    IconButton(
                        onClick = { showRawLogsDialog = true },
                        modifier = Modifier.testTag("btn_view_raw_logs")
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = "Diagnostic Logs", tint = CyberCyan)
                    }
                    IconButton(
                        onClick = { viewModel.clearPidScan() },
                        enabled = !isScanning && !isValidating && discoveredPids.isNotEmpty(),
                        modifier = Modifier.testTag("btn_clear_scan")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Scan")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = Modifier.testTag("pid_scanner_screen")
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Scanner Control & Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header Status Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when (scanStatus) {
                                PidScanStatus.SCANNING -> CyberCyan
                                PidScanStatus.VALIDATING -> ElectricAmber
                                PidScanStatus.COMPLETED -> NeonEmerald
                                PidScanStatus.ERROR -> WarningRed
                                PidScanStatus.CANCELLED -> ElectricAmber
                                PidScanStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = scanStatus.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        // Connection badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isConnected) NeonEmerald.copy(alpha = 0.15f) else WarningRed.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isConnected) (connectedDeviceName ?: "Connected") else "Disconnected",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isConnected) NeonEmerald else WarningRed
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Current progress / range message
                    Text(
                        text = currentRangeText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isScanning || progress > 0f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .testTag("pid_scan_progress_bar"),
                            color = if (scanStatus == PidScanStatus.COMPLETED) NeonEmerald else CyberCyan,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningRed
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Discovery stats chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatChip(
                            label = "Supported",
                            value = "$supportedCount",
                            accentColor = NeonEmerald,
                            modifier = Modifier.weight(1f)
                        )
                        StatChip(
                            label = "Unsupported",
                            value = "$unsupportedCount",
                            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        StatChip(
                            label = "Ranges",
                            value = "${discoveredRanges.size}",
                            accentColor = CyberCyan,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (discoveredEcus.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Responding ECUs:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            discoveredEcus.forEach { ecuId ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = CyberCyan.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "ECU $ecuId",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CyberCyan
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isScanning) {
                            OutlinedButton(
                                onClick = { viewModel.stopPidScan() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("btn_stop_pid_scan"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningRed),
                                border = androidx.compose.foundation.BorderStroke(1.dp, WarningRed),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop Scan", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startPidScan() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("btn_start_pid_scan"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan PIDs", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!isScanning && supportedCount > 0) {
                            if (isValidating) {
                                OutlinedButton(
                                    onClick = { viewModel.stopPidScan() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("btn_stop_pid_validation"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningRed),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningRed),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop ($validatedPidsCount)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.startDirectValidation() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("btn_direct_validate_pids"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (validatedPidsCount > 0) "Validated ($validatedPidsCount)" else "Validate",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    appliedCount = viewModel.applyDiscoveredPidsToLiveData()
                                    showApplySuccessDialog = true
                                },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .height(48.dp)
                                    .testTag("btn_apply_pids_to_live"),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald, contentColor = Color.Black),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Apply Live", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Search and Filter Bar
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by Hex (e.g. 0C) or Name...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_pids_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filterMode == PidFilterMode.ALL,
                        onClick = { filterMode = PidFilterMode.ALL },
                        label = { Text("All ($totalScanned)") },
                        modifier = Modifier.testTag("filter_chip_all")
                    )
                    FilterChip(
                        selected = filterMode == PidFilterMode.SUPPORTED_ONLY,
                        onClick = { filterMode = PidFilterMode.SUPPORTED_ONLY },
                        label = { Text("Supported ($supportedCount)") },
                        leadingIcon = {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonEmerald)
                        },
                        modifier = Modifier.testTag("filter_chip_supported")
                    )
                    FilterChip(
                        selected = filterMode == PidFilterMode.UNSUPPORTED_ONLY,
                        onClick = { filterMode = PidFilterMode.UNSUPPORTED_ONLY },
                        label = { Text("Unsupported ($unsupportedCount)") },
                        modifier = Modifier.testTag("filter_chip_unsupported")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Discovered PIDs List
            if (filteredPids.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (discoveredPids.isEmpty()) Icons.Default.Search else Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (discoveredPids.isEmpty()) {
                                if (isScanning) "Discovering Mode 01 PIDs from vehicle ECU..." else "Tap 'Scan PIDs' to probe vehicle supported parameters"
                            } else {
                                "No PIDs match the active search filter"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        if (discoveredPids.isEmpty() && !isConnected) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Adapter not connected. Connect Bluetooth or Simulation Mode to scan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricAmber
                            )
                        }
                    }
                }
            } else {
                val valMap = remember(validatedPids) { validatedPids.associateBy { it.hexPid.uppercase() } }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .testTag("discovered_pids_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(items = filteredPids, key = { it.id }) { pidDef ->
                        DiscoveredPidCard(
                            pidDef = pidDef,
                            validationResult = valMap[pidDef.hexPid.uppercase()]
                        )
                    }
                }
            }
        }
    }

    // Raw Diagnostic Logs Dialog
    if (showRawLogsDialog) {
        RawLogsDialog(
            rawLogs = rawLogs,
            onDismiss = { showRawLogsDialog = false },
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("PID Discovery Log", rawLogs.joinToString("\n"))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied diagnostic logs to clipboard", Toast.LENGTH_SHORT).show()
            },
            onShare = {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, rawLogs.joinToString("\n"))
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share PID Discovery Log"))
            }
        )
    }

    // Applied to Live Monitor Dialog
    if (showApplySuccessDialog) {
        AlertDialog(
            onDismissRequest = { showApplySuccessDialog = false },
            title = { Text("Live Monitor Updated") },
            text = {
                Text(
                    "Successfully applied $appliedCount supported PIDs to the active Live Telemetry engine. Unsupported PIDs have been disabled to maximize CAN bus bandwidth and responsiveness."
                )
            },
            confirmButton = {
                Button(
                    onClick = { showApplySuccessDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald, contentColor = Color.Black)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun DiscoveredPidCard(
    pidDef: PidDefinition,
    validationResult: com.example.discovery.PidValidationResult? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pid_card_${pidDef.hexPid}"),
        colors = CardDefaults.cardColors(
            containerColor = if (pidDef.supported) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hex Badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (pidDef.supported) NeonEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        1.dp,
                        if (pidDef.supported) NeonEmerald.copy(alpha = 0.5f) else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pidDef.hexPid,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = if (pidDef.supported) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Main Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = pidDef.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (pidDef.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pidDef.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Metadata Badges (Mode, Unit, Formula, Bytes)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BadgeText(text = "Mode 01")
                    if (pidDef.unit.isNotBlank() && pidDef.unit != "RAW") {
                        BadgeText(text = pidDef.unit, color = CyberCyan)
                    }
                    if (pidDef.dataBytes > 0) {
                        BadgeText(text = "${pidDef.dataBytes}B")
                    }
                    if (pidDef.formulaDisplay.isNotBlank()) {
                        BadgeText(text = pidDef.formulaDisplay, color = ElectricAmber)
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status and Validation Badges Column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (pidDef.supported) NeonEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (pidDef.supported) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (pidDef.supported) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (pidDef.supported) "SUPPORTED" else "NO",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (pidDef.supported) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (validationResult != null) {
                    val isValOk = validationResult.directStatus == com.example.model.CapabilityStatus.SUPPORTED
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isValOk) NeonEmerald.copy(alpha = 0.15f) else WarningRed.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isValOk) "VAL: ${validationResult.latencyMs}ms" else validationResult.directStatus.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = if (isValOk) NeonEmerald else WarningRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun StatChip(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun RawLogsDialog(
    rawLogs: List<String>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .testTag("raw_logs_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Raw Diagnostic Log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = "Real-time communication trace with vehicle ECU during PID discovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable log area
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black
                ) {
                    if (rawLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No communication logged yet", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            items(rawLogs) { entry ->
                                val color = when {
                                    entry.contains("ERROR") || entry.contains("FATAL") -> WarningRed
                                    entry.contains("TX:") -> CyberCyan
                                    entry.contains("RX:") -> NeonEmerald
                                    entry.contains("Decoded") -> ElectricAmber
                                    else -> Color.LightGray
                                }
                                Text(
                                    text = entry,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = color,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopy,
                        modifier = Modifier.weight(1f),
                        enabled = rawLogs.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        enabled = rawLogs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }
                }
            }
        }
    }
}
