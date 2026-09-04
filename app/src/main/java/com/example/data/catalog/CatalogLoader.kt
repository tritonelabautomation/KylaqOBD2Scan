package com.example.data.catalog

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONObject
import java.io.InputStreamReader

class CatalogLoader(private val context: Context, private val database: AppDatabase) {

    suspend fun loadCatalogFromJsonIfEmpty() {
        withContext(Dispatchers.IO) {
            val count = database.catalogDao().getVariantCount()
            val metadataFlow = database.catalogDao().getMetadata()
            val metadata = metadataFlow.firstOrNull()

            val inputStream = context.assets.open("vehicle_catalog_india_v1.json")
            val jsonString = java.io.InputStreamReader(inputStream).readText()
            val json = JSONObject(jsonString)
            val assetVersion = json.getString("catalogVersion")
            
            if (count == 0 || metadata == null || metadata?.catalogVersion != assetVersion) {
                Log.d("CatalogLoader", "Catalog needs update, loading from JSON (Asset Version: $assetVersion)...")
                loadCatalog(json)
            } else {
                Log.d("CatalogLoader", "Catalog already populated and up to date (Version: $assetVersion, $count variants)")
            }
        }
    }

    suspend fun loadCatalog(json: JSONObject) {
        withContext(Dispatchers.IO) {
            try {

                val metadata = CatalogMetadataEntity(
                    schemaVersion = json.getInt("schemaVersion"),
                    catalogVersion = json.getString("catalogVersion"),
                    market = json.getString("market"),
                    country = json.getString("country"),
                    lastUpdated = System.currentTimeMillis()
                )

                val enginesJson = json.getJSONArray("engines")
                val engines = mutableListOf<CatalogEngineEntity>()
                for (i in 0 until enginesJson.length()) {
                    val e = enginesJson.getJSONObject(i)
                    engines.add(
                        CatalogEngineEntity(
                            id = e.getString("id"),
                            name = e.getString("name"),
                            code = if (e.isNull("code")) null else e.getString("code"),
                            displacementCc = if (e.isNull("displacementCc")) null else e.getInt("displacementCc"),
                            cylinders = if (e.isNull("cylinders")) null else e.getInt("cylinders"),
                            aspiration = if (e.isNull("aspiration")) null else e.getString("aspiration"),
                            fuelType = if (e.isNull("fuelType")) null else e.getString("fuelType"),
                            powerPs = if (e.isNull("powerPs")) null else e.getInt("powerPs"),
                            torqueNm = if (e.isNull("torqueNm")) null else e.getInt("torqueNm"),
                            source = if (e.isNull("source")) null else e.getString("source"),
                            sourceUrl = if (e.isNull("sourceUrl")) null else e.getString("sourceUrl"),
                            sourceDate = if (e.isNull("sourceDate")) null else e.getString("sourceDate"),
                            market = if (e.isNull("market")) null else e.getString("market"),
                            confidence = if (e.isNull("confidence")) null else e.getString("confidence"),
                            verificationStatus = if (e.isNull("verificationStatus")) null else e.getString("verificationStatus")
                        )
                    )
                }

                val transmissionsJson = json.getJSONArray("transmissions")
                val transmissions = mutableListOf<CatalogTransmissionEntity>()
                for (i in 0 until transmissionsJson.length()) {
                    val t = transmissionsJson.getJSONObject(i)
                    transmissions.add(
                        CatalogTransmissionEntity(
                            id = t.getString("id"),
                            name = t.getString("name"),
                            type = if (t.isNull("type")) null else t.getString("type"),
                            gearCount = if (t.isNull("gearCount")) null else t.getInt("gearCount"),
                            source = if (t.isNull("source")) null else t.getString("source"),
                            sourceUrl = if (t.isNull("sourceUrl")) null else t.getString("sourceUrl"),
                            sourceDate = if (t.isNull("sourceDate")) null else t.getString("sourceDate"),
                            market = if (t.isNull("market")) null else t.getString("market"),
                            confidence = if (t.isNull("confidence")) null else t.getString("confidence"),
                            verificationStatus = if (t.isNull("verificationStatus")) null else t.getString("verificationStatus")
                        )
                    )
                }

                val manufacturersJson = json.getJSONArray("manufacturers")
                val manufacturers = mutableListOf<CatalogManufacturerEntity>()
                val models = mutableListOf<CatalogModelEntity>()
                val generations = mutableListOf<CatalogGenerationEntity>()
                val variants = mutableListOf<CatalogVariantEntity>()

                for (i in 0 until manufacturersJson.length()) {
                    val m = manufacturersJson.getJSONObject(i)
                    val mId = m.getString("id")
                    manufacturers.add(CatalogManufacturerEntity(id = mId, name = m.getString("name"),
                        source = if (m.isNull("source")) null else m.getString("source"),
                        sourceUrl = if (m.isNull("sourceUrl")) null else m.getString("sourceUrl"),
                        sourceDate = if (m.isNull("sourceDate")) null else m.getString("sourceDate"),
                        market = if (m.isNull("market")) null else m.getString("market"),
                        confidence = if (m.isNull("confidence")) null else m.getString("confidence"),
                        verificationStatus = if (m.isNull("verificationStatus")) null else m.getString("verificationStatus")))

                    val modelsJson = m.getJSONArray("models")
                    for (j in 0 until modelsJson.length()) {
                        val mod = modelsJson.getJSONObject(j)
                        val modId = mod.getString("id")
                        models.add(
                            CatalogModelEntity(
                                id = modId,
                                manufacturerId = mId,
                                name = mod.getString("name"),
                                isCurrent = mod.getBoolean("isCurrent"),
                                source = if (mod.isNull("source")) null else mod.getString("source"),
                                sourceUrl = if (mod.isNull("sourceUrl")) null else mod.getString("sourceUrl"),
                                sourceDate = if (mod.isNull("sourceDate")) null else mod.getString("sourceDate"),
                                market = if (mod.isNull("market")) null else mod.getString("market"),
                                confidence = if (mod.isNull("confidence")) null else mod.getString("confidence"),
                                verificationStatus = if (mod.isNull("verificationStatus")) null else mod.getString("verificationStatus")
                            )
                        )

                        val gensJson = mod.getJSONArray("generations")
                        for (k in 0 until gensJson.length()) {
                            val gen = gensJson.getJSONObject(k)
                            val genId = gen.getString("id")
                            generations.add(
                                CatalogGenerationEntity(
                                    id = genId,
                                    modelId = modId,
                                    name = gen.getString("name"),
                                    startYear = if (gen.isNull("startYear")) null else gen.getInt("startYear"),
                                    endYear = if (gen.isNull("endYear")) null else gen.getInt("endYear"),
                                    source = if (gen.isNull("source")) null else gen.getString("source"),
                                    sourceUrl = if (gen.isNull("sourceUrl")) null else gen.getString("sourceUrl"),
                                    sourceDate = if (gen.isNull("sourceDate")) null else gen.getString("sourceDate"),
                                    market = if (gen.isNull("market")) null else gen.getString("market"),
                                    confidence = if (gen.isNull("confidence")) null else gen.getString("confidence"),
                                    verificationStatus = if (gen.isNull("verificationStatus")) null else gen.getString("verificationStatus")
                                )
                            )

                            val varsJson = gen.getJSONArray("variants")
                            for (vIdx in 0 until varsJson.length()) {
                                val v = varsJson.getJSONObject(vIdx)
                                variants.add(
                                    CatalogVariantEntity(
                                        id = v.getString("id"),
                                        generationId = genId,
                                        name = v.getString("name"),
                                        engineId = if (v.isNull("engineId")) null else v.getString("engineId"),
                                        transmissionId = if (v.isNull("transmissionId")) null else v.getString("transmissionId"),
                                        bodyType = if (v.isNull("bodyType")) null else v.getString("bodyType"),
                                        drivetrain = if (v.isNull("drivetrain")) null else v.getString("drivetrain"),
                                        startYear = if (v.isNull("startYear")) null else v.getInt("startYear"),
                                        endYear = if (v.isNull("endYear")) null else v.getInt("endYear"),
                                        source = if (v.isNull("source")) null else v.getString("source"),
                                        sourceUrl = if (v.isNull("sourceUrl")) null else v.getString("sourceUrl"),
                                        sourceDate = if (v.isNull("sourceDate")) null else v.getString("sourceDate"),
                                        market = if (v.isNull("market")) null else v.getString("market"),
                                        confidence = if (v.isNull("confidence")) null else v.getString("confidence"),
                                        verificationStatus = if (v.isNull("verificationStatus")) null else v.getString("verificationStatus")
                                    )
                                )
                            }
                        }
                    }
                }

                // Validate at least some data exists to prevent writing empty lists on corrupt JSON
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
                database.catalogDao().replaceCatalog(
                    metadata = metadata,
                    engines = engines,
                    transmissions = transmissions,
                    manufacturers = manufacturers,
                    models = models,
                    generations = generations,
                    variants = variants
                )
                
                Log.d("CatalogLoader", "Successfully loaded catalog JSON")

            } catch (e: Exception) {
                Log.e("CatalogLoader", "Failed to load catalog", e)
            }
        }
    }
}
