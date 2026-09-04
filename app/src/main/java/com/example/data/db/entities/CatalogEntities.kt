package com.example.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "catalog_metadata")
data class CatalogMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val schemaVersion: Int,
    val catalogVersion: String,
    val market: String,
    val country: String,
    val lastUpdated: Long
)

@Entity(tableName = "catalog_engines")
data class CatalogEngineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String?,
    val displacementCc: Int?,
    val cylinders: Int?,
    val aspiration: String?,
    val fuelType: String?,
    val powerPs: Int?,
    val torqueNm: Int?
)

@Entity(tableName = "catalog_transmissions")
data class CatalogTransmissionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String?,
    val gearCount: Int?
)

@Entity(tableName = "catalog_manufacturers")
data class CatalogManufacturerEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "catalog_models",
    foreignKeys = [
        ForeignKey(entity = CatalogManufacturerEntity::class, parentColumns = ["id"], childColumns = ["manufacturerId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("manufacturerId")]
)
data class CatalogModelEntity(
    @PrimaryKey val id: String,
    val manufacturerId: String,
    val name: String,
    val isCurrent: Boolean
)

@Entity(
    tableName = "catalog_generations",
    foreignKeys = [
        ForeignKey(entity = CatalogModelEntity::class, parentColumns = ["id"], childColumns = ["modelId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("modelId")]
)
data class CatalogGenerationEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val name: String,
    val startYear: Int?,
    val endYear: Int?
)

@Entity(
    tableName = "catalog_variants",
    foreignKeys = [
        ForeignKey(entity = CatalogGenerationEntity::class, parentColumns = ["id"], childColumns = ["generationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CatalogEngineEntity::class, parentColumns = ["id"], childColumns = ["engineId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CatalogTransmissionEntity::class, parentColumns = ["id"], childColumns = ["transmissionId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("generationId"), Index("engineId"), Index("transmissionId")]
)
data class CatalogVariantEntity(
    @PrimaryKey val id: String,
    val generationId: String,
    val name: String,
    val engineId: String?,
    val transmissionId: String?,
    val bodyType: String?,
    val drivetrain: String?
)
