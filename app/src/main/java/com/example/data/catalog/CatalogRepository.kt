package com.example.data.catalog

import com.example.data.db.AppDatabase
import com.example.data.db.entities.*
import kotlinx.coroutines.flow.Flow

class CatalogRepository constructor(private val database: AppDatabase) {
    val dao = database.catalogDao()

    fun getManufacturers(): Flow<List<CatalogManufacturerEntity>> = dao.getManufacturers()
    
    fun getModels(manufacturerId: String): Flow<List<CatalogModelEntity>> = dao.getModelsForManufacturer(manufacturerId)
    
    fun getGenerations(modelId: String): Flow<List<CatalogGenerationEntity>> = dao.getGenerationsForModel(modelId)
    
    fun getVariants(generationId: String): Flow<List<CatalogVariantEntity>> = dao.getVariantsForGeneration(generationId)

    suspend fun getVariantDetails(variantId: String): CatalogVariantDetails? {
        val variant = dao.getVariant(variantId) ?: return null
        val generation = dao.getGeneration(variant.generationId) ?: return null
        val model = dao.getModel(generation.modelId) ?: return null
        val manufacturer = dao.getManufacturer(model.manufacturerId) ?: return null
        val engine = variant.engineId?.let { dao.getEngine(it) }
        val transmission = variant.transmissionId?.let { dao.getTransmission(it) }

        return CatalogVariantDetails(
            variant = variant,
            generation = generation,
            model = model,
            manufacturer = manufacturer,
            engine = engine,
            transmission = transmission
        )
    }
}

data class CatalogVariantDetails(
    val variant: CatalogVariantEntity,
    val generation: CatalogGenerationEntity,
    val model: CatalogModelEntity,
    val manufacturer: CatalogManufacturerEntity,
    val engine: CatalogEngineEntity?,
    val transmission: CatalogTransmissionEntity?
)
