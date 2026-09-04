with open("app/src/main/java/com/example/ui/screens/VehicleProfileScreen.kt", "r") as f:
    text = f.read()

sig_old = """fun VehicleProfileScreen(
    vehicle: VehicleEntity?,
    catalogRepository: CatalogRepository,
    onBack: () -> Unit
) {"""
sig_new = """fun VehicleProfileScreen(
    vehicle: VehicleEntity?,
    catalogRepository: CatalogRepository,
    onBack: () -> Unit,
    onNavigateToDtc: () -> Unit
) {"""
text = text.replace(sig_old, sig_new)

health_old = """                    ProfileDetailRow("Overall Health", "Unknown (Scan Required)")
                    ProfileDetailRow("Last Scan", "Never")
                    ProfileDetailRow("Active DTCs", "Unknown")
                }
            }"""
health_new = """                    ProfileDetailRow("Overall Health", "Unknown (Scan Required)")
                    ProfileDetailRow("Last Scan", "Never")
                    ProfileDetailRow("Active DTCs", "Unknown")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToDtc,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                    ) {
                        Icon(Icons.Default.HealthAndSafety, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Diagnostic Scanner")
                    }
                }
            }"""
text = text.replace(health_old, health_new)

with open("app/src/main/java/com/example/ui/screens/VehicleProfileScreen.kt", "w") as f:
    f.write(text)
