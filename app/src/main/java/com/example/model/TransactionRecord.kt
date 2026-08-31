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
    UNKNOWN
}

/**
 * Immutable transaction record representing an atomic OBD/CAN interaction
 */
data class TransactionRecord(
    val id: String = UUID.randomUUID().toString(),
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
    val errorMessage: String? = null
)

/**
 * Synchronized live snapshot for dashboard and time-series export
 */
data class SynchronizedSample(
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
    val boostPressureRaw: String? = null
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
    val profile: String = "India-Market 1.0 TSI 6MT/6AT",
    val adapter: String = "ELM327 v1.5 Bluetooth Classic",
    val protocol: String = "ISO 15765-4 CAN 11-bit 500kbps",
    val canBitrate: String = "500 kbps",
    val startTimeUtc: String,
    var endTimeUtc: String? = null,
    val appVersion: String = "1.0-research"
)
