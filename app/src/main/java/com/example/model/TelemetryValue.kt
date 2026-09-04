package com.example.model

/**
 * Origin and trust classification for each telemetry parameter
 */
enum class ValueSource {
    STANDARD_OBD,           // SAE J1979 Mode 01 validated response
    CALCULATED,             // Numerically derived from verified signals (e.g. instant km/L)
    ESTIMATED,              // Heuristic estimation with clear confidence boundaries (e.g. Estimated Gear)
    MANUFACTURER_SPECIFIC,  // UDS / OEM diagnostic service (only if validated)
    RAW_OBSERVED,           // Raw hex captured without speculative formula
    GPS,                    // Android GNSS receiver
    UNKNOWN                 // Not available or not yet determined
}

/**
 * High-fidelity live telemetry value with trust source and staleness tracking
 */
data class LiveTelemetryValue(
    val parameterName: String,
    val numericValue: Double? = null,
    val displayValue: String = "—",
    val unit: String = "",
    val source: ValueSource = ValueSource.UNKNOWN,
    val timestampMonotonic: Long = 0L,
    val isValid: Boolean = false,
    val isStale: Boolean = false,
    val sourcePid: String? = null,
    val rawBytes: List<Int>? = null
)

/**
 * Central vehicle driving state classification
 */
enum class DrivingState(val displayName: String, val description: String) {
    STOPPED("STOPPED", "Vehicle stationary, engine off"),
    IDLE("IDLE", "Vehicle stationary, engine running"),
    ACCELERATING("ACCELERATING", "Speed increasing under positive load"),
    CRUISING("CRUISING", "Steady speed with sustained throttle"),
    COASTING("COASTING", "Vehicle moving, accelerator released, brake off"),
    BRAKING("BRAKING", "Vehicle moving, brake active with deceleration"),
    FUEL_CUT_DECELERATION("FUEL-CUT DECEL", "Decelerating with injector shutoff (0 fuel rate)"),
    BRAKE_AND_ACCELERATOR("BRAKE + ACCEL", "Simultaneous throttle and brake pedal application"),
    UNKNOWN("UNKNOWN", "Signals insufficient or transitioning")
}

/**
 * Transmission state for Škoda Kylaq 1.0 TSI (6-speed torque-converter automatic)
 */
data class TransmissionState(
    val selectedRange: String = "—",                  // P / R / N / D / S / M
    val actualGear: Int? = null,                       // Authoritative ECU gear (only if validated)
    val actualGearDisplay: String = "Not available",   // "Not available / Not detected" if unvalidated
    val estimatedGear: Int? = null,                    // Derived from RPM + Speed + Ratios
    val estimatedGearDisplay: String = "—",            // Clearly labeled as Estimated
    val targetGearDisplay: String = "Not available",
    val inputRpm: Double? = null,
    val outputRpm: Double? = null,
    val torqueConverterSlipRpm: Double? = null,
    val torqueConverterLockup: String = "Not available",
    val atfTemperatureC: Double? = null,
    val isEstimatedGearConfident: Boolean = false,
    val source: ValueSource = ValueSource.ESTIMATED
)

/**
 * Fuel economy metrics snapshot (real-time instantaneous)
 */
data class RealtimeEconomySnapshot(
    val instantKmL: Double? = null,
    val instantKmLDisplay: String = "—",
    val instantL100km: Double? = null,
    val instantL100kmDisplay: String = "—",
    val smoothedKmL: Double? = null,
    val smoothedKmLDisplay: String = "—",
    val smoothedL100km: Double? = null,
    val smoothedL100kmDisplay: String = "—",
    val idleConsumptionLh: Double? = null,
    val isIdle: Boolean = false,
    val source: ValueSource = ValueSource.CALCULATED
)

/**
 * Numerically integrated trip economy and consumption statistics
 */
data class TripEconomyStats(
    val tripDurationSec: Long = 0L,
    val movingDurationSec: Long = 0L,
    val idleDurationSec: Long = 0L,
    val distanceKm: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val averageMovingSpeedKmh: Double = 0.0,
    val totalFuelLiters: Double = 0.0,
    val averageKmL: Double = 0.0,
    val averageL100km: Double = 0.0,
    val idleFuelLiters: Double = 0.0,
    val coastingFuelLiters: Double = 0.0,
    val fuelCutDurationSec: Long = 0L,
    val sampleCount: Long = 0L,
    val isFuelIntegrated: Boolean = false
)

/**
 * Capability state for an OBD PID or diagnostic service
 * Strictly distinguishes between explicit unsupported status and transport/timeout issues.
 */
enum class CapabilityStatus {
    NOT_TESTED,
    BITMAP_SUPPORTED,
    DIRECT_VALIDATED,
    LIVE_ELIGIBLE,
    SUPPORTED,
    NOT_SUPPORTED,
    TIMEOUT,
    NO_DATA,
    MALFORMED_RESPONSE,
    CAN_ERROR,
    ADAPTER_ERROR,
    ERROR,
    TEMPORARILY_UNAVAILABLE
}
