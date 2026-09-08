package com.example.ui.screens

import android.app.Activity
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
import androidx.compose.ui.platform.testTag
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
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val vehicleName by viewModel.vehicleName.collectAsState()
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
            val unitsMetric by viewModel.settingsRepository.unitsMetric.collectAsState()
            val currencySymbol by viewModel.settingsRepository.currencySymbol.collectAsState()
            val appearanceMode by viewModel.settingsRepository.appearanceMode.collectAsState()
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
                            "reconnect - the head-unit launcher should list it. NOTE: the parked route needs the " +
                            "newer AA stack (AABrowser itself demands Android 15+); on Android 13-14 phones " +
                            "only PLAY-TRUSTED installs list - use Play Console Internal App Sharing " +
                            "(upload AAB, install via the shared link) for a guaranteed head-unit icon. " +
                            "This build also adds a phone-side 'Car Dash' launcher icon. Details: " +
                            "docs/reference/android-auto-install-guide.md.",
                        color = TextSecondaryDark,
                        fontSize = 10.sp
                    )
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
