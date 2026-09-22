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

        /**
         * Reduces persisted per-row altitudes (journal/CSV sample rows) into a stats window.
         *
         * KILL-AUDIT FIX B (2026-09-19): a recovered trip's LIVE accumulator is empty RAM - the
         * persisted rows are the only altitude the drive has left. Same [record] plausibility
         * gate as the live path; null when no plausible point exists, so the trip shows an honest
         * "-- m" rather than an invented window.
         */
        fun reduce(points: List<Double>): AltitudeStats? {
            if (points.isEmpty()) return null
            val s = AltitudeStats()
            points.forEach { s.record(it) }
            return if (s.sampleCount > 0) s else null
        }

        /**
         * Widens two windows into one: the lower minimum and the higher maximum, sample counts
         * added. Null-safe on both sides, so "the live accumulator and the persisted rows" can be
         * combined without either one having to exist.
         *
         * Added 2026-09-22 (owner: *"Altitude not logging still"*). Two separate defects needed
         * it. A process that restarts MID-drive resets this trip's live accumulator - the RAM
         * window then covers only the leg recorded after the restart, while the persisted rows
         * still hold the whole drive; picking either one alone reported a short window. And a
         * RECOVERED or MERGED trip used to take the live accumulator whenever it happened to be
         * non-empty, which is the window of whatever drive is running NOW - one trip's elevation
         * stamped onto a different trip. Callers now decide whether the live accumulator belongs
         * to the trip being written, and [combine] merges it with the rows when it does.
         */
        fun combine(a: AltitudeStats?, b: AltitudeStats?): AltitudeStats? {
            if (a == null) return b
            if (b == null) return a
            val min = listOfNotNull(a.minAltitudeM, b.minAltitudeM).minOrNull()
            val max = listOfNotNull(a.maxAltitudeM, b.maxAltitudeM).maxOrNull()
            if (min == null || max == null) return null
            return AltitudeStats().apply { setWindow(min, max, a.sampleCount + b.sampleCount) }
        }
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

    /**
     * Adopts an already-gated window wholesale. Only [Companion.combine] uses it: merging two
     * windows that each passed [record]'s plausibility gate must not re-count their fixes as two,
     * and must not re-gate values that were accepted when they were measured.
     */
    internal fun setWindow(min: Double, max: Double, count: Int) {
        minAltitudeM = min
        maxAltitudeM = max
        sampleCount = count
    }
}
