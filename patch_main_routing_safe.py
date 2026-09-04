with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    text = f.read()

import re
text = re.sub(r'com\.example\.ui\.screens\.VehicleProfileScreen\(\s*vehicle = vehicle,\s*catalogRepository = viewModel\.catalogRepository,\s*onBack = \{ navController\.popBackStack\(\) \}\s*\)', 
"""com.example.ui.screens.VehicleProfileScreen(
                    vehicle = vehicle,
                    catalogRepository = viewModel.catalogRepository,
                    onBack = { navController.popBackStack() },
                    onNavigateToDtc = { navController.navigate(Screen.DtcScanner.route) }
                )""", text)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(text)
