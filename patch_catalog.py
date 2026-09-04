import re

with open("app/src/main/java/com/example/data/db/dao/CatalogDao.kt", "r") as f:
    dao = f.read()
dao = dao.replace(
    '@Query("SELECT * FROM catalog_variants WHERE generationId = :generationId ORDER BY name COLLATE NOCASE")\n    fun getVariantsForGeneration(generationId: String): Flow<List<CatalogVariantEntity>>',
    '''@Query("""
        SELECT v.* FROM catalog_variants v
        INNER JOIN catalog_generations g ON v.generationId = g.id
        WHERE v.generationId = :generationId 
        AND (:year BETWEEN ifnull(v.startYear, ifnull(g.startYear, 0)) AND ifnull(v.endYear, ifnull(g.endYear, 9999)))
        ORDER BY v.name COLLATE NOCASE
    """)
    fun getVariantsForGenerationAndYear(generationId: String, year: Int): Flow<List<CatalogVariantEntity>>'''
)
with open("app/src/main/java/com/example/data/db/dao/CatalogDao.kt", "w") as f:
    f.write(dao)

with open("app/src/main/java/com/example/data/catalog/CatalogRepository.kt", "r") as f:
    repo = f.read()
repo = repo.replace(
    'fun getVariants(generationId: String): Flow<List<CatalogVariantEntity>> = dao.getVariantsForGeneration(generationId)',
    'fun getVariants(generationId: String, year: Int): Flow<List<CatalogVariantEntity>> = dao.getVariantsForGenerationAndYear(generationId, year)'
)
with open("app/src/main/java/com/example/data/catalog/CatalogRepository.kt", "w") as f:
    f.write(repo)
