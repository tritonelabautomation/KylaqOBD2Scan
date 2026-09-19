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
    val notes: String = "",
    /** GPS altitude extremes for this trip (accuracy-gated fixes only). Null = never captured (pre-v10 trips or no GPS fix) → UI shows an honest blank. Added 2026-09-15 (MIGRATION_9_10). */
    val maxAltitudeM: Double? = null,
    val minAltitudeM: Double? = null,
    /** Battery voltage extremes measured from stored 0142 samples at trip end. Null = never captured (pre-v11 trips or no voltage samples) -> UI derives from samples or stays blank. Added 2026-09-16 (MIGRATION_10_11, owner pipeline task 3). */
    val minVoltageV: Double? = null,
    val maxVoltageV: Double? = null
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
    val sequence: Long = 0L,
    // GPS altitude stamp per sample row (owner 2026-09-16). NULL for rows recorded
    // before MIGRATION_11_12 and for OBD-only recovered trips - honest blank.
    val altitudeM: Double? = null
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

/**
 * When this sample happened, as an epoch instant - the value a chart axis, a fuel integrator or a
 * trend comparison actually needs.
 *
 * The `timestamp` column cannot be trusted on its own. `RecordingManager` filled it from
 * `TransactionRecord.timestampMonotonic`, which the live transport and scheduler set to
 * `SystemClock.elapsedRealtime()` - milliseconds since boot, not a moment in time. Every trip
 * recorded before that was fixed therefore holds uptime in this column, and the rows are already on
 * the owner's phone: a migration would have to rewrite his whole history, so the repair happens on
 * read instead. `RecordTime.instantOf` rejects a value that cannot be an epoch instant and falls
 * back to the `timestampUtc` stamp beside it, which always stated the truth.
 *
 * Rows written since the fix store a real instant, so for them this returns `timestamp` unchanged.
 * The fallback to `timestamp` at the end is for a row whose stamp is also unreadable: an implausible
 * number still sorts correctly, which is better than dropping the sample and silently shortening a
 * curve.
 *
 * Owner mandate 2026-09-17: *"All logs, trends everything should be IST even the old logs should be
 * IST by default."* An axis drawn from uptime is not a time in any zone.
 */
val TelemetrySampleEntity.instantMs: Long
    get() = com.example.data.RecordTime.instantOf(timestamp, timestampUtc) ?: timestamp

/**
 * A detected refuel event (owner 2026-09-19: event-driven since-refuel tracking off PID 012F).
 * Written by [com.example.data.RecordingManager] when [com.example.analysis.RefuelEventDetector]
 * emits, either from a stationary window inside a session or from the level jump across a session
 * gap. `calibratedPumpL` arrives later, when a fuel-log entry whose odometer matches this event
 * supplies the pump's own litres - at which point the implied tank capacity is re-derived and the
 * next event's litre estimate improves. Estimate, never measurement, until that match happens.
 */
@Entity(tableName = "refuel_events")
data class RefuelEventEntity(
    @PrimaryKey
    val idMs: Long,
    val tsStartMs: Long,
    val tsEndMs: Long,
    val levelBeforePct: Double,
    val levelAfterPct: Double,
    val odoKm: Double?,
    val estLitres: Double,
    /** 0/1: the level rise happened across a session gap (engine off at the pump), not on screen. */
    val betweenSessions: Int,
    val calibratedPumpL: Double? = null,
    val capacityL: Double
)
