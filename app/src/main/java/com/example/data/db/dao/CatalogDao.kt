package com.example.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: CatalogMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEngines(engines: List<CatalogEngineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransmissions(transmissions: List<CatalogTransmissionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManufacturers(manufacturers: List<CatalogManufacturerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<CatalogModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGenerations(generations: List<CatalogGenerationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariants(variants: List<CatalogVariantEntity>)

    @Query("SELECT * FROM catalog_metadata LIMIT 1")
    fun getMetadata(): Flow<CatalogMetadataEntity?>

    @Query("SELECT * FROM catalog_manufacturers ORDER BY name COLLATE NOCASE")
    fun getManufacturers(): Flow<List<CatalogManufacturerEntity>>

    @Query("SELECT * FROM catalog_models WHERE manufacturerId = :manufacturerId ORDER BY name COLLATE NOCASE")
    fun getModelsForManufacturer(manufacturerId: String): Flow<List<CatalogModelEntity>>

    @Query("SELECT * FROM catalog_generations WHERE modelId = :modelId ORDER BY name COLLATE NOCASE")
    fun getGenerationsForModel(modelId: String): Flow<List<CatalogGenerationEntity>>

    @Query("""
        SELECT v.* FROM catalog_variants v
        INNER JOIN catalog_generations g ON v.generationId = g.id
        WHERE v.generationId = :generationId 
        AND (:year BETWEEN ifnull(v.startYear, ifnull(g.startYear, 0)) AND ifnull(v.endYear, ifnull(g.endYear, 9999)))
        ORDER BY v.name COLLATE NOCASE
    """)
    fun getVariantsForGenerationAndYear(generationId: String, year: Int): Flow<List<CatalogVariantEntity>>

    @Query("SELECT * FROM catalog_engines WHERE id = :engineId")
    suspend fun getEngine(engineId: String): CatalogEngineEntity?

    @Query("SELECT * FROM catalog_transmissions WHERE id = :transmissionId")
    suspend fun getTransmission(transmissionId: String): CatalogTransmissionEntity?

    @Query("SELECT * FROM catalog_variants WHERE id = :variantId")
    suspend fun getVariant(variantId: String): CatalogVariantEntity?
    
    @Query("SELECT * FROM catalog_generations WHERE id = :generationId")
    suspend fun getGeneration(generationId: String): CatalogGenerationEntity?

    @Query("SELECT * FROM catalog_models WHERE id = :modelId")
    suspend fun getModel(modelId: String): CatalogModelEntity?

    @Query("SELECT * FROM catalog_manufacturers WHERE id = :manufacturerId")
    suspend fun getManufacturer(manufacturerId: String): CatalogManufacturerEntity?

    @Query("SELECT COUNT(*) FROM catalog_variants")
    suspend fun getVariantCount(): Int


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

    @Transaction
    suspend fun clearCatalog() {
        clearVariants()
        clearGenerations()
        clearModels()
        clearManufacturers()
        clearTransmissions()
        clearEngines()
        clearMetadata()
    }

    @Query("DELETE FROM catalog_variants")
    suspend fun clearVariants()
    @Query("DELETE FROM catalog_generations")
    suspend fun clearGenerations()
    @Query("DELETE FROM catalog_models")
    suspend fun clearModels()
    @Query("DELETE FROM catalog_manufacturers")
    suspend fun clearManufacturers()
    @Query("DELETE FROM catalog_transmissions")
    suspend fun clearTransmissions()
    @Query("DELETE FROM catalog_engines")
    suspend fun clearEngines()
    @Query("DELETE FROM catalog_metadata")
    suspend fun clearMetadata()

}
