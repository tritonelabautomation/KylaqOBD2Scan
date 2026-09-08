package com.example.analysis

/**
 * Cross-trip trend aggregates for the Trips tab ("Trips & Recordings").
 *
 * The owner asked for rpm / speed / torque / load trends across trips plus an
 * idle "engine model vs actual" comparison. Every recorded trip stores its raw
 * telemetry samples in Room; this analyzer reduces them to one point per trip:
 * averages of rpm (010C), speed (010D), load (0104), torque percent (0162,
 * converted to Nm against the 178 Nm reference) and an idle window (speed < 1 km/h
 * with rpm 400-1300) whose measured fuel burn is compared against the math model's
 * idle rate (PowertrainModel: 0.8 L/h).
 *
 * Pure JVM, no Android deps - unit-tested in TripTrendAnalyzerTest.
 */
data class TripTrendPoint(
    val tripId: String,
    val startTs: Long,
    val avgRpm: Double?,
    val avgSpeedKmh: Double?,
    val avgLoadPct: Double?,
    val avgTorqueNm: Double?,
    val idleSec: Double,
    val idleActualL: Double,
    val idleModelL: Double,
    val idleActualLh: Double?
) {
    /** Measured idle burn vs the model's 0.8 L/h, % excess. */
    val idleExcessPct: Double?
        get() = if (idleModelL > 0.0005) (idleActualL / idleModelL - 1.0) * 100.0 else null
}

object TripTrendAnalyzer {

    data class Sample(val tripId: String, val pid: String, val ts: Long, val value: Double?)

    const val PID_RPM = "010C"
    const val PID_SPEED = "010D"
    const val PID_LOAD = "0104"
    const val PID_TORQUE_PCT = "0162"
    const val PID_FUEL_VOL = "015E"
    const val PID_FUEL_MASS = "019D"

    /** Matches PowertrainModel.IDLE_FUEL_LH - kept local so the analyzer stays dependency-free. */
    const val MODEL_IDLE_LH = 0.8

    /** 178 Nm reference: J1979 torque percent -> Nm. */
    const val NM_PER_PERCENT = 1.78

    fun analyze(samples: List<Sample>): List<TripTrendPoint> =
        samples
            .filter { it.value != null }
            .groupBy { it.tripId }
            .map { (tripId, list) -> analyzeTrip(tripId, list.sortedBy { it.ts }) }
            .filter { it.startTs != Long.MAX_VALUE }

    private fun analyzeTrip(tripId: String, s: List<Sample>): TripTrendPoint {
        var rpmSum = 0.0; var rpmN = 0
        var spdSum = 0.0; var spdN = 0
        var loadSum = 0.0; var loadN = 0
        var tqSum = 0.0; var tqN = 0
        var idleSec = 0.0
        var idleFuelL = 0.0
        var startTs = Long.MAX_VALUE
        var lastTs: Long? = null
        var lastRpm: Double? = null
        var lastSpeed: Double? = null
        var lastFuelLh: Double? = null
        for (x in s) {
            startTs = minOf(startTs, x.ts)
            val v = x.value ?: continue
            val dt = lastTs?.let { ((x.ts - it) / 1000.0).coerceIn(0.0, 5.0) } ?: 0.0
            // Interval attribution: the seconds before this sample belong to the
            // PREVIOUS observation (same rule as RideBehaviorRecorder).
            val prevRpm = lastRpm
            val prevSpeed = lastSpeed
            val prevFuel = lastFuelLh
            when (x.pid) {
                PID_RPM -> { rpmSum += v; rpmN++; lastRpm = v }
                PID_SPEED -> { spdSum += v; spdN++; lastSpeed = v }
                PID_LOAD -> { loadSum += v; loadN++ }
                PID_TORQUE_PCT -> { tqSum += v; tqN++ }
                PID_FUEL_VOL -> lastFuelLh = v
                PID_FUEL_MASS -> lastFuelLh = v * 3600.0 / 745.0
            }
            if (dt > 0.0 && prevSpeed != null && prevSpeed < 1.0 && prevRpm != null && prevRpm in 400.0..1300.0) {
                idleSec += dt
                prevFuel?.let { idleFuelL += it * dt / 3600.0 }
            }
            lastTs = x.ts
        }
        val idleActualLh = if (idleSec > 30.0) idleFuelL / (idleSec / 3600.0) else null
        return TripTrendPoint(
            tripId = tripId,
            startTs = startTs,
            avgRpm = if (rpmN > 0) rpmSum / rpmN else null,
            avgSpeedKmh = if (spdN > 0) spdSum / spdN else null,
            avgLoadPct = if (loadN > 0) loadSum / loadN else null,
            avgTorqueNm = if (tqN > 0) (tqSum / tqN) * NM_PER_PERCENT else null,
            idleSec = idleSec,
            idleActualL = idleFuelL,
            idleModelL = MODEL_IDLE_LH * idleSec / 3600.0,
            idleActualLh = idleActualLh
        )
    }
}
