package com.example.data

/**
 * Aggregates reduced from a transactions CSV in a single streaming pass.
 * Recovery / STOP use this instead of holding every [com.example.model.TransactionRecord].
 */
data class CsvTripStats(
    val count: Int = 0,
    val minEpochMs: Long? = null,
    val maxEpochMs: Long? = null,
    val maxRpm: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val maxCoolantC: Double = 0.0,
    val voltSum: Double = 0.0,
    val voltCount: Int = 0,
    val minVoltageV: Double? = null,
    val maxVoltageV: Double? = null,
    val minAltitudeM: Double? = null,
    val maxAltitudeM: Double? = null,
    val startFuelPercent: Double? = null,
    val endFuelPercent: Double? = null,
    val detectedEcus: String = "7E8"
) {
    val avgVoltageV: Double get() = if (voltCount > 0) voltSum / voltCount else 0.0
    val fuelDeltaPercent: Double?
        get() = if (startFuelPercent != null && endFuelPercent != null) {
            endFuelPercent - startFuelPercent
        } else null
}
