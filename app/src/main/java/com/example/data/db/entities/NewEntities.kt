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
    val catalogManufacturerId: String? = null,
    val catalogModelId: String? = null,
    val catalogGenerationId: String? = null,
    val catalogVariantId: String? = null,
    val catalogEngineId: String? = null,
    val catalogTransmissionId: String? = null,
    
    val catalogSource: String? = null,
    val catalogConfidence: String? = null,
    val catalogEvidence: String? = null,
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


@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val vehicleId: String?,
    val startedAt: Long,
    val completedAt: Long?,
    val connectionType: String,
    val adapterName: String,
    val adapterAddress: String,
    val protocol: String?,
    val ecuCount: Int = 0,
    val pidCount: Int = 0,
    val dtcCount: Int = 0,
    val readinessAvailable: Boolean = false,
    val completionStatus: String, // RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val rawEvidenceReference: String?
)

@Entity(tableName = "ecu_topologies")
data class EcuTopologyEntity(
    @PrimaryKey val id: String,
    val vehicleId: String?,
    val address: String, // e.g. "7E8", "18DA10F1"
    val name: String,
    val type: String, // ENGINE, TRANSMISSION, ABS, BCM
    val protocol: String?,
    val lastSeen: Long,
    val responseTime: Long,
    val supportedServices: String, // Comma separated
    val supportedPids: String, // Comma separated
    val dtcCount: Int,
    val confidence: String, // OBSERVED, INFERRED
    val rawEvidence: String?
)

@Entity(tableName = "pid_capabilities")
data class PidCapabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val vehicleId: String?,
    val ecuAddress: String,
    val pid: String, // e.g. "010C"
    val supported: Boolean,
    val lastVerified: Long,
    val responseLatency: Long,
    val failureCount: Int,
    val confidence: String // OBSERVED, INFERRED
)
