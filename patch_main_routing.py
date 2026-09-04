with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    text = f.read()

import re

old_block = """                com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() }
                )"""

new_block = """                com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() },
                    onNavigateToDtc = { navController.navigate(Screen.DtcScanner.route) }
                )"""

text = text.replace(old_block, new_block)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(text)
