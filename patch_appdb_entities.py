import re

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "r") as f:
    text = f.read()

replacement = """        CatalogVariantEntity::class,
        ScanSessionEntity::class,
        EcuTopologyEntity::class,
        PidCapabilityEntity::class
    ],"""

text = text.replace("        CatalogVariantEntity::class\n    ],", replacement)

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "w") as f:
    f.write(text)
