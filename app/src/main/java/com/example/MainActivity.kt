package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
    object DtcScanner : Screen("dtc_scanner", "DTC Scanner", Icons.Default.Warning)
    object AddVehicle : Screen("add_vehicle", "Add Vehicle", Icons.Default.Add)
    object PidDetail : Screen("pid_detail/{pidId}", "Research", Icons.Default.Science) {
        fun createRoute(pidId: String) = "pid_detail/$pidId"
    }
    object TripDetail : Screen("trip_detail/{tripId}", "Trip Detail", Icons.Default.Assessment) {
        fun createRoute(tripId: String) = "trip_detail/$tripId"
    }
    object PidConfig : Screen("pid_config", "Config", Icons.Default.Tune)
    object PidScanner : Screen("pid_scanner", "PID Scanner", Icons.Default.Search)
    object Profiles : Screen("profiles", "Profiles", Icons.Default.VerifiedUser)
    object FuelCosts : Screen("fuel_costs", "Fuel & Costs", Icons.Default.LocalGasStation)
    object Maintenance : Screen("maintenance", "Maintenance", Icons.Default.Build)
    object DriveBackup : Screen("drive_backup", "Drive Backup", Icons.Default.CloudUpload)
    object Expenses : Screen("expenses", "Expenses", Icons.Default.ReceiptLong)
    object Documents : Screen("documents", "Documents", Icons.Default.Description)
    object Reminders : Screen("reminders", "Reminders", Icons.Default.Notifications)
    object Reports : Screen("reports", "Reports", Icons.Default.QueryStats)
    object CoachChat : Screen("coach_chat", "Coach Chat", Icons.Default.Chat)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object About : Screen("about", "About & Fuel Guide", Icons.Default.Info)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
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
    }

    val defaultBtAddress by viewModel.settingsRepository.defaultBtAddress.collectAsState()
    val autoConnect by viewModel.settingsRepository.autoConnect.collectAsState()
    val autoRecord by viewModel.settingsRepository.autoRecord.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startSessionAutomation()
        viewModel.refreshDueNotifications()
        val basePermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            basePermissions + arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            basePermissions + arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        permissionLauncher.launch(requiredPermissions)
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
    val drawerItems = bottomNavItems + listOf(
        Screen.FuelCosts, Screen.Maintenance, Screen.Expenses, Screen.Reports,
        Screen.CoachChat, Screen.Reminders, Screen.Documents, Screen.DriveBackup, Screen.Settings, Screen.About
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
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                bottomNavItems.forEach { screen ->
                    // FIX (bug: tab highlight inconsistent on nested screens)
                    val isCurrentScreen = currentRoute == screen.route ||
                            (screen.route == Screen.PidDetail.route && currentRoute?.startsWith("pid_detail") == true) ||
                            (screen.route == Screen.Recordings.route && currentRoute?.startsWith("trip_detail") == true) ||
                            (screen.route == Screen.Garage.route && (currentRoute?.startsWith("vehicle_profile") == true || currentRoute?.startsWith("add_vehicle") == true))

                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title, fontSize = 10.sp, fontWeight = if (isCurrentScreen) FontWeight.Bold else FontWeight.Normal) },
                        selected = isCurrentScreen,
                        onClick = {
                            if (!isCurrentScreen) {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Dashboard.route) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyberCyan,
                            selectedTextColor = CyberCyan,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.testTag("nav_item_${screen.title.lowercase().replace(" ", "_")}")
                    )
                }
            }
        }
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
                        "Avg Fuel Economy" to (fuelStats.avgKmPerL?.let { String.format("%.1f km/L (%d logs)", it, fuelStats.entryCount) } ?: "Not enough data"),
                        "Running Cost" to (fuelStats.costPerKm?.let { String.format("%.2f/km", it) } ?: "--"),
                        "Odometer" to (viewModel.maintenanceRepository.currentOdometerKm()?.let { String.format("%.0f km", it) } ?: "Not set"),
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
                        items.add(it.idMs to "${it.dateUtc.take(10)} · Fuel ${String.format("%.1f", it.liters)} L · $cur${String.format("%.0f", it.totalCost)}")
                    }
                    viewModel.maintenanceRepository.logs().take(3).forEach {
                        items.add(it.dateMs to ("${it.dateUtc.take(10)} · Service ${it.itemId}" + (it.cost?.let { c -> " · $cur${String.format("%.0f", c)}" } ?: "")))
                    }
                    viewModel.expenseRepository.entries().take(3).forEach {
                        items.add(it.idMs to "${it.dateUtc.take(10)} · ${it.category} · $cur${String.format("%.0f", it.amount)}")
                    }
                    items.sortedByDescending { it.first }.take(6).map { it.second }
                }
                com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() },
                    onNavigateToDtc = { navController.navigate(Screen.DtcScanner.route) },
                    onNavigateToPidScanner = { navController.navigate(Screen.PidScanner.route) },
                    ownership = ownershipRows,
                    upcoming = upcomingRows,
                    recent = recentRows
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
            composable(Screen.Reminders.route) {
                RemindersScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Reports.route) {
                ReportsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.CoachChat.route) {
                CoachChatScreen(viewModel = viewModel)
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
