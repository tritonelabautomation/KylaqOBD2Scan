package com.example.model

import java.util.UUID

/**
 * Direction of communication
 */
enum class Direction {
    TX,
    RX,
    INFO,
    ERROR
}

/**
 * Status of OBD/ELM327 response
 */
enum class ResponseStatus {
    OK,
    NO_DATA,
    CAN_ERROR,
    UNABLE_TO_CONNECT,
    BUS_INIT_ERROR,
    TIMEOUT,
    MALFORMED,
    UNKNOWN,
    /**
     * FIX P0-8: The command was blocked by SafetyValidator before reaching the vehicle.
     * Semantically distinct from MALFORMED (which implies a bad response) and from
     * UNKNOWN (which implies an unrecognized status from the ELM327). Carries the
     * safety reason in the transaction record's errorMessage field.
     */
    BLOCKED,
    /**
     * The frame answered a *different* PID than the one requested — i.e. a late or
     * unsolicited response for an earlier request. Recorded for diagnostics but never
     * used for telemetry or capability promotion (see ObdScheduler).
     */
    IGNORED_LATE_FRAME
}

/**
 * Immutable transaction record representing an atomic OBD/CAN interaction
 */
data class TransactionRecord(
    val id: String = UUID.randomUUID().toString(),
    /**
     * When this frame happened, as an ISO-8601 stamp that STATES its zone.
     *
     * The name says "utc" and the value has not been UTC since 1.0.337: owner mandate
     * 2026-09-17, "For all records use IST time only no UTC." It is now
     * `2026-09-17T14:27:05.123+05:30`, written by [com.example.data.RecordTime].
     *
     * The key is NOT renamed because session JSON, both CSVs, the ZIP bundle, the Room
     * `telemetry_samples.timestampUtc` column and every backup the owner already holds use it -
     * renaming would orphan his whole trip history for a cosmetic gain. Files written before
     * 1.0.337 carry genuine `...Z` UTC and still parse to the same instant
     * ([com.example.data.RecordTime.parseMillis] honours an explicit offset, a `Z`, and a naive
     * stamp read as IST), so a history that spans the upgrade has no five-hour tear in it.
     */
    val timestampUtc: String,
    val timestampMonotonic: Long,
    val direction: Direction,
    val elmCommand: String = "",
    val canTxId: String = "",
    val canRxId: String = "",
    val requestHex: String = "",
    val responseHex: String = "",
    val service: String = "",
    val pid: String = "",
    val rawPayload: String = "",
    val decodedParameter: String = "",
    val decodedValue: Double? = null,
    val decodedValueDisplay: String = "",
    val unit: String = "",
    val decoderVersion: String = "1.0-ea211",
    val responseStatus: ResponseStatus = ResponseStatus.OK,
    val errorMessage: String? = null,
    // GPS altitude stamped at intake (owner 2026-09-16: "why altitude is missing in
    // trend and trip logs?"): the elevation of this OBD line's own moment, from
    // accuracy-gated fixes. Null when no fix exists - never 0.0, never invented.
    // SynchronizedSample.altitudeM (CSV wide rows) is the separate, older stamp.
    val altitudeM: Double? = null
)

/**
 * Synchronized live snapshot for dashboard and time-series export
 */
data class SynchronizedSample(
    /** IST stamp with its offset since 1.0.337 - see [TransactionRecord.timestampUtc]. */
    val timestampUtc: String,
    val timestampMonotonic: Long,
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val engineLoadPct: Double? = null,
    val mapKpa: Double? = null,
    val throttlePct: Double? = null,
    val acceleratorPct: Double? = null,
    val coolantC: Double? = null,
    val iatC: Double? = null,
    val ambientC: Double? = null,
    val fuelRateLh: Double? = null,
    val engineTorquePct: Double? = null,
    val voltageV: Double? = null,
    val fuelPressureRaw: String? = null,
    val boostPressureRaw: String? = null,
    /**
     * GPS altitude at this sample, from accuracy-gated fixes that reported altitude.
     * Null = no GPS altitude at that moment; it is never back-filled or interpolated
     * (no-fake-values rule). Added 2026-09-15 so the trip log carries the elevation
     * profile, not just the trip-summary min/max.
     */
    val altitudeM: Double? = null
)

/**
 * Statistical analysis of a single byte position across multiple frames (for reverse engineering)
 */
data class BytePositionStats(
    val byteIndex: Int,
    val minVal: Int,
    val maxVal: Int,
    val uniqueCount: Int,
    val changeCount: Int,
    val sampleCount: Int,
    val lastValue: Int,
    val commonHexValues: List<String>
)

/**
 * Metadata for a recording session
 */
data class RecordingMetadata(
    val sessionId: String,
    var sessionName: String,
    val vehicle: String = "Škoda Kylaq 1.0 TSI (EA211)",
    val vehicleId: String? = null,  // CRITICAL FIX: Added vehicleId for proper DTC/trip association
    val profile: String = "India-Market 1.0 TSI 6MT/6AT",
    val adapter: String = "ELM327 v1.5 Bluetooth Classic",
    val protocol: String = "ISO 15765-4 CAN 11-bit 500kbps",
    val canBitrate: String = "500 kbps",
    /** IST with its offset since 1.0.337 - see [TransactionRecord.timestampUtc] for why the name stays. */
    val startTimeUtc: String,
    var endTimeUtc: String? = null,
    val appVersion: String = "1.0-research",
    /**
     * GPS altitude window of the trip, filled in when recording stops (accuracy-gated fixes
     * only). Null = never captured, and the UI/JSON then say so instead of inventing 0 m.
     * Carried in the session JSON so backup -> reinstall -> import keeps the elevation data.
     */
    val maxAltitudeM: Double? = null,
    val minAltitudeM: Double? = null,
    /**
     * Battery voltage extremes of the trip, measured from the real 0142 samples when
     * recording stops (owner pipeline task 3, 2026-09-16: "Voltage min max recording").
     * Null = the trip had no voltage samples; extremes are recorded, never invented.
     * Carried in the session JSON so backup -> reinstall -> import keeps them.
     */
    val minVoltageV: Double? = null,
    val maxVoltageV: Double? = null
)
