package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.data.FleetExporter
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenPidConfig: () -> Unit = {},
    onOpenFuelCosts: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    onOpenDriveBackup: () -> Unit = {},
    onOpenExpenses: () -> Unit = {},
    onOpenDocuments: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenReports: () -> Unit = {}
) {
    val context = LocalContext.current
    // FIX (bug: Google sign-in button did nothing): Credential Manager requires an
    // Activity context. LocalContext.current may be the Activity, a ContextWrapper, or
    // (in tests/previews) a non-Activity context. Resolve defensively.
    val activity: Activity? = remember(context) {
        var ctx: Context? = context
        while (ctx is android.content.ContextWrapper && ctx !is Activity) {
            ctx = ctx.baseContext
        }
        ctx as? Activity
    }
    val fuelioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFuelioCsv(it) } }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ── Background-recording protection state (owner 2026-09-17: "why the hell ... app is
    // killed in background ... it never ever loose the logs"). Re-read on every resume, so the
    // card tells the truth the moment the owner comes back from system Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryExempt by remember {
        mutableStateOf(
            runCatching {
                context.getSystemService(android.os.PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }.getOrDefault(false)
        )
    }
    DisposableEffect(lifecycleOwner) {
        val readExemption = {
            batteryExempt = runCatching {
                context.getSystemService(android.os.PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }.getOrDefault(false)
        }
        readExemption()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) readExemption()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val isRecordingNow by viewModel.isRecording.collectAsState()
    val connectionNow by viewModel.connectionState.collectAsState()
    val rawLogWriteFailures = viewModel.rawLogManager.writeFailureCount
    val autoRecoveryNotice by viewModel.autoRecoveryNotice.collectAsState()
    val isAutoRecovering by viewModel.isAutoRecovering.collectAsState()

    val vehicleName by viewModel.vehicleName.collectAsState()
    val welcomeEnabled by viewModel.settingsRepository.welcomeEnabled.collectAsState()
    val welcomeMessage by viewModel.settingsRepository.welcomeMessage.collectAsState()
    val welcomeVolumeEnabled by viewModel.settingsRepository.welcomeVolumeEnabled.collectAsState()
    val welcomeVolumePct by viewModel.settingsRepository.welcomeVolumePct.collectAsState()
    val welcomeVoiceId by viewModel.welcomeVoiceId.collectAsState()
    val welcomeVoices by viewModel.welcomeVoices.collectAsState()
    val fuelioNotice by viewModel.fuelioImportNotice.collectAsState()
    val canHeader by viewModel.canHeader.collectAsState()
    val sppUuid by viewModel.sppUuid.collectAsState()
    val pollingMode by viewModel.pollingMode.collectAsState()

    val settingsRepo = viewModel.settingsRepository
    val cloudManager = viewModel.cloudBackupManager
    val googleEmail by settingsRepo.googleAccountEmail.collectAsState()
    val googleAccountName by settingsRepo.googleAccountName.collectAsState()
    val storedWebClientId by settingsRepo.googleWebClientId.collectAsState()
    // Recomputed whenever the stored client id changes, so saving one enables the button.
    val signInConfigured = remember(storedWebClientId, cloudManager) { cloudManager.isGoogleSignInConfigured }
    var showSignInSetup by remember { mutableStateOf(false) }
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
            SettingsSectionHeader("YOUR GARAGE")
            SimpleNavCard(
                icon = Icons.Default.LocalGasStation,
                title = "Fuel & Costs",
                subtitle = "Fill-up journal, km/L, cost per km and 30-day spend",
                onClick = onOpenFuelCosts
            )
            SimpleNavCard(
                icon = Icons.Default.Build,
                title = "Maintenance & Health",
                subtitle = "Kylaq service plan with due states and service log",
                onClick = onOpenMaintenance
            )
            SimpleNavCard(
                icon = Icons.Default.ReceiptLong,
                title = "Expenses",
                subtitle = "Unified fuel, service & cost timeline with CSV export",
                onClick = onOpenExpenses
            )
            SimpleNavCard(
                icon = Icons.Default.QueryStats,
                title = "Reports & Analytics",
                subtitle = "YTD spend, trends, budget, trip estimator & splitter",
                onClick = onOpenReports
            )
            SimpleNavCard(
                icon = Icons.Default.Notifications,
                title = "Reminders Hub",
                subtitle = "Due services, expiring documents & custom reminders",
                onClick = onOpenReminders
            )
            SimpleNavCard(
                icon = Icons.Default.Description,
                title = "Documents",
                subtitle = "Insurance, RC, licence, PUC with expiry alerts",
                onClick = onOpenDocuments
            )
            SettingsSectionHeader("CONNECTION & VEHICLE")
            // Vehicle & Hardware Profile (Connection & vehicle section)
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
            // PID management was an orphaned route (registered in MainActivity but never
            // navigated to) - the navigation audit wired it here so every screen is reachable.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPidConfig() }
                    .testTag("card_pid_config"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Manage PIDs",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Enable, edit or reset the tracked OBD-II parameters",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── App update (owner 2026-09-16: "update available ... similar to playstore") ──
                        // Android Auto discovery status + the one real restriction (Google policy).
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Android Auto dashboard",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Live OBD dash on your head unit - discovery status below",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                    val context = LocalContext.current
                    val discovered = remember {
                        runCatching {
                            val intent = android.content.Intent("androidx.car.app.CarAppService")
                                .setPackage(context.packageName)
                            @Suppress("DEPRECATION")
                            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
                        }.getOrDefault(false)
                    }
                    Text(
                        text = if (discovered) {
                            "CarAppService declared & discoverable on this device. If your head unit still " +
                                "does not list the app, the cause is Google's distribution rule, not this app:"
                        } else {
                            "CarAppService NOT discoverable - reinstall the latest APK."
                        },
                        color = if (discovered) NeonEmerald else WarningRed,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Two routes exist. (1) TEMPLATE route (CarAppService): Google requires a " +
                            "Play-trusted install on real head units - sideloaded template apps stay hidden. " +
                            "(2) PARKED/SURFACE route (this build ships it too - same recipe as the sideloaded " +
                            "AABrowser project): a distraction-optimised activity with CAR_LAUNCHER + " +
                            "ACCESS_SURFACE that AA's unknown-sources toggle DOES unlock (media/messaging/" +
                            "parked classes). Sideload this APK, enable AA developer mode + unknown sources, " +
                            "reconnect - the head-unit launcher should list it. Some hosts grant the car " +
                            "surface only while parked (host safety policy). Details: " +
                            "docs/reference/android-auto-install-guide.md.",
                        color = TextSecondaryDark,
                        fontSize = 10.sp
                    )
                }
            }

            SettingsSectionHeader("APPEARANCE & UNITS")
            val unitsMetric by viewModel.settingsRepository.unitsMetric.collectAsState()
            val currencySymbol by viewModel.settingsRepository.currencySymbol.collectAsState()
            val appearanceMode by viewModel.settingsRepository.appearanceMode.collectAsState()
            val accentTheme by viewModel.settingsRepository.accent.collectAsState()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12181F))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Straighten, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(8.dp))
                        Text("Appearance, Units & Currency", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFFE6EDF3))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Metric (km, km/L)", fontSize = 13.sp, color = Color(0xFF9AA7B4), modifier = Modifier.weight(1f))
                        Switch(checked = unitsMetric, onCheckedChange = { viewModel.settingsRepository.setUnitsMetric(it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("DARK", "LIGHT", "SYSTEM").forEach { m ->
                            FilterChip(
                                selected = appearanceMode == m,
                                onClick = { viewModel.settingsRepository.setAppearanceMode(m) },
                                label = { Text(m.lowercase(), fontSize = 12.sp) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CYBER" to "cyber", "RED" to "red sport", "AMBER" to "amber").forEach { (a, label) ->
                            FilterChip(
                                selected = accentTheme == a,
                                onClick = { viewModel.settingsRepository.setAccent(a) },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("\u20B9", "$", "\u20AC", "\u00A3").forEach { sym ->
                            FilterChip(
                                selected = currencySymbol == sym,
                                onClick = { viewModel.settingsRepository.setCurrencySymbol(sym) },
                                label = { Text(sym, fontSize = 12.sp) }
                            )
                        }
                    }
                    val businessPct by viewModel.settingsRepository.businessUsePct.collectAsState()
                    var businessInput by remember { mutableStateOf(if (businessPct > 0) businessPct.toString() else "") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Business use % (tax)", fontSize = 13.sp, color = Color(0xFF9AA7B4), modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = businessInput,
                            onValueChange = { businessInput = it.filter(Char::isDigit).take(3) },
                            modifier = Modifier.width(84.dp),
                            singleLine = true,
                            label = { Text("%", fontSize = 11.sp) }
                        )
                        TextButton(onClick = { viewModel.settingsRepository.setBusinessUsePct(businessInput.toIntOrNull() ?: 0) }) {
                            Text("Set")
                        }
                    }
                    Text(
                        "Currency restates money figures across Fuel, Expenses & Reports; appearance restyles dialogs, cards and inputs.",
                        fontSize = 10.sp, color = Color(0xFF9AA7B4)
                    )
                }
            }
            SettingsSectionHeader("DATA & BACKUP")

            // ── Never lose a drive (owner 2026-09-17) ─────────────────────────────────
            // The owner lost a whole day of logging to a background kill and could not see why or
            // what to do about it. This card answers both: what is protecting the recording right
            // now, what is still exposed, and the one tap that closes the exposure. It states
            // plainly that a foreground service does not make the process unkillable - claiming
            // otherwise is what let the loss happen silently.
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("card_drive_protection"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (batteryExempt) NeonEmerald else ElectricAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Never lose a drive",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Every OBD line is written to disk as it arrives, so a kill " +
                                    "costs at most one line - and the drive is rebuilt automatically " +
                                    "the next time the app or its service starts.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ProtectionStatusRow(
                        ok = batteryExempt,
                        label = "Battery optimisation",
                        detail = if (batteryExempt) {
                            "Unrestricted - Android will not doze this app out of a recording."
                        } else {
                            "STILL RESTRICTED. Motorola's battery management can stop the service " +
                                "mid-drive. This is the one setting the app cannot change for you."
                        }
                    )
                    ProtectionStatusRow(
                        ok = connectionNow == com.example.bluetooth.ConnectionState.CONNECTED,
                        label = "Keep-alive service",
                        detail = if (connectionNow == com.example.bluetooth.ConnectionState.CONNECTED) {
                            "Foreground service running${if (isRecordingNow) " and recording" else ""}, " +
                                "wake lock held, restarts itself after a swipe-away."
                        } else {
                            "Not running - it starts the moment the adapter connects."
                        }
                    )
                    ProtectionStatusRow(
                        ok = rawLogWriteFailures == 0,
                        label = "Disk writes",
                        detail = if (rawLogWriteFailures == 0) {
                            "No failed log or journal writes. All timestamps are IST (+05:30)."
                        } else {
                            "$rawLogWriteFailures write(s) FAILED - the log is incomplete. " +
                                "Check free storage now."
                        }
                    )

                    if (!batteryExempt) {
                        Button(
                            onClick = {
                                val standard = android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                val launched = runCatching { context.startActivity(standard) }.isSuccess
                                if (!launched) {
                                    // OEM firmware without the standard action: fall back to the
                                    // app details page rather than doing nothing at all.
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }.onFailure {
                                        Toast.makeText(context, "Open Settings > Apps > Kylaq TSI Coach > Battery > Unrestricted", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("btn_battery_exempt")
                        ) {
                            Icon(Icons.Default.BatterySaver, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Make battery Unrestricted")
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.runAutoRecovery() },
                        enabled = !isAutoRecovering,
                        modifier = Modifier.fillMaxWidth().testTag("btn_recover_now")
                    ) {
                        Icon(Icons.Default.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isAutoRecovering) "Checking for killed sessions..." else "Recover killed sessions now")
                    }

                    autoRecoveryNotice?.let {
                        Surface(shape = RoundedCornerShape(10.dp), color = ElectricAmber.copy(alpha = 0.14f)) {
                            Text(
                                text = it,
                                modifier = Modifier.padding(10.dp),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // ── Fuelio import (owner 2026-09-16) ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Import from Fuelio",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Bring your whole Fuelio fill-up history across: in Fuelio open " +
                                    "menu \u2192 Backup \u2192 Export (CSV) and pick that file here. Units, " +
                                    "prices and partial fill-ups are converted automatically; re-imports " +
                                    "never duplicate.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = { fuelioPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/*", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Choose Fuelio CSV")
                    }
                    fuelioNotice?.let {
                        Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = NeonEmerald)
                    }
                }
            }
            var exportStatus by remember { mutableStateOf<String?>(null) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12181F))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(8.dp))
                        Text("Export data", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFFE6EDF3))
                    }
                    Text(
                        "Unified ledger of fuel, services, expenses & documents - free, no account.",
                        fontSize = 10.sp, color = Color(0xFF9AA7B4)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val rows = FleetExporter.collectRows(
                                viewModel.fuelLogRepository.entries(),
                                viewModel.maintenanceRepository.logs(),
                                viewModel.expenseRepository.entries(),
                                viewModel.documentRepository.documents()
                            )
                            val file = FleetExporter.writeToDownloads(context, "kylaq-fleet.csv", FleetExporter.toCsv(rows))
                            exportStatus = file?.let { "CSV saved: ${it.name} (${rows.size} rows)" } ?: "CSV export failed"
                        }) { Text("CSV", fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            val rows = FleetExporter.collectRows(
                                viewModel.fuelLogRepository.entries(),
                                viewModel.maintenanceRepository.logs(),
                                viewModel.expenseRepository.entries(),
                                viewModel.documentRepository.documents()
                            )
                            val stats = viewModel.fuelLogRepository.stats()
                            val statsLine = "lifetime %.1f km/L, cost/km %.2f".format(
                                stats.avgKmPerL ?: Double.NaN, stats.costPerKm ?: Double.NaN
                            )
                            val json = FleetExporter.toJson(rows, vehicleName, statsLine)
                            val file = FleetExporter.writeToDownloads(context, "kylaq-fleet.json", json)
                            exportStatus = file?.let { "JSON saved: ${it.name}" } ?: "JSON export failed"
                        }) { Text("JSON", fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            val rows = FleetExporter.collectRows(
                                viewModel.fuelLogRepository.entries(),
                                viewModel.maintenanceRepository.logs(),
                                viewModel.expenseRepository.entries(),
                                viewModel.documentRepository.documents()
                            )
                            val stats = viewModel.fuelLogRepository.stats()
                            val downloads = java.io.File(context.getExternalFilesDir(null), "Downloads").apply { mkdirs() }
                            val pdf = java.io.File(downloads, "kylaq-fleet-summary.pdf")
                            val ok = FleetExporter.writePdf(
                                pdf,
                                "Kylaq fleet summary - $vehicleName",
                                listOf(
                                    "Lifetime %.1f km/L · cost/km %.2f · %d ledger rows".format(
                                        stats.avgKmPerL ?: Double.NaN, stats.costPerKm ?: Double.NaN, rows.size
                                    ),
                                    "Generated ${java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                                ),
                                rows
                            )
                            exportStatus = if (ok) "PDF saved: ${pdf.name}" else "PDF export failed"
                        }) { Text("PDF", fontSize = 12.sp) }
                    }
                    Text(
                        "Files land in Android/data/<package>/files/Downloads.",
                        fontSize = 10.sp, color = Color(0xFF9AA7B4)
                    )
                    exportStatus?.let {
                        Text(it, fontSize = 11.sp, color = Color(0xFF00E676))
                    }
                }
            }
            // Google Drive Backup & Sign-In (Data & backup section)
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
                                text = "Google Drive Backup & Sign-In",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "One tap: pick your Google account, trips back up to ITS Drive",
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
                                if (!googleAccountName.isNullOrBlank()) {
                                    Text(googleAccountName ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
                            text = "Back up your trip logs to Google Drive. Two paths: the one-tap folder backup " +
                                "below works TODAY with your Google account (system picker = account chooser, zero " +
                                "Console setup). Credential Manager sign-in is optional legacy auto-sync and needs a " +
                                "Cloud Console OAuth key - Google's requirement, not the app's.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = onOpenDriveBackup,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Back up on Google Drive now (no setup)", fontWeight = FontWeight.Bold, color = Color.Black)
                        }

                        GoogleSignInSetup(
                            cloudManager = cloudManager,
                            storedClientId = storedWebClientId,
                            initiallyExpanded = !signInConfigured || showSignInSetup
                        )

                        Button(
                            onClick = {
                                if (activity == null) {
                                    Toast.makeText(context, "Cannot sign in: Activity not available.", Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                coroutineScope.launch {
                                    val result = cloudManager.signInWithGoogle(activity)
                                    result.onFailure { err ->
                                        // FIX: a dismissed account chooser is not an error, and a
                                        // missing OAuth client used to surface as Google's opaque
                                        // "ApiException: 10". Both now behave sensibly.
                                        when (err) {
                                            is com.example.backup.SignInCancelledException -> Unit
                                            is com.example.backup.SignInNotConfiguredException -> {
                                                showSignInSetup = true
                                                Toast.makeText(context, err.message, Toast.LENGTH_LONG).show()
                                            }
                                            else -> Toast.makeText(
                                                context,
                                                "Google Sign-In failed: ${err.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("btn_google_sign_in"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyanDark),
                            enabled = signInConfigured || showSignInSetup
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (signInConfigured) "Sign in with Google (legacy auto-sync)"
                                else "Legacy sign-in (needs Console key) - use the green button above",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!signInConfigured) {
                            Text(
                                text = "Optional: legacy Credential Manager sign-in stays disabled until an OAuth Web " +
                                    "Client ID from Google Cloud Console is pasted above (Google requires a registered " +
                                    "client for that API). Your Drive backup does NOT need it - the green button opens " +
                                    "the system picker where you choose your Google account and folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricAmber,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

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
            // ── Car Welcome voice (owner 2026-09-16: MacroDroid-style greeting, native) ──
            SettingsSectionHeader("CAR WELCOME")
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = NeonEmerald,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Car welcome voice",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Speaks your greeting the moment the OBD link connects - no MacroDroid needed",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = welcomeEnabled, onCheckedChange = { settingsRepo.setWelcomeEnabled(it) })
                    }
                    OutlinedTextField(
                        value = welcomeMessage,
                        onValueChange = { settingsRepo.setWelcomeMessage(it) },
                        label = { Text("Greeting message") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Tip: write {car} anywhere to say the vehicle name (\"$vehicleName\").",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var voiceMenuOpen by remember { mutableStateOf(false) }
                    val voiceLabel = welcomeVoices.firstOrNull { it.id == welcomeVoiceId }?.label
                        ?: welcomeVoiceId?.let { com.example.data.WelcomeSpeaker.voiceLabel("default", it) }
                        ?: "Default engine voice"
                    ExposedDropdownMenuBox(
                        expanded = voiceMenuOpen,
                        onExpandedChange = { voiceMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = voiceLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuOpen) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = voiceMenuOpen,
                            onDismissRequest = { voiceMenuOpen = false }
                        ) {
                            welcomeVoices.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label, fontSize = 14.sp) },
                                    onClick = {
                                        viewModel.selectWelcomeVoice(v.id)
                                        voiceMenuOpen = false
                                    }
                                )
                            }
                            if (welcomeVoices.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No speech voices installed - add a TTS engine (e.g. Google TTS) in system settings", fontSize = 12.sp) },
                                    onClick = { voiceMenuOpen = false },
                                    enabled = false
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Set media volume first",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Parks the media stream at $welcomeVolumePct% before speaking",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = welcomeVolumeEnabled,
                            onCheckedChange = { settingsRepo.setWelcomeVolumeEnabled(it) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$welcomeVolumePct%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(10.dp))
                        Slider(
                            value = welcomeVolumePct.toFloat(),
                            onValueChange = { settingsRepo.setWelcomeVolumePct(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = { viewModel.testWelcomeVoice() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test voice now")
                    }
                }
            }

            SettingsSectionHeader("ABOUT & UPDATES")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAbout() }
                    .testTag("card_about"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = ElectricAmber,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "About & Fuel Guide",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Version, what \u201Clog fuel\u201D means, and MID vs recorded fuel comparison",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val updateState by viewModel.updateState.collectAsState()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_app_update"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = ElectricAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App update",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Installed ${com.example.BuildConfig.VERSION_NAME} " +
                                    "(build ${com.example.BuildConfig.VERSION_CODE})",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (updateState.downloading) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { updateState.progress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .testTag("update_progress"),
                            color = ElectricAmber
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Downloading " +
                                com.example.update.AppUpdateFeed.humanSize(updateState.downloadedBytes) +
                                " of " +
                                com.example.update.AppUpdateFeed.humanSize(updateState.totalBytes) +
                                " - the SHA-256 published with the release is verified before Android " +
                                "is asked to install anything",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    updateState.available?.let { info ->
                        if (!updateState.downloading) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "New build available: " + info.buildLabel() +
                                    if (info.sizeBytes > 0L) {
                                        " (${com.example.update.AppUpdateFeed.humanSize(info.sizeBytes)})"
                                    } else "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeonEmerald
                            )
                            if (info.commitSubject.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = info.commitSubject,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val provenance = listOf(
                                info.branch,
                                info.publishedAt.take(16).replace('T', ' ')
                            ).filter { it.isNotBlank() }.joinToString(" · ")
                            if (provenance.isNotBlank()) {
                                Text(text = provenance, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.downloadUpdate() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag("btn_update_download"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download & install", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    if (updateState.stagedFile != null && !updateState.downloading) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (updateState.signatureMismatch) {
                            Text(
                                text = "This update is signed with a different key than the copy installed " +
                                    "on this phone, so Android will refuse to install it over the top. " +
                                    "Builds made before 16 Sep 2026 were each signed with a throwaway CI " +
                                    "key - that is why updating used to mean uninstalling. This is a " +
                                    "one-time migration: export a backup (Drive folder or ZIP), uninstall " +
                                    "once, then install the new APK. Every update after that installs in " +
                                    "place and keeps your data and permissions.",
                                fontSize = 11.sp,
                                color = WarningRed
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.installUpdate() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_update_install_anyway"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Try installing anyway", fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = { viewModel.installUpdate() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag("btn_update_install"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricAmber)
                            ) {
                                Text("Install now", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    if (updateState.upToDate && updateState.available == null && !updateState.downloading) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val checkedAt = if (updateState.lastCheckedAtMs > 0L) {
                            " (checked " + SimpleDateFormat("HH:mm", Locale.US).format(Date(updateState.lastCheckedAtMs)) + ")"
                        } else ""
                        Text(
                            text = "You are on the newest build$checkedAt",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    updateState.error?.let { message ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = message, fontSize = 11.sp, color = WarningRed)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.checkForUpdate(auto = false) },
                        enabled = !updateState.checking && !updateState.downloading,
                        modifier = Modifier.testTag("btn_check_update"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (updateState.checking) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Checking…", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check for updates", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Updates come from this project's public GitHub Release and are checked " +
                            "automatically about every 6 hours. Android always shows its own install " +
                            "confirmation - a sideloaded app cannot silently replace itself, only the " +
                            "Play Store can do that.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }


            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


/**
 * Android-style section header (owner pipeline task 8, 2026-09-16: settings entries sat
 * "at random locations, hard to find"): small accent-coloured label + hairline divider,
 * grouping the cards below it the way the platform Settings app groups its pages.
 */
@Composable
/**
 * One line of the "Never lose a drive" card: a plain verdict plus what it means.
 * Green only when the protection is actually in place - an amber row that says what to do beats a
 * green one that hides an exposure.
 */
@Composable
private fun ProtectionStatusRow(ok: Boolean, label: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) NeonEmerald else ElectricAmber,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun SettingsSectionHeader(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = CyberCyan,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.height(4.dp))
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
}

/**
 * One-time Google Sign-In configuration.
 *
 * FIX (sign-in did nothing): the repository ships
 * `google_web_client_id = "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"`. Credential
 * Manager dutifully sent that to Google Play services, which answered
 * `ApiException: 10 / DEVELOPER_ERROR` — on screen that looks like an account chooser that
 * closes itself. There is no way to fix that without a real OAuth client, and a real client
 * is only valid for one package name + signing certificate, so both are shown here with copy
 * buttons and the client ID can be saved on-device (no rebuild required).
 */
@Composable
private fun GoogleSignInSetup(
    cloudManager: com.example.backup.CloudBackupManager,
    storedClientId: String?,
    initiallyExpanded: Boolean
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    // A failed sign-in asks for the setup panel; open it even if the user hid it earlier.
    LaunchedEffect(initiallyExpanded) { if (initiallyExpanded) expanded = true }
    var draft by remember(storedClientId) { mutableStateOf(storedClientId ?: "") }
    val fingerprints = remember(cloudManager) { cloudManager.signingSha1Fingerprints() }
    val packageName = context.packageName

    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("card_google_signin_setup"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = ElectricAmber
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Google Sign-In setup", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", fontSize = 12.sp)
                }
            }

            if (expanded) {
                Text(
                    text = "1. In Google Cloud Console create an OAuth 2.0 Client ID of type " +
                        "\u201CWeb application\u201D (APIs & Services \u2192 Credentials) and enable the " +
                        "\u201CGoogle Identity\u201D / People API.\n" +
                        "2. Under that client add an Android authorization entry with the package name " +
                        "and SHA-1 below.\n" +
                        "3. Paste the Web Client ID here and save. It is stored on this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CopyableRow(label = "Package name", value = packageName, onCopy = { copy("Package name", packageName) })
                if (fingerprints.isEmpty()) {
                    Text(
                        text = "Signing SHA-1 unavailable on this device — run ./gradlew signingReport on a build machine.",
                        fontSize = 11.sp,
                        color = ElectricAmber
                    )
                } else {
                    fingerprints.forEachIndexed { index, fingerprint ->
                        CopyableRow(
                            label = if (fingerprints.size > 1) "SHA-1 #${index + 1}" else "Signing SHA-1",
                            value = fingerprint,
                            onCopy = { copy("SHA-1", fingerprint) }
                        )
                    }
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.trim() },
                    label = { Text("OAuth Web Client ID") },
                    placeholder = { Text("1234567890-abc…apps.googleusercontent.com") },
                    modifier = Modifier.fillMaxWidth().testTag("input_google_web_client_id"),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val usable = cloudManager.isUsableClientId(draft)
                    Button(
                        onClick = {
                            cloudManager.setGoogleWebClientId(draft)
                            Toast.makeText(
                                context,
                                if (usable) "Web Client ID saved — try signing in again."
                                else "Saved, but that does not look like a Web Client ID.",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.weight(1f).testTag("btn_save_web_client_id"),
                        shape = RoundedCornerShape(8.dp),
                        enabled = draft.isNotBlank()
                    ) {
                        Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (!storedClientId.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                cloudManager.setGoogleWebClientId(null)
                                draft = ""
                                Toast.makeText(context, "Stored Web Client ID removed.", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                }

                if (draft.isNotBlank() && !cloudManager.isUsableClientId(draft)) {
                    Text(
                        text = "Expected format: <numbers>-<hash>.apps.googleusercontent.com",
                        fontSize = 11.sp,
                        color = ElectricAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyableRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
        TextButton(onClick = onCopy, modifier = Modifier.testTag("btn_copy_${label.replace(" ", "_")}")) {
            Text("Copy", fontSize = 12.sp)
        }
    }
}

@Composable
private fun SimpleNavCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
