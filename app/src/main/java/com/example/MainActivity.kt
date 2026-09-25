package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.app.ForegroundServiceStartNotAllowedException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.example.service.ObdKeepAliveService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.Alignment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bluetooth.ConnectionState
import com.example.ui.screens.*
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonEmerald
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Speed)
    object Insights : Screen("insights", "Insights", Icons.Default.Insights)
    object CarDoctor : Screen("car_doctor", "AI Doctor", Icons.Default.HealthAndSafety)
    object DrivingDashboard : Screen("driving_hud", "Auto HUD", Icons.Default.DirectionsCar)
    object Recordings : Screen("recordings", "Trips", Icons.Default.Folder)
    object RawMonitor : Screen("raw_monitor", "Raw Monitor", Icons.Default.FormatListBulleted)
    object Console : Screen("console", "Console", Icons.Default.Terminal)
    object Garage : Screen("garage", "Garage", Icons.Default.Garage)
    object VehicleProfile : Screen("vehicle_profile/{vehicleId}", "Vehicle Profile", Icons.Default.DirectionsCar) {
        fun createRoute(vehicleId: String) = "vehicle_profile/$vehicleId"
    }
    object DtcScanner : Screen("dtc_scanner", "EPC & DTC Scanner", Icons.Default.Warning)
    object AddVehicle : Screen("add_vehicle", "Add Vehicle", Icons.Default.Add)
    object PidDetail : Screen("pid_detail/{pidId}", "Research", Icons.Default.Science) {
        fun createRoute(pidId: String) = "pid_detail/$pidId"
    }
    object TripDetail : Screen("trip_detail/{tripId}", "Trip Detail", Icons.Default.Assessment) {
        fun createRoute(tripId: String) = "trip_detail/$tripId"
    }
    object PidConfig : Screen("pid_config", "Config", Icons.Default.Tune)
    object PidScanner : Screen("pid_scanner", "PID Scanner", Icons.Default.Search)
    object CodingLab : Screen("coding_lab", "Coding Lab", Icons.Default.Build)
    object RevTheater : Screen("rev_theater", "Rev Theater", Icons.Default.MusicNote)
    object Profiles : Screen("profiles", "Profiles", Icons.Default.VerifiedUser)
    object FuelCosts : Screen("fuel_costs", "Fuel & Costs", Icons.Default.LocalGasStation)
    object Carpool : Screen("carpool", "Car Pool", Icons.Default.Groups)
    object FuelSavings : Screen("fuel_savings", "Save Fuel", Icons.Default.Savings)
    object Maintenance : Screen("maintenance", "Maintenance", Icons.Default.Build)
    object DriveBackup : Screen("drive_backup", "Drive Backup", Icons.Default.CloudUpload)
    object Expenses : Screen("expenses", "Expenses", Icons.Default.ReceiptLong)
    object Documents : Screen("documents", "Documents", Icons.Default.Description)
    object OwnerManual : Screen("owner_manual", "Owner's Manual", Icons.AutoMirrored.Filled.MenuBook)
    object Reminders : Screen("reminders", "Reminders", Icons.Default.Notifications)
    object Reports : Screen("reports", "Reports", Icons.Default.QueryStats)
    object CoachChat : Screen("coach_chat", "Coach Chat", Icons.Default.Chat)
    object Trips : Screen("trips", "Trip Planner", Icons.Default.Map)
    object TripsOverview : Screen("trip_overview", "Trip Overview", Icons.Default.CalendarMonth)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object About : Screen("about", "About & Fuel Guide", Icons.Default.Info)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Grants can change in Settings while we were not looking; the altitude footnote and the
        // Settings card must not argue with the OS.
        viewModel.refreshLocationPermissionState()
        promptBatteryExemptionIfNeeded()
    }

    /**
     * OEM battery management killed a 74-minute owner trip mid-recording (2026-09-16,
     * "Recovered Run"): the foreground service alone does not survive OEM battery
     * restrictions on this device class. Ask for the standard exemption once, while a
     * session is live, then never again - and never crash on OEM firmware that lacks
     * the standard settings action.
     */
    private fun promptBatteryExemptionIfNeeded() {
        try {
            val prefs = getSharedPreferences("kylaq_system", android.content.Context.MODE_PRIVATE)
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            val ignoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            if (!com.example.service.BatteryOptimizationPolicy.shouldPrompt(
                    isIgnoringBatteryOptimizations = ignoring,
                    alreadyPrompted = prefs.getBoolean("battery_exempt_prompted", false),
                    sessionActive = viewModel.isSessionActiveNow(),
                    lastPromptAtMs = prefs.getLong("battery_exempt_prompted_at", 0L),
                    nowMs = System.currentTimeMillis()
                )
            ) return
            prefs.edit()
                .putBoolean("battery_exempt_prompted", true)
                .putLong("battery_exempt_prompted_at", System.currentTimeMillis())
                .apply()
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            // Firmware without the standard action: skip silently.
        }
    }

    /** Hands the newest crash record to any app (Drive, Gmail, Files) via FileProvider. */
    private fun shareCrashLog() {
        val file = com.example.data.CrashJournal.crashFiles(this).firstOrNull() ?: return
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share crash log"
                )
            )
        }
    }

    /**
     * Safe mode's way out of a crash loop (owner 2026-09-20: the OOM loop left safe mode
     * as the ONLY usable screen on the broken build, with no path to the fixed one). Uses
     * the standalone [com.example.update.UpdateManager] - no ViewModel, no database, none
     * of the machinery that may be broken - and mirrors the normal path's honesty:
     * signature-checked APK, system installer confirmation, installs in place over this
     * build so no trip, log or permission is touched.
     */
    private fun safeModeUpdate(onState: (String) -> Unit) {
        lifecycleScope.launch {
            onState("Checking for a newer build...")
            val manager = com.example.update.UpdateManager(this@MainActivity)
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { manager.fetchFeed() }.getOrNull()
            }
            if (info == null) {
                onState("Could not reach the update feed - check the connection and tap again.")
                return@launch
            }
            if (!com.example.update.AppUpdateFeed.isNewerThan(info, manager.installedVersionCode())) {
                onState("This build is already the newest published - tap \"Try normal start\".")
                return@launch
            }
            onState("Downloading build ${info.versionCode}...")
            val apk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { manager.download(info) }.getOrNull()
            }
            if (apk == null) {
                onState("Download failed - check the connection and tap again.")
                return@launch
            }
            val sig = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { manager.installedSignatureMatchesApk(apk) }.getOrNull()
            }
            if (sig == false) {
                onState("Downloaded APK signature does not match this install - refusing to install it.")
                return@launch
            }
            if (!manager.canRequestPackageInstalls()) {
                onState("Allow \"install unknown apps\" for Kylaq in the screen that opened, then tap again.")
                runCatching { manager.openInstallPermissionSettings() }
                return@launch
            }
            onState("Handing the APK to the system installer - confirm there.")
            runCatching { manager.install(apk) }
                .onFailure { onState("Installer could not start: ${it.message ?: "unknown error"}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Crash-loop breaker (owner 2026-09-20: "Again app is crashing when I open it
        // crashes" with no log to say why): if the last starts died before their first
        // frame, open the ONE screen that touches nothing else - no ViewModel, no
        // database, no Bluetooth - it shows the recorded reason and can share the log.
        if (com.example.data.CrashJournal.shouldEnterSafeMode(this)) {
            val safeCrashedAt = com.example.data.CrashJournal.lastCrashedAt(this)
            val safeSummary = com.example.data.CrashJournal.lastCrashSummary(this)
            val safeText = com.example.data.CrashJournal.latestCrashText(this)
            setContent {
                var safeUpdateMsg by remember { mutableStateOf<String?>(null) }
                com.example.ui.screens.CrashSafeModeScreen(
                    crashedAt = safeCrashedAt,
                    summary = safeSummary,
                    fullText = safeText,
                    onShare = { shareCrashLog() },
                    onTryNormalStart = {
                        com.example.data.CrashJournal.enterNormalModeAgain(this)
                        recreate()
                    },
                    updateMessage = safeUpdateMsg,
                    onUpdate = { safeModeUpdate { safeUpdateMsg = it } }
                )
            }
            return
        }

        // Read ONCE, off-composition: check if there is an unacknowledged crash notice.
        val hasUnseenCrash = com.example.data.CrashJournal.hasUnseenCrash(this)
        val lastCrashSummary = if (hasUnseenCrash) com.example.data.CrashJournal.lastCrashSummary(this) else null
        val lastCrashedAt = if (hasUnseenCrash) com.example.data.CrashJournal.lastCrashedAt(this) else null
        setContent {
            // This start counts as healthy only after the UI survived its first seconds:
            // the ViewModel's init coroutines (auto-connect, refuel backfill, update
            // check) run right after composition, and a crash inside one of them is
            // still a start crash.
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(4_000)
                com.example.data.CrashJournal.startupCompleted(this@MainActivity)
            }
            var showCrashNotice by remember { mutableStateOf(lastCrashSummary != null) }
            if (showCrashNotice && lastCrashSummary != null) {
                com.example.ui.screens.CrashNoticeDialog(
                    crashedAt = lastCrashedAt,
                    summary = lastCrashSummary,
                    onShare = { shareCrashLog() },
                    onDismiss = {
                        com.example.data.CrashJournal.dismissCrashNotice(this@MainActivity)
                        showCrashNotice = false
                    }
                )
            }
            val appearance by viewModel.settingsRepository.appearanceMode.collectAsState()
            MyApplicationTheme(
                darkTheme = when (appearance) {
                    "LIGHT" -> false
                    "SYSTEM" -> androidx.compose.foundation.isSystemInDarkTheme()
                    else -> true
                }
            ) {
                MainApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var showConnectionDialog by remember { mutableStateOf(false) }

    // Bluetooth Permissions handling
    var hasBluetoothPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        hasBluetoothPermission = allGranted
        // Background location (owner field report 2026-09-18: "Altitude"). On API 29 the startup
        // dialog carries an "allow all the time" checkbox; if the foreground grant came back but the
        // background one did not, that is a decline and the cooldown starts, so the app does not
        // re-raise the same dialog on every launch.
        val askedBg = permissions.keys.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val fgNow = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (askedBg && fgNow && permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] != true) {
            viewModel.noteBackgroundLocationDeclined()
        } else {
            viewModel.refreshLocationPermissionState()
        }
    }

    val defaultBtAddress by viewModel.settingsRepository.defaultBtAddress.collectAsState()
    val autoConnect by viewModel.settingsRepository.autoConnect.collectAsState()
    val autoRecord by viewModel.settingsRepository.autoRecord.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startSessionAutomation()
        viewModel.refreshDueNotifications()
        // Silent, throttled update check (2026-09-16): the owner asked for a store-style
        // "update available" instead of downloading and reinstalling by hand. Manual
        // checks stay available in Settings and are never throttled.
        viewModel.checkForUpdate(auto = true)
        val basePermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) +
            // Only on API 29, where the system renders it as a checkbox inside this same dialog,
            // and only outside a decline cooldown. On API 30+ bundling it is silently ignored -
            // that level gets the dedicated card in Settings instead.
            if (com.example.service.BackgroundLocationPolicy.includeInStartupRequest(
                    Build.VERSION.SDK_INT,
                    viewModel.settingsRepository.lastBgLocationDeclinedMs(),
                    System.currentTimeMillis()
                )
            ) {
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                emptyArray()
            }
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            basePermissions + arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            basePermissions + arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        permissionLauncher.launch(requiredPermissions)
    }

    // Background keep-alive (2026-09-09): while the adapter is CONNECTED the process
    // runs as a foreground service so Android cannot silently kill the OBD session.
    val keepAliveConnection by viewModel.connectionState.collectAsState()
    val keepAliveRecording by viewModel.isRecording.collectAsState()
    val keepAliveContext = LocalContext.current
    var keepAliveRetryPending by remember { mutableStateOf(false) }
    val startKeepAlive: (Boolean) -> Boolean = { recording ->
        val intent = Intent(keepAliveContext, ObdKeepAliveService::class.java)
            .putExtra(ObdKeepAliveService.EXTRA_RECORDING, recording)
        val result = runCatching { ContextCompat.startForegroundService(keepAliveContext, intent) }
        if (result.exceptionOrNull() is ForegroundServiceStartNotAllowedException) {
            keepAliveRetryPending = true
        }
        result.isSuccess
    }
    LaunchedEffect(keepAliveConnection, keepAliveRecording) {
        if (keepAliveConnection == ConnectionState.CONNECTED) {
            startKeepAlive(keepAliveRecording)
        } else {
            keepAliveRetryPending = false
            runCatching { keepAliveContext.stopService(Intent(keepAliveContext, ObdKeepAliveService::class.java)) }
        }
    }
    // QA H1: Android 12+ forbids starting a foreground service while the app is in the
    // background; auto-connect can reach CONNECTED with the activity stopped. Retry on resume.
    val keepAliveLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(keepAliveLifecycleOwner, keepAliveConnection, keepAliveRetryPending) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                keepAliveRetryPending &&
                keepAliveConnection == ConnectionState.CONNECTED
            ) {
                if (startKeepAlive(keepAliveRecording)) keepAliveRetryPending = false
            }
        }
        keepAliveLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { keepAliveLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── In-app updater prompt (owner 2026-09-16) ────────────────────────────────────
    val updateState by viewModel.updateState.collectAsState()

    if (updateState.shouldPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdatePrompt() },
            modifier = Modifier.testTag("dialog_app_update"),
            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
            title = { Text("Update available") },
            text = {
                Column {
                    Text(
                        "Build ${updateState.available?.buildLabel() ?: ""} is published on GitHub (" +
                            com.example.update.AppUpdateFeed.humanSize(updateState.available?.sizeBytes ?: 0L) + ")."
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "It installs straight over this one — your trips, raw logs, fuel records " +
                            "and permissions all stay. No uninstall, no ZIP extraction.",
                        fontSize = 12.sp
                    )
                    val newestChange = updateState.available?.commitSubject?.takeIf { it.isNotBlank() }
                    if (newestChange != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Newest change: $newestChange", fontSize = 11.sp, color = Color(0xFF8E8E93))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.downloadUpdate() }, modifier = Modifier.testTag("btn_update_download")) {
                    Text("Download & install")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdatePrompt() }) { Text("Later") }
            }
        )
    }

    val bottomNavItems = listOf(
        Screen.Dashboard,
        Screen.Insights,
        Screen.Garage,
        Screen.CarDoctor,
        Screen.Recordings,
        Screen.RawMonitor,
        Screen.Console
    )

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val accentTheme by viewModel.settingsRepository.accent.collectAsState()
    androidx.compose.runtime.LaunchedEffect(accentTheme) {
        com.example.ui.theme.setAccentColor(
            when (accentTheme) {
                "RED" -> androidx.compose.ui.graphics.Color(0xFFFF2D3F)
                "AMBER" -> androidx.compose.ui.graphics.Color(0xFFFFB300)
                else -> androidx.compose.ui.graphics.Color(0xFF00E5FF)
            }
        )
    }
    val drawerItems = bottomNavItems + listOf(
        Screen.DtcScanner, Screen.FuelCosts, Screen.Carpool, Screen.FuelSavings, Screen.Maintenance, Screen.Expenses, Screen.Reports,
        Screen.CoachChat, Screen.Trips, Screen.TripsOverview, Screen.Reminders, Screen.Documents, Screen.OwnerManual, Screen.DriveBackup,
        Screen.PidScanner, Screen.CodingLab, Screen.RevTheater, Screen.Settings, Screen.About
    )
    val mainTabRoutes = bottomNavItems.map { it.route }.toSet()
    var showQuickAdd by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "Kylaq TSI Coach",
                    color = CyberCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
                Text(
                    "Skoda Kylaq 1.0 TSI - drive intelligence",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                // 2026-09-14 owner screenshot: the sheet is a plain Column, so on a
                // phone the last drawer entries (Rev Theater, PID Scanner, Coding Lab,
                // Settings, About) fell below the fold and were UNREACHABLE. Scroll fix.
                // weight(1f) gives this list an EXACT bounded slot of the remaining sheet
                // height - without it some sheet implementations measure children
                // unbounded, the scroll range collapses to zero and the tail entries
                // (Rev Theater ... About) stay clipped below the fold (owner: "there is
                // no scroll when select hamburger").
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                drawerItems.forEach { screen ->
                    NavigationDrawerItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            drawerScope.launch { drawerState.close() }
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                }
            }
        }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel = viewModel,
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    onNavigateToRawMonitor = {
                        navController.navigate(Screen.RawMonitor.route)
                    },
                    onNavigateToPidDetail = { pidId ->
                        navController.navigate(Screen.PidDetail.createRoute(pidId))
                    },
                    onNavigateToCarDoctor = {
                        navController.navigate(Screen.CarDoctor.route)
                    },
                    onNavigateToTrips = {
                        navController.navigate(Screen.Recordings.route)
                    },
                    onNavigateToHud = {
                        navController.navigate(Screen.DrivingDashboard.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToPidScanner = {
                        navController.navigate(Screen.PidScanner.route)
                    },
                    onNavigateToDtc = {
                        navController.navigate(Screen.DtcScanner.route)
                    },
                    onOpenConnectDialog = { showConnectionDialog = true }
                )
            }

            composable(Screen.Insights.route) {
                InsightsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.CarDoctor.route) {
                AiDoctorScreen(
                    viewModel = viewModel,
                    onNavigateToTripDetail = { tripId ->
                        navController.navigate(Screen.TripDetail.createRoute(tripId))
                    },
                    onBack = { navController.popBackStack() }
                    
                )
            }

            composable(Screen.DrivingDashboard.route) {
                DrivingDashboardScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    
                )
            }

            composable(Screen.RawMonitor.route) {
                RawMonitorScreen(
                    viewModel = viewModel,
                    onNavigateToPidDetail = { pidId ->
                        navController.navigate(Screen.PidDetail.createRoute(pidId))
                    }
                )
            }

            composable(
                route = Screen.PidDetail.route,
                arguments = listOf(navArgument("pidId") { type = NavType.StringType; defaultValue = "0170" })
            ) { backStackEntry ->
                val pidId = backStackEntry.arguments?.getString("pidId") ?: "0170"
                PidDetailScreen(
                    pidIdParam = pidId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    
                )
            }

            composable(Screen.Console.route) {
                AdapterConsoleScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    
                )
            }

                        composable(Screen.Garage.route) {
                val allVehicles by viewModel.recordingManager.tripRepository.allVehiclesFlow.collectAsState(initial = emptyList())
                val garageScope = rememberCoroutineScope()
                VehicleGarageScreen(
                    vehicles = allVehicles,
                    onAddVehicle = { navController.navigate(Screen.AddVehicle.route) },
                    onAutoScan = { navController.navigate("auto_scan_obd") },
                    onOpenProfiles = { navController.navigate(Screen.Profiles.route) },
                    onSelectVehicle = { vehicle ->
                        val name = if (vehicle.nickname.isNullOrBlank()) "${vehicle.make} ${vehicle.model}" else vehicle.nickname
                        viewModel.setVehicleName(name)
                        navController.navigate(Screen.VehicleProfile.createRoute(vehicle.id))
                    },
                    onUpdateVehicle = { v ->
                        garageScope.launch { viewModel.recordingManager.tripRepository.insertVehicle(v) }
                    },
                    onDeleteVehicle = { v ->
                        garageScope.launch { viewModel.recordingManager.tripRepository.deleteVehicle(v.id) }
                    },
                    onMoveVehicle = { vehicle, delta ->
                        garageScope.launch {
                            val list = allVehicles.toMutableList()
                            val from = list.indexOfFirst { it.id == vehicle.id }
                            val to = from + delta
                            if (from >= 0 && to in list.indices) {
                                val moved = list.removeAt(from)
                                list.add(to, moved)
                                // Re-index the whole garage so the manual order becomes explicit.
                                list.forEachIndexed { i, v ->
                                    viewModel.recordingManager.tripRepository.insertVehicle(v.copy(sortOrder = i))
                                }
                            }
                        }
                    }
                )
            }
            
            composable(Screen.VehicleProfile.route) { backStackEntry ->
                val vehicleId = backStackEntry.arguments?.getString("vehicleId")
                val allVehicles by viewModel.recordingManager.tripRepository.allVehiclesFlow.collectAsState(initial = emptyList())
                val vehicle = allVehicles.find { it.id == vehicleId }
                val profileNow = remember(vehicleId) { System.currentTimeMillis() }
                val fuelStats = remember(vehicleId) { viewModel.fuelLogRepository.stats() }
                val ownershipRows = remember(vehicleId) {
                    val nextDue = viewModel.maintenanceRepository.dueStates(profileNow)
                        .filter {
                            it.status == com.example.data.MaintenanceCatalog.DueStatus.DUE_SOON ||
                                it.status == com.example.data.MaintenanceCatalog.DueStatus.OVERDUE
                        }
                        .minByOrNull { it.kmRemaining ?: Double.MAX_VALUE }
                    listOf(
                        "Avg Fuel Economy" to (fuelStats.avgKmPerL?.let { String.format(java.util.Locale.US, "%.1f km/L (%d logs)", it, fuelStats.entryCount) } ?: "Not enough data"),
                        "Running Cost" to (fuelStats.costPerKm?.let { String.format(java.util.Locale.US, "%.2f/km", it) } ?: "--"),
                        "Odometer" to (viewModel.maintenanceRepository.currentOdometerKm()?.let { String.format(java.util.Locale.US, "%.0f km", it) } ?: "Not set"),
                        "Next Maintenance" to (nextDue?.let { "${it.item.label} (${it.headline})" } ?: "Nothing due")
                    )
                }
                val upcomingRows = remember(vehicleId) {
                    val rows = mutableListOf<String>()
                    viewModel.maintenanceRepository.dueStates(profileNow)
                        .filter {
                            it.status == com.example.data.MaintenanceCatalog.DueStatus.OVERDUE ||
                                it.status == com.example.data.MaintenanceCatalog.DueStatus.DUE_SOON
                        }
                        .take(4)
                        .forEach { rows.add("${it.item.label} - ${it.headline}") }
                    viewModel.documentRepository.expiringWithin(30, profileNow)
                        .forEach { pair ->
                            val doc = pair.first
                            val ms = pair.second
                            rows.add(if (ms < 0) "${doc.type}: ${doc.title} EXPIRED" else "${doc.type}: ${doc.title} expires in ${ms / 86400000L} d")
                        }
                    rows
                }
                val recentRows = remember(vehicleId) {
                    val cur = viewModel.settingsRepository.currencySymbol.value
                    val items = mutableListOf<Pair<Long, String>>()
                    viewModel.fuelLogRepository.entries().take(3).forEach {
                        items.add(it.idMs to "${it.dateUtc.take(10)} · Fuel ${String.format(java.util.Locale.US, "%.1f", it.liters)} L · $cur${String.format("%.0f", it.totalCost)}")
                    }
                    viewModel.maintenanceRepository.logs().take(3).forEach {
                        items.add(it.dateMs to ("${it.dateUtc.take(10)} · Service ${it.itemId}" + (it.cost?.let { c -> " · $cur${String.format(java.util.Locale.US, "%.0f", c)}" } ?: "")))
                    }
                    viewModel.expenseRepository.entries().take(3).forEach {
                        items.add(it.idMs to "${it.dateUtc.take(10)} · ${it.category} · $cur${String.format(java.util.Locale.US, "%.0f", it.amount)}")
                    }
                    items.sortedByDescending { it.first }.take(6).map { it.second }
                }
                val allDtcs by viewModel.recordingManager.tripRepository.dtcRecordsFlow.collectAsState(initial = emptyList())
                val currentOdoKm = viewModel.maintenanceRepository.currentOdometerKm()
                com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() },
                    onNavigateToDtc = { navController.navigate(Screen.DtcScanner.route) },
                    onNavigateToPidScanner = { navController.navigate(Screen.PidScanner.route) },
                    ownership = ownershipRows,
                    upcoming = upcomingRows,
                    recent = recentRows,
                    dtcRecords = allDtcs,
                    currentOdoKm = currentOdoKm
                )
            }
            
            composable(Screen.DtcScanner.route) {
                com.example.ui.screens.DtcScannerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    
                )
            }
            composable("auto_scan_obd") {
                com.example.ui.screens.AutoScanObdScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    ,
                    onManualSelect = { 
                        navController.navigate(Screen.AddVehicle.route) {
                            popUpTo(Screen.Garage.route) // clear auto scan from backstack
                        }
                    }
                )
            }
            composable(Screen.AddVehicle.route) {
                val coroutineScope = rememberCoroutineScope()
                AddVehicleScreen(
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() }
                    ,
                    onVehicleConfirmed = { make, model, year, variantId ->
                        coroutineScope.launch {
                            viewModel.recordingManager.tripRepository.insertVehicle(
                                com.example.data.db.entities.VehicleEntity(
                                    id = java.util.UUID.randomUUID().toString(),
                                    make = make,
                                    model = model,
                                    year = year,
                                    catalogVariantId = variantId,
                                    catalogSource = "MANUAL",
                                    catalogConfidence = "HIGH",
                                    defaultProtocol = null,
                                    vin = viewModel.vehicleVin.value.takeIf { !it.isNullOrBlank() && !it.contains("Unavailable", ignoreCase = true) && !it.contains("Failed", ignoreCase = true) }
                                )
                            )
                            navController.popBackStack()
                        }
                    }
                )
            }

            composable(Screen.Recordings.route) {
                RecordingsScreen(
                    viewModel = viewModel,
                    onNavigateToTripDetail = { tripId ->
                        navController.navigate(Screen.TripDetail.createRoute(tripId))
                    },
                    onBack = { navController.popBackStack() }
                    
                )
            }

            composable(
                route = Screen.TripDetail.route,
                arguments = listOf(navArgument("tripId") { type = NavType.StringType; defaultValue = "" })
            ) { backStackEntry ->
                val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
                TripDetailScreen(
                    tripId = tripId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    
                )
            }

            composable(Screen.PidConfig.route) {
                PidConfigScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToPidScanner = { navController.navigate(Screen.PidScanner.route) }
                )
            }
            composable(Screen.PidScanner.route) {
                com.example.ui.screens.PidScannerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.RevTheater.route) {
                com.example.ui.screens.RevTheaterScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.CodingLab.route) {
                com.example.ui.screens.CodingLabScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Profiles.route) {
                val profilesViewModel: com.example.ui.viewmodel.ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                ProfilesScreen(
                    viewModel = profilesViewModel,
                    mainViewModel = viewModel,
                    onBack = { navController.popBackStack() }
                    
                )
            }

            composable(Screen.FuelCosts.route) {
                FuelCostsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Carpool.route) {
                CarpoolScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.TripsOverview.route) {
                TripsOverviewScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenTrip = { tripId -> navController.navigate("trip_detail/$tripId") }
                )
            }
            composable(Screen.FuelSavings.route) {
                FuelSavingsGuideScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenFuelCosts = { navController.navigate(Screen.FuelCosts.route) },
                    onOpenDtc = { navController.navigate(Screen.DtcScanner.route) },
                    onOpenMaintenance = { navController.navigate(Screen.Maintenance.route) }
                )
            }
            composable(Screen.Maintenance.route) {
                MaintenanceScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.DriveBackup.route) {
                DriveBackupScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Expenses.route) {
                ExpensesScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Documents.route) {
                DocumentsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.OwnerManual.route) {
                OwnerManualScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Reminders.route) {
                RemindersScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Reports.route) {
                ReportsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.CoachChat.route) {
                CoachChatScreen(viewModel = viewModel)
            }
            composable(Screen.Trips.route) {
                TripsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenAbout = { navController.navigate(Screen.About.route) },
                    onOpenPidConfig = { navController.navigate(Screen.PidConfig.route) },
                    onOpenFuelCosts = { navController.navigate(Screen.FuelCosts.route) },
                    onOpenMaintenance = { navController.navigate(Screen.Maintenance.route) },
                    onOpenDriveBackup = { navController.navigate(Screen.DriveBackup.route) },
                    onOpenExpenses = { navController.navigate(Screen.Expenses.route) },
                    onOpenDocuments = { navController.navigate(Screen.Documents.route) },
                    onOpenReminders = { navController.navigate(Screen.Reminders.route) },
                    onOpenReports = { navController.navigate(Screen.Reports.route) }
                )
            }

            composable(Screen.About.route) {
                AboutScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
        if (currentRoute in mainTabRoutes) {
            FloatingActionButton(
                onClick = { showQuickAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = CyberCyan,
                contentColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Default.Add, contentDescription = "Quick add")
            }
        }
        }
    }
    }

    // Restart-refuel popup (owner 2026-09-19): the level rise that happened engine-off at the
    // pump is proven by the first fuel-level row after the restart. Hosted above the NavHost so
    // it reaches the owner on whatever screen he lands on - once per event, both answers end it.
    val restartRefuel by viewModel.restartRefuel.collectAsState()
    restartRefuel?.let { c ->
        AlertDialog(
            onDismissRequest = { viewModel.answerRestartRefuel(addReceipt = false) },
            title = { Text("Fuel change detected") },
            text = {
                Text(
                    "Tank went from " +
                        String.format(java.util.Locale.US, "%.1f", c.levelBeforePct) + "% to " +
                        String.format(java.util.Locale.US, "%.1f", c.levelAfterPct) +
                        "% while the engine was off - about " +
                        String.format(java.util.Locale.US, "%.1f", c.estLitres) +
                        " L (estimated from the level rise, not measured). Add the pump receipt?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.answerRestartRefuel(addReceipt = true)
                    navController.navigate(Screen.FuelCosts.route)
                }) { Text("Add Receipt") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerRestartRefuel(addReceipt = false) }) {
                    Text("Not Now")
                }
            }
        )
    }

    if (showQuickAdd) {
        AlertDialog(
            onDismissRequest = { showQuickAdd = false },
            title = { Text("Quick add") },
            text = {
                Column {
                    listOf(
                        "Fuel fill-up" to { viewModel.setQuickAdd("fuel"); navController.navigate(Screen.FuelCosts.route) },
                        "Service" to { viewModel.setQuickAdd("service"); navController.navigate(Screen.Maintenance.route) },
                        "Expense" to { viewModel.setQuickAdd("expense"); navController.navigate(Screen.Expenses.route) },
                        "Document" to { viewModel.setQuickAdd("document"); navController.navigate(Screen.Documents.route) }
                    ).forEach { (label, action) ->
                        TextButton(onClick = { showQuickAdd = false; action() }) {
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQuickAdd = false }) { Text("Close") } }
        )
    }


    // Connection selection dialog
    if (showConnectionDialog) {
        ConnectionDialog(
            pairedDevices = viewModel.bluetoothManager.getPairedDevices(),
            isBluetoothEnabled = viewModel.bluetoothManager.isBluetoothEnabled,
            onDeviceSelected = { deviceAddress ->
                viewModel.connectDevice(deviceAddress)
            },
            onStartSimulation = {
                viewModel.startSimulationMode()
            },
            onDismiss = { showConnectionDialog = false },
            defaultAddress = defaultBtAddress,
            onSetDefault = { address -> viewModel.settingsRepository.setDefaultBtAddress(address) },
            autoConnect = autoConnect,
            onAutoConnectChanged = { enabled -> viewModel.settingsRepository.setAutoConnect(enabled) },
            autoRecord = autoRecord,
            onAutoRecordChanged = { enabled -> viewModel.settingsRepository.setAutoRecord(enabled) }
        )
    }
}
