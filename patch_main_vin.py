with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    main_content = f.read()

import re
old_save = """                                id = java.util.UUID.randomUUID().toString(),
                                make = make,
                                model = model,
                                year = year,
                                catalogVariantId = variantId,
                                defaultProtocol = null,
                                vin = null,
                                nickname = null
                            )"""

new_save = """                                id = java.util.UUID.randomUUID().toString(),
                                make = make,
                                model = model,
                                year = year,
                                catalogVariantId = variantId,
                                catalogSource = "MANUAL",
                                catalogConfidence = "HIGH",
                                defaultProtocol = null,
                                vin = viewModel.vehicleVin.value.takeIf { !it.isNullOrBlank() && !it.contains("Unavailable", ignoreCase = true) && !it.contains("Failed", ignoreCase = true) },
                                nickname = null
                            )"""

main_content = main_content.replace(old_save, new_save)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(main_content)
