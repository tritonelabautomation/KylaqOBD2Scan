with open("app/src/main/java/com/example/ui/screens/VehicleGarageScreen.kt", "r") as f:
    content = f.read()

content = content.replace("Button(onClick = onAddVehicle, modifier = Modifier.weight(1f)) {\n            Text(\"Add Vehicle\")\n        }",
"""Button(onClick = onAddVehicle, modifier = Modifier.weight(1f)) {
                Text("Manual Add")
            }
            OutlinedButton(onClick = onAddVehicle, modifier = Modifier.weight(1f)) {
                Text("Auto Scan (OBD)")
            }
        }""")

content = content.replace("fun VehicleGarageScreen(\n    vehicles: List<VehicleEntity>,\n    onAddVehicle: () -> Unit,\n    onSelectVehicle: (VehicleEntity) -> Unit\n) {",
"""fun VehicleGarageScreen(
    vehicles: List<VehicleEntity>,
    onAddVehicle: () -> Unit,
    onAutoScan: () -> Unit,
    onSelectVehicle: (VehicleEntity) -> Unit
) {""")

content = content.replace("OutlinedButton(onClick = onAddVehicle, modifier = Modifier.weight(1f)) {", "OutlinedButton(onClick = onAutoScan, modifier = Modifier.weight(1f)) {")

with open("app/src/main/java/com/example/ui/screens/VehicleGarageScreen.kt", "w") as f:
    f.write(content)
