with open("app/src/main/java/com/example/ui/screens/VehicleProfileScreen.kt", "r") as f:
    content = f.read()

import_str = "import com.example.data.catalog.CatalogRepository\nimport com.example.data.catalog.CatalogVariantDetails\n"
content = content.replace("import com.example.data.db.entities.VehicleEntity", import_str + "import com.example.data.db.entities.VehicleEntity")

func_sig = """fun VehicleProfileScreen(
    vehicle: VehicleEntity?,
    catalogRepository: CatalogRepository,
    onBack: () -> Unit
) {"""
content = content.replace("fun VehicleProfileScreen(\n    vehicle: VehicleEntity?,\n    onBack: () -> Unit\n) {", func_sig)

state_add = """
        var variantDetails by remember { mutableStateOf<CatalogVariantDetails?>(null) }
        
        LaunchedEffect(vehicle?.catalogVariantId) {
            vehicle?.catalogVariantId?.let {
                variantDetails = catalogRepository.getVariantDetails(it)
            }
        }
"""
content = content.replace("    ) { padding ->\n        if (vehicle == null) {", "    ) { padding ->" + state_add + "\n        if (vehicle == null) {")

details_replace = """
                    val engineName = variantDetails?.engine?.let { "${it.name} (${it.displacementCc ?: "?"} cc)" } ?: "Unknown"
                    val transName = variantDetails?.transmission?.let { "${it.name} (${it.type ?: "?"})" } ?: "Unknown"
                    val fuelName = variantDetails?.engine?.fuelType ?: "Unknown"

                    ProfileDetailRow("Engine", engineName)
                    ProfileDetailRow("Transmission", transName)
                    ProfileDetailRow("Fuel Type", fuelName)
"""
content = content.replace("""                    ProfileDetailRow("Engine", "Loading...") // Todo: fetch from catalog if ID exists
                    ProfileDetailRow("Transmission", "Loading...")
                    ProfileDetailRow("Fuel Type", "Loading...")""", details_replace)

with open("app/src/main/java/com/example/ui/screens/VehicleProfileScreen.kt", "w") as f:
    f.write(content)
