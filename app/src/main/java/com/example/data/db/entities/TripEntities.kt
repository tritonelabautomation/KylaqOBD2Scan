package com.example.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Trip Entity representing a persistent vehicle logging session
 */
@Entity(
    tableName = "trips",
    indices = [
        Index(value = ["startTimeUtc"]),
        Index(value = ["status"])
    ]
)
data class TripEntity(
    @PrimaryKey
    val id: String, // e.g. "trip_a1b2c3d4"
    val title: String,
    val vehicleName: String = "Škoda Kylaq 1.0 TSI (EA211)",
    val adapterName: String = "ELM327 Bluetooth",
    val protocolName: String = "ISO 15765-4 (CAN 11/500K)",
    val startTimeUtc: String,
    val endTimeUtc: String? = null,
    val startTimestamp: Long = System.currentTimeMillis(),
    val endTimestamp: Long? = null,
    val durationSeconds: Long = 0L,
    val status: String = "COMPLETED", // "RECORDING", "COMPLETED", "INTERRUPTED"
    val sampleCount: Int = 0,
    val rawLogCount: Int = 0,
    val eventCount: Int = 0,
    val maxRpm: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val maxCoolantC: Double = 0.0,
    val avgVoltageV: Double = 0.0,
    val detectedEcus: String = "7E8", // Comma-separated CAN IDs e.g. "7E8, 7E9"
    val healthScore: Int = 100, // 0-100 score
    val notes: String = ""
)

/**
 * High-performance granular telemetry sample
 */
@Entity(
    tableName = "telemetry_samples",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["pid"]),
        Index(value = ["ecuCanId"]),
        Index(value = ["timestamp"])
    ]
)
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val tripId: String,
    val timestamp: Long,
    val timestampUtc: String,
    val ecuCanId: String, // "7E8", "7E9"
    val pid: String, // "010C", "010D", etc.
    val parameterName: String,
    val rawHex: String,
    val numericValue: Double?,
    val displayValue: String,
    val unit: String,
    val quality: String = "VALID", // "VALID", "STALE", "OUT_OF_RANGE", "INVALID"
    val sequence: Long = 0L
)

/**
 * Structured Raw communication log for audit & CAN inspection
 */
@Entity(
    tableName = "raw_logs",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["category"]),
        Index(value = ["timestamp"])
    ]
)
data class RawLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val tripId: String,
    val timestamp: Long,
    val timestampUtc: String,
    val direction: String, // "TX", "RX", "INFO", "ERROR"
    val category: String, // "ELM", "OBD", "CAN_7E8", "CAN_7E9", "ISO_TP", "PID", "ERROR"
    val command: String,
    val rawLine: String,
    val canId: String? = null,
    val parsedPayload: String? = null,
    val status: String = "OK"
)

/**
 * Diagnostic Event or Anomaly
 */
@Entity(
    tableName = "diagnostic_events",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["severity"]),
        Index(value = ["timestamp"])
    ]
)
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val tripId: String,
    val timestamp: Long,
    val timestampUtc: String,
    val severity: String, // "INFO", "WARNING", "ANOMALY", "FAULT"
    val category: String, // "TEMPERATURE", "VOLTAGE", "BUS", "TIMEOUT", "RPM"
    val message: String,
    val pid: String? = null,
    val value: String? = null
)

/**
 * AI "Car Doctor" Diagnostic Review
 */
@Entity(
    tableName = "ai_analyses",
    indices = [
        Index(value = ["tripId"], unique = true),
        Index(value = ["timestamp"])
    ]
)
data class AiAnalysisEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val tripId: String,
    val timestamp: Long,
    val timestampUtc: String,
    val provider: String, // "RULE_BASED", "GEMINI_CLOUD", "OFFLINE_LOCAL"
    val model: String,
    val overallHealth: String, // "NORMAL", "MONITOR", "ATTENTION", "CRITICAL"
    val healthScore: Int, // 0-100
    val drivingSummary: String,
    val engineBehavior: String,
    val temperatureBehavior: String,
    val voltageBehavior: String,
    val throttleLoadBehavior: String,
    val potentialAnomalies: String, // JSON or formatted bullet points
    val recommendedChecks: String,
    val confidence: String = "HIGH", // "LOW", "MEDIUM", "HIGH"
    val privacyMode: String = "LOCAL_ONLY"
)
