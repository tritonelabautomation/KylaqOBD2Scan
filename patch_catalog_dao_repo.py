import re

with open("app/src/main/java/com/example/data/db/dao/CatalogDao.kt", "r") as f:
    text = f.read()

dao_additions = """
    @Query("SELECT * FROM catalog_variants")
    suspend fun getAllVariants(): List<CatalogVariantEntity>

    @Query("SELECT * FROM catalog_generations")
    suspend fun getAllGenerations(): List<CatalogGenerationEntity>

    @Query("SELECT * FROM catalog_models")
    suspend fun getAllModels(): List<CatalogModelEntity>

    @Query("SELECT * FROM catalog_manufacturers")
    suspend fun getAllManufacturers(): List<CatalogManufacturerEntity>
"""

text = text.replace("@Query(\"SELECT COUNT(*) FROM catalog_variants\")", dao_additions + "\n    @Query(\"SELECT COUNT(*) FROM catalog_variants\")")

with open("app/src/main/java/com/example/data/db/dao/CatalogDao.kt", "w") as f:
    f.write(text)

with open("app/src/main/java/com/example/data/catalog/CatalogRepository.kt", "r") as f:
    repo = f.read()

repo_additions = """
    suspend fun getAllVariants(): List<CatalogVariantEntity> = dao.getAllVariants()
    suspend fun getAllGenerations(): List<CatalogGenerationEntity> = dao.getAllGenerations()
    suspend fun getAllModels(): List<CatalogModelEntity> = dao.getAllModels()
    suspend fun getAllManufacturers(): List<CatalogManufacturerEntity> = dao.getAllManufacturers()
"""

repo = repo.replace("suspend fun getVariantDetails", repo_additions + "\n    suspend fun getVariantDetails")

with open("app/src/main/java/com/example/data/catalog/CatalogRepository.kt", "w") as f:
    f.write(repo)

