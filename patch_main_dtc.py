with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    text = f.read()

route_import = "object VehicleProfile : Screen(\"vehicle_profile/{vehicleId}\", \"Vehicle Profile\", Icons.Default.DirectionsCar) {\n        fun createRoute(vehicleId: String) = \"vehicle_profile/$vehicleId\"\n    }\n    "
route_add = "object DtcScanner : Screen(\"dtc_scanner\", \"DTC Scanner\", Icons.Default.Warning)\n    "
text = text.replace(route_import, route_import + route_add)

screen_add = """            composable(Screen.DtcScanner.route) {
                com.example.ui.screens.DtcScannerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
"""
text = text.replace("            composable(\"auto_scan_obd\") {", screen_add + "            composable(\"auto_scan_obd\") {")

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(text)
