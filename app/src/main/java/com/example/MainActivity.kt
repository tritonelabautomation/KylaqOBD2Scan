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

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Speed)
    object RawMonitor : Screen("raw_monitor", "Raw Monitor", Icons.Default.FormatListBulleted)
    object PidDetail : Screen("pid_detail/{pidId}", "Research", Icons.Default.Science) {
        fun createRoute(pidId: String) = "pid_detail/$pidId"
    }
    object Console : Screen("console", "Console", Icons.Default.Terminal)
    object Recordings : Screen("recordings", "Recordings", Icons.Default.Folder)
    object PidConfig : Screen("pid_config", "Config", Icons.Default.Tune)
    object Profiles : Screen("profiles", "Profiles", Icons.Default.VerifiedUser)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
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

    LaunchedEffect(Unit) {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(requiredPermissions)
    }

    val bottomNavItems = listOf(
        Screen.Dashboard,
        Screen.RawMonitor,
        Screen.Console,
        Screen.Profiles,
        Screen.PidConfig
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                bottomNavItems.forEach { screen ->
                    val isSelected = currentRoute == screen.route ||
                            (screen is Screen.PidDetail && currentRoute?.startsWith("pid_detail") == true)

                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
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
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToRawMonitor = {
                        navController.navigate(Screen.RawMonitor.route)
                    },
                    onNavigateToPidDetail = { pidId ->
                        navController.navigate(Screen.PidDetail.createRoute(pidId))
                    },
                    onOpenConnectDialog = { showConnectionDialog = true }
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

            composable(Screen.Recordings.route) {
                RecordingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.PidConfig.route) {
                PidConfigScreen(
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
        }
    }

    // Connection selection dialog
    if (showConnectionDialog) {
        ConnectionDialog(
            pairedDevices = viewModel.getPairedDevices(),
            isBluetoothEnabled = viewModel.bluetoothManager.isBluetoothEnabled,
            onDeviceSelected = { deviceAddress ->
                viewModel.connectDevice(deviceAddress)
            },
            onStartSimulation = {
                viewModel.startSimulationMode()
            },
            onDismiss = { showConnectionDialog = false }
        )
    }
}
