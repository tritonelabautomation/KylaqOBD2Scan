import re

with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    text = f.read()

# Only VehicleProfileScreen should have onNavigateToDtc
text = re.sub(r',\s*onNavigateToDtc = \{ navController\.navigate\(Screen\.DtcScanner\.route\) \}', '', text)

# Now add it back only to VehicleProfileScreen
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

if old_block in text:
    text = text.replace(old_block, new_block)
else:
    print("Could not find the VehicleProfileScreen block. Wait, did the regex remove it?")
    text = re.sub(r'com\.example\.ui\.screens\.VehicleProfileScreen\(\s*vehicle = vehicle,\s*catalogRepository = viewModel\.catalogRepository,\s*onBack = \{ navController\.popBackStack\(\) \}\s*\)', 
"""com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() },
                    onNavigateToDtc = { navController.navigate(Screen.DtcScanner.route) }
                )""", text)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(text)
