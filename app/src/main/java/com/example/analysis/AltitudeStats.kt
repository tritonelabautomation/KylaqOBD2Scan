package com.example.analysis

/**
 * Trip altitude-range accumulator (owner 2026-09-15: "Why altitude is not taken
 * from GPS why it's empty in trip summary?").
 *
 * The GPS fix already carries altitude (GpsManager publishes it live), but the
 * recorder never aggregated or persisted it per trip — so the trip summary card
 * showed an honest "-- m" blank. This class closes the gap: GpsManager feeds it
 * every fix that passes the horizontal-accuracy gate AND reports altitude
 * (Location.hasAltitude()); RecordingManager persists min/max at stop.
 *
 * Plausibility filter: values outside -500..9500 m (Dead Sea shore .. above
 * Everest) are rejected as sensor glitches rather than poisoning the trip range.
 *
 * Pure JVM - unit-tested in AltitudeStatsTest.
 */
class AltitudeStats {

    companion object {
        const val MIN_PLAUSIBLE_M = -500.0
        const val MAX_PLAUSIBLE_M = 9500.0
    }

    var minAltitudeM: Double? = null
        private set
    var maxAltitudeM: Double? = null
        private set
    var sampleCount: Int = 0
        private set

    /** Records one accepted GPS altitude (metres, WGS-84 ellipsoidal ≈ MSL at our scale). */
    fun record(altitudeM: Double) {
        if (altitudeM < MIN_PLAUSIBLE_M || altitudeM > MAX_PLAUSIBLE_M) return
        sampleCount++
        val mn = minAltitudeM
        if (mn == null || altitudeM < mn) minAltitudeM = altitudeM
        val mx = maxAltitudeM
        if (mx == null || altitudeM > mx) maxAltitudeM = altitudeM
    }

    /** Max − min climb window for the trip, null until at least one fix was recorded. */
    val rangeM: Double?
        get() {
            val a = minAltitudeM ?: return null
            val b = maxAltitudeM ?: return null
            return b - a
        }

    fun reset() {
        minAltitudeM = null
        maxAltitudeM = null
        sampleCount = 0
    }
}
