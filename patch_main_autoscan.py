with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    main_content = f.read()

route_add = """
            composable("auto_scan_obd") {
                com.example.ui.screens.AutoScanObdScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onManualSelect = { 
                        navController.navigate(Screen.AddVehicle.route) {
                            popUpTo(Screen.Garage.route) // clear auto scan from backstack
                        }
                    }
                )
            }
"""
main_content = main_content.replace("composable(Screen.AddVehicle.route) {", route_add + "            composable(Screen.AddVehicle.route) {")

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(main_content)
