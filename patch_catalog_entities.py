with open("app/src/main/java/com/example/data/db/entities/CatalogEntities.kt", "r") as f:
    text = f.read()

import re

# We will add the provenance fields to all entity classes.
provenance_fields = """    val source: String? = null,
    val sourceUrl: String? = null,
    val sourceDate: String? = null,
    val market: String? = null,
    val confidence: String? = null,
    val verificationStatus: String? = null"""

# CatalogEngineEntity
text = re.sub(r'val torqueNm: Int\?\n\)', f'val torqueNm: Int?,\n{provenance_fields}\n)', text)

# CatalogTransmissionEntity
text = re.sub(r'val gearCount: Int\?\n\)', f'val gearCount: Int?,\n{provenance_fields}\n)', text)

# CatalogManufacturerEntity
text = re.sub(r'val name: String\n\)', f'val name: String,\n{provenance_fields}\n)', text)

# CatalogModelEntity
text = re.sub(r'val isCurrent: Boolean\n\)', f'val isCurrent: Boolean,\n{provenance_fields}\n)', text)

# CatalogGenerationEntity
text = re.sub(r'val endYear: Int\?\n\)', f'val endYear: Int?,\n{provenance_fields}\n)', text)

# CatalogVariantEntity
text = re.sub(r'val endYear: Int\? = null\n\)', f'val endYear: Int? = null,\n{provenance_fields}\n)', text)

with open("app/src/main/java/com/example/data/db/entities/CatalogEntities.kt", "w") as f:
    f.write(text)
