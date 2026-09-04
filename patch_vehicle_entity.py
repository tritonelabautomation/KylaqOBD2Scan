with open("app/src/main/java/com/example/data/db/entities/NewEntities.kt", "r") as f:
    text = f.read()

import re
replacement = """    val defaultProtocol: String?,
    
    // Catalog fields
    val catalogManufacturerId: String? = null,
    val catalogModelId: String? = null,
    val catalogGenerationId: String? = null,
    val catalogVariantId: String? = null,
    val catalogEngineId: String? = null,
    val catalogTransmissionId: String? = null,
    
    val catalogSource: String? = null,"""

text = re.sub(r'val defaultProtocol: String\?,\s*// Catalog fields\s*val catalogVariantId: String\? = null,\s*val catalogSource: String\? = null,', replacement, text)

with open("app/src/main/java/com/example/data/db/entities/NewEntities.kt", "w") as f:
    f.write(text)
