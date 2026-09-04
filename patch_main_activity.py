with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    main_content = f.read()

screen_import_pos = main_content.find("object AddVehicle : Screen(\"add_vehicle\", \"Add Vehicle\", Icons.Default.Add)")
if screen_import_pos != -1:
    main_content = main_content[:screen_import_pos] + "object VehicleProfile : Screen(\"vehicle_profile/{vehicleId}\", \"Vehicle Profile\", Icons.Default.DirectionsCar) {\n        fun createRoute(vehicleId: String) = \"vehicle_profile/$vehicleId\"\n    }\n    " + main_content[screen_import_pos:]

route_replace = """            composable(Screen.Garage.route) {
                val allVehicles by viewModel.recordingManager.tripRepository.allVehiclesFlow.collectAsState(initial = emptyList())
                VehicleGarageScreen(
                    vehicles = allVehicles,
                    onAddVehicle = { navController.navigate(Screen.AddVehicle.route) },
                    onSelectVehicle = { vehicle ->
                        val name = if (vehicle.nickname.isNullOrBlank()) "${vehicle.make} ${vehicle.model}" else vehicle.nickname
                        viewModel.setVehicleName(name)
                        navController.navigate(Screen.VehicleProfile.createRoute(vehicle.id))
                    }
                )
            }
"""
import re
main_content = re.sub(r'composable\(Screen\.Garage\.route\) \{.*?\n\s+\}', route_replace, main_content, flags=re.DOTALL)

route_add = """
            composable(Screen.VehicleProfile.route) { backStackEntry ->
                val vehicleId = backStackEntry.arguments?.getString("vehicleId")
                val allVehicles by viewModel.recordingManager.tripRepository.allVehiclesFlow.collectAsState(initial = emptyList())
                val vehicle = allVehicles.find { it.id == vehicleId }
                com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    onBack = { navController.popBackStack() }
                )
            }
"""
main_content = main_content.replace("composable(Screen.AddVehicle.route) {", route_add + "            composable(Screen.AddVehicle.route) {")

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(main_content)
