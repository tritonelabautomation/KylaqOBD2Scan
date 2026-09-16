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
    val idleActualLh: Double?,
    /**
     * Owner 2026-09-16: "There is no calculated power in trends based 2piNt mechanical
     * power formula based on speed torque". Mean mechanical power over timestamp-paired
     * (rpm, torque) samples: P = 2*pi*N*T / 60000 kW.
     */
    val avgPowerKw: Double? = null,
    /** Owner 2026-09-16: "gears are not displayed in trend" - most-used gear while moving. */
    val modeGear: Int? = null,
    val gearMin: Int? = null,
    val gearMax: Int? = null
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

    /**
     * Room projection pid list for the cross-trip trend charts: every pid in BOTH its
     * 4-hex form ("019D") and the 2-hex form the recorder actually stores ("9D").
     * Owner 2026-09-15: the projection only carried "0C"/"0D" fallbacks, so fuel rows
     * never reached the analyzer and the idle-burn trend read a flat 0.00 L/h
     * ("-100% vs model") even on trips that burned real idle fuel.
     */
    val TREND_PROJECTION_PIDS = listOf(
        PID_RPM, PID_SPEED, PID_LOAD, PID_TORQUE_PCT, PID_FUEL_VOL, PID_FUEL_MASS,
        "0C", "0D", "04", "62", "5E", "9D"
    )

    /** Matches PowertrainModel.IDLE_FUEL_LH - kept local so the analyzer stays dependency-free. */
    const val MODEL_IDLE_LH = 1.05

    /** rpm/torque/speed samples closer than this are the same engine moment. */
    const val PAIR_MS = 2500L

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
        // Power (2*pi*N*T/60000 kW) and gear (rpm-per-km/h vs GearModel) pairing state:
        // rpm is the common axis, torque/speed pair with it inside PAIR_MS.
        var lastRpmTs = -1L; var lastRpmV = 0.0
        var lastTqTs = -1L; var lastTqNm = 0.0
        var lastSpdTs = -1L; var lastSpdV = 0.0
        var powSum = 0.0; var powN = 0
        val gearCounts = HashMap<Int, Int>()
        val gearModel = com.example.engine.Aq250GearModel()
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
                PID_RPM -> {
                    rpmSum += v; rpmN++; lastRpm = v
                    lastRpmTs = x.ts; lastRpmV = v
                    if (lastTqTs >= 0 && kotlin.math.abs(x.ts - lastTqTs) <= PAIR_MS) {
                        powSum += kotlin.math.PI * 2.0 * v * lastTqNm / 60000.0; powN++
                    }
                    if (lastSpdTs >= 0 && kotlin.math.abs(x.ts - lastSpdTs) <= PAIR_MS && lastSpdV >= 10.0) {
                        val ratio = v / lastSpdV
                        val g = gearModel.estimate(ratio)?.first
                            ?: gearModel.nearestGear(ratio)?.first
                        if (g != null) gearCounts[g] = (gearCounts[g] ?: 0) + 1
                    }
                }
                PID_SPEED -> {
                    spdSum += v; spdN++; lastSpeed = v
                    lastSpdTs = x.ts; lastSpdV = v
                }
                PID_LOAD -> { loadSum += v; loadN++ }
                PID_TORQUE_PCT -> {
                    tqSum += v; tqN++
                    lastTqTs = x.ts; lastTqNm = v * NM_PER_PERCENT
                    if (lastRpmTs >= 0 && kotlin.math.abs(x.ts - lastRpmTs) <= PAIR_MS) {
                        powSum += kotlin.math.PI * 2.0 * lastRpmV * lastTqNm / 60000.0; powN++
                    }
                }
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
            idleActualLh = idleActualLh,
            avgPowerKw = if (powN > 0) powSum / powN else null,
            modeGear = gearCounts.maxByOrNull { it.value }?.key,
            gearMin = gearCounts.keys.minOrNull(),
            gearMax = gearCounts.keys.maxOrNull()
        )
    }

    /**
     * GPS altitude series for the Trends tab (owner 2026-09-16: "why altitude is
     * missing in trend and trip logs?"). Altitude is not an OBD PID: it is stamped on
     * every sample row from accuracy-gated GPS fixes (<= 40 m), so its series is built
     * from the row stamp rather than a pid filter. Rows without a fix are skipped -
     * gaps stay gaps, never interpolated, never 0.0 (recovered OBD-only trips therefore
     * yield an empty series and the chart honestly stays hidden).
     */
    const val PID_ALTITUDE_GPS = "ALT"

    fun <T> altitudePoints(
        samples: List<T>,
        timestamp: (T) -> Long,
        altitudeM: (T) -> Double?
    ): List<Pair<Long, Double>> =
        samples.mapNotNull { row -> altitudeM(row)?.let { timestamp(row) to it } }
            .sortedBy { it.first }
}
