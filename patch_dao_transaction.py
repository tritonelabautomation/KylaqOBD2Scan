with open("app/src/main/java/com/example/data/db/dao/CatalogDao.kt", "r") as f:
    dao = f.read()

new_transaction = """
    @Transaction
    suspend fun replaceCatalog(
        metadata: CatalogMetadataEntity,
        engines: List<CatalogEngineEntity>,
        transmissions: List<CatalogTransmissionEntity>,
        manufacturers: List<CatalogManufacturerEntity>,
        models: List<CatalogModelEntity>,
        generations: List<CatalogGenerationEntity>,
        variants: List<CatalogVariantEntity>
    ) {
        clearVariants()
        clearGenerations()
        clearModels()
        clearManufacturers()
        clearTransmissions()
        clearEngines()
        clearMetadata()

        insertMetadata(metadata)
        insertEngines(engines)
        insertTransmissions(transmissions)
        insertManufacturers(manufacturers)
        insertModels(models)
        insertGenerations(generations)
        insertVariants(variants)
    }

    @Transaction"""

dao = dao.replace("    @Transaction", new_transaction, 1)

with open("app/src/main/java/com/example/data/db/dao/CatalogDao.kt", "w") as f:
    f.write(dao)

with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "r") as f:
    loader = f.read()

old_insert = """                database.catalogDao().clearCatalog()
                database.catalogDao().insertMetadata(metadata)
                database.catalogDao().insertEngines(engines)
                database.catalogDao().insertTransmissions(transmissions)
                database.catalogDao().insertManufacturers(manufacturers)
                database.catalogDao().insertModels(models)
                database.catalogDao().insertGenerations(generations)
                database.catalogDao().insertVariants(variants)"""

new_insert = """                // Validate at least some data exists to prevent writing empty lists on corrupt JSON
                if (manufacturers.isEmpty() || models.isEmpty() || variants.isEmpty()) {
                    throw IllegalStateException("Catalog validation failed: Missing crucial entities")
                }
                
                // Atomic transaction
                database.catalogDao().replaceCatalog(
                    metadata = metadata,
                    engines = engines,
                    transmissions = transmissions,
                    manufacturers = manufacturers,
                    models = models,
                    generations = generations,
                    variants = variants
                )"""

loader = loader.replace(old_insert, new_insert)

with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "w") as f:
    f.write(loader)
