package com.example.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey
    val id: String,
    val make: String,
    val model: String,
    val year: String,
    val vin: String?,
    val defaultProtocol: String?,
    
    // Catalog fields
    val catalogVariantId: String? = null,
    val nickname: String? = null,
    val licensePlate: String? = null,
    val odometerKm: Int? = null,
    val isLegacy: Boolean = false,
    val notes: String? = null
)

@Entity(tableName = "protocol_test_results")
data class ProtocolTestResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val protocol: String,
    val atCommand: String,
    val resultStatus: String,
    val ecuResponses: Int,
    val canErrors: Int,
    val timeouts: Int,
    val averageLatency: Long,
    val appVersion: String,
    val buildNumber: Int,
    val gitCommit: String
)

@Entity(tableName = "dtc_records")
data class DtcRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val vehicleId: String?,
    val tripId: String?,
    val timestamp: Long,
    val code: String,
    val description: String,
    val status: String // PENDING, CONFIRMED
)

@Entity(tableName = "service_records")
data class ServiceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val vehicleId: String,
    val timestamp: Long,
    val dateString: String,
    val title: String,
    val description: String,
    val mileage: Int?
)
