package com.example.data.catalog

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStreamReader

class CatalogLoader(private val context: Context, private val database: AppDatabase) {

    suspend fun loadCatalogFromJsonIfEmpty() {
        withContext(Dispatchers.IO) {
            val count = database.catalogDao().getVariantCount()
            if (count == 0) {
                Log.d("CatalogLoader", "Catalog empty, loading from JSON...")
                loadCatalog()
            } else {
                Log.d("CatalogLoader", "Catalog already populated ($count variants)")
            }
        }
    }

    suspend fun loadCatalog() {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("vehicle_catalog_india_v1.json")
                val jsonString = InputStreamReader(inputStream).readText()
                val json = JSONObject(jsonString)

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
                            torqueNm = if (e.isNull("torqueNm")) null else e.getInt("torqueNm")
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
                            gearCount = if (t.isNull("gearCount")) null else t.getInt("gearCount")
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
                    manufacturers.add(CatalogManufacturerEntity(id = mId, name = m.getString("name")))

                    val modelsJson = m.getJSONArray("models")
                    for (j in 0 until modelsJson.length()) {
                        val mod = modelsJson.getJSONObject(j)
                        val modId = mod.getString("id")
                        models.add(
                            CatalogModelEntity(
                                id = modId,
                                manufacturerId = mId,
                                name = mod.getString("name"),
                                isCurrent = mod.getBoolean("isCurrent")
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
                                    endYear = if (gen.isNull("endYear")) null else gen.getInt("endYear")
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
                                        drivetrain = if (v.isNull("drivetrain")) null else v.getString("drivetrain")
                                    )
                                )
                            }
                        }
                    }
                }

                database.catalogDao().clearCatalog()
                database.catalogDao().insertMetadata(metadata)
                database.catalogDao().insertEngines(engines)
                database.catalogDao().insertTransmissions(transmissions)
                database.catalogDao().insertManufacturers(manufacturers)
                database.catalogDao().insertModels(models)
                database.catalogDao().insertGenerations(generations)
                database.catalogDao().insertVariants(variants)
                
                Log.d("CatalogLoader", "Successfully loaded catalog JSON")

            } catch (e: Exception) {
                Log.e("CatalogLoader", "Failed to load catalog", e)
            }
        }
    }
}
