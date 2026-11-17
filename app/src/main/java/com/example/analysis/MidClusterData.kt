package com.example.analysis

/**
 * Extracted & verified instrument cluster (MID / Virtual Cockpit) trip telemetry.
 *
 * Source: Captured from driver cluster display ("Since start" / "Since refuel" / "Long-term")
 * via AI Vision OCR or on-device pattern recognition.
 */
data class MidClusterData(
    val tripId: String,
    val timestampUtc: String = "",
    val durationMinutes: Int, // e.g. 83 for 1:23 h
    val durationText: String, // e.g. "1:23 h"
    val distanceKm: Double, // e.g. 31.0 km
    val avgFuelEconomyKmL: Double, // e.g. 9.1 km/L
    val avgSpeedKmh: Double, // e.g. 23.0 km/h
    val totalOdometerKm: Double? = null, // e.g. 4021.0 km
    val rangeKm: Double? = null, // e.g. 240.0 km
    val ambientTempC: Double? = null, // e.g. 29.5 °C
    val mode: String = "Since start", // "Since start", "Long-term", "Since refuel"
    val timeOfDay: String? = null, // e.g. "9:07"
    val photoUri: String? = null
) {
    /** Implied total fuel burn in litres calculated from MID reported distance and km/L. */
    val impliedFuelLiters: Double
        get() = if (avgFuelEconomyKmL > 0.1 && distanceKm > 0.05) distanceKm / avgFuelEconomyKmL else 0.0

    /** Converted L/100km economy for international reference. */
    val fuelEconomyL100Km: Double?
        get() = if (avgFuelEconomyKmL > 0.1) 100.0 / avgFuelEconomyKmL else null
}
