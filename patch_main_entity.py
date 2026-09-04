with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    main_content = f.read()

old_entity = """                                com.example.data.db.entities.VehicleEntity(
                                    id = java.util.UUID.randomUUID().toString(),
                                    make = make,
                                    model = model,
                                    year = year,
                                    vin = null,
                                    defaultProtocol = null,
                                    catalogVariantId = variantId
                                )"""

new_entity = """                                com.example.data.db.entities.VehicleEntity(
                                    id = java.util.UUID.randomUUID().toString(),
                                    make = make,
                                    model = model,
                                    year = year,
                                    catalogVariantId = variantId,
                                    catalogSource = "MANUAL",
                                    catalogConfidence = "HIGH",
                                    defaultProtocol = null,
                                    vin = viewModel.vehicleVin.value.takeIf { !it.isNullOrBlank() && !it.contains("Unavailable", ignoreCase = true) && !it.contains("Failed", ignoreCase = true) }
                                )"""

main_content = main_content.replace(old_entity, new_entity)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(main_content)
