with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "r") as f:
    loader = f.read()

validation_code = """                // Validate at least some data exists to prevent writing empty lists on corrupt JSON
                if (manufacturers.isEmpty() || models.isEmpty() || variants.isEmpty()) {
                    throw IllegalStateException("Catalog validation failed: Missing crucial entities")
                }
                
                // Validate required names
                if (manufacturers.any { it.name.isBlank() } || models.any { it.name.isBlank() } || variants.any { it.name.isBlank() }) {
                    throw IllegalStateException("Catalog validation failed: Missing required names")
                }
                
                // Validate duplicate IDs
                if (manufacturers.distinctBy { it.id }.size != manufacturers.size ||
                    models.distinctBy { it.id }.size != models.size ||
                    variants.distinctBy { it.id }.size != variants.size
                ) {
                    throw IllegalStateException("Catalog validation failed: Duplicate IDs found")
                }
                
                // Validate Foreign Keys
                val manufacturerIds = manufacturers.map { it.id }.toSet()
                val modelIds = models.map { it.id }.toSet()
                val generationIds = generations.map { it.id }.toSet()
                val engineIds = engines.map { it.id }.toSet()
                val transmissionIds = transmissions.map { it.id }.toSet()
                
                if (models.any { !manufacturerIds.contains(it.manufacturerId) }) {
                    throw IllegalStateException("Catalog validation failed: Orphaned model")
                }
                if (generations.any { !modelIds.contains(it.modelId) }) {
                    throw IllegalStateException("Catalog validation failed: Orphaned generation")
                }
                if (variants.any { !generationIds.contains(it.generationId) }) {
                    throw IllegalStateException("Catalog validation failed: Orphaned variant")
                }
                if (variants.any { it.engineId != null && !engineIds.contains(it.engineId) }) {
                    throw IllegalStateException("Catalog validation failed: Invalid engine reference")
                }
                if (variants.any { it.transmissionId != null && !transmissionIds.contains(it.transmissionId) }) {
                    throw IllegalStateException("Catalog validation failed: Invalid transmission reference")
                }
                
                // Atomic transaction
"""

loader = loader.replace("""                // Validate at least some data exists to prevent writing empty lists on corrupt JSON
                if (manufacturers.isEmpty() || models.isEmpty() || variants.isEmpty()) {
                    throw IllegalStateException("Catalog validation failed: Missing crucial entities")
                }
                
                // Atomic transaction
""", validation_code)

with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "w") as f:
    f.write(loader)
