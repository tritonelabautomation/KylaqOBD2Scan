package com.example.analysis

import com.example.engine.CoastNeutralDetector
import com.example.engine.FuelQualityAnalyzer
import com.example.engine.PowertrainModel
import com.example.engine.TurboAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The car-learning brain: fuses live OBD signals into the derived quantities the user asked for
 * and that no single PID provides —
 *
 *  * **power / torque vs rpm**, measured (PID 0162/0161 when the ECU answers, otherwise recovered
 *    from fuel energy and vehicle dynamics) and compared against the factory curve;
 *  * **fuel consumption vs speed**, measured in 10 km/h bins while cruising steadily, plus the
 *    modelled curve and the resulting **sweet spot** for the current top-gear ratio;
 *  * **coasting / driving-in-neutral** events with distance and fuel saved (20-130 km/h window);
 *  * **turbo behaviour**: live/peak boost, boost-vs-rpm curve, tip-in lag, over-boost counts;
 *  * **per-tank fuel behaviour** (refuel-segmented) for the X95-vs-regular comparison;
 *  * a 1 Hz **trend buffer** for the time-series charts.
 *
 * Everything is derived in one place ([onSignals]), called by `ObdScheduler` once per correlated
 * telemetry tick, so phone UI, Android Auto and future exports all read the same numbers.
 */
class DriveAnalytics(
    private val massKg: Double = PowertrainModel.KERB_KG + PowertrainModel.OCCUPANT_KG
) {

    data class TrendPoint(
        val timestampMonotonicMs: Long,
        val speedKmh: Double,
        val rpm: Double,
        val powerKw: Double?,
        val torqueNm: Double?,
        val fuelLh: Double?,
        val boostKpa: Double?
    )

    data class DriveSnapshot(
        val measuredPowerKw: Double? = null,
        val measuredTorqueNm: Double? = null,
        val demandedTorqueNm: Double? = null,
        val measuredRpmPerKmh: Double? = null,
        val powerCurve: List<Pair<Int, Double>> = emptyList(),
        val torqueCurve: List<Pair<Int, Double>> = emptyList(),
        val factoryTorqueCurve: List<Pair<Int, Double>> = emptyList(),
        val fuelVsSpeedMeasured: List<Pair<Int, Double>> = emptyList(),
        val fuelVsSpeedModel: List<Pair<Double, Double>> = emptyList(),
        val sweetSpotKmh: Double? = null,
        val sweetSpotKmL: Double? = null,
        val bestMeasuredSpeedKmh: Int? = null,
        val bestMeasuredKmL: Double? = null,
        val coast: CoastNeutralDetector.CoastSummary = CoastNeutralDetector.CoastSummary(),
        val activeCoastMode: CoastNeutralDetector.CoastMode? = null,
        val lastCoastEvent: CoastNeutralDetector.CoastEvent? = null,
        val turbo: TurboAnalyzer.TurboSnapshot? = null,
        val tanks: List<FuelQualityAnalyzer.TankSegment> = emptyList(),
        val activeTank: FuelQualityAnalyzer.TankSegment? = null,
        val fuelComparisonNote: String? = null,
        val harshAccelCount: Int = 0,
        val harshBrakeCount: Int = 0,
        val idleSeconds: Double = 0.0,
        val trend: List<TrendPoint> = emptyList()
    )

    val coastDetector = CoastNeutralDetector()
    val turboAnalyzer = TurboAnalyzer()
    val fuelQuality = FuelQualityAnalyzer()

    private val _snapshot = MutableStateFlow(DriveSnapshot())
    val snapshot: StateFlow<DriveSnapshot> = _snapshot.asStateFlow()

    private val trend = ArrayDeque<TrendPoint>()
    private var lastTrendMs = 0L
    private var lastSpeedKmh: Double? = null
    private var lastSpeedMs = 0L
    private var measuredRpmPerKmh: Double? = null
    private var lastSnapshotMs = 0L
    private var harshAccelCount = 0
    private var harshBrakeCount = 0
    private var idleSeconds = 0.0
    private var lastHarshAccelMs = 0L
    private var lastHarshBrakeMs = 0L
    private var lastSignalMs = 0L

    private val torqueBins = LinkedHashMap<Int, BinStats>()
    private val powerBins = LinkedHashMap<Int, BinStats>()
    private val lp100Bins = LinkedHashMap<Int, BinStats>()

    /**
     * One correlated telemetry tick. All inputs are optional — unsupported PIDs simply drop out
     * and the derived values degrade gracefully instead of showing invented numbers.
     */
    fun onSignals(
        timestampMonotonicMs: Long,
        speedKmh: Double?,
        rpm: Double?,
        throttlePct: Double?,
        pedalPct: Double?,
        fuelRateLh: Double?,
        mapKpa: Double?,
        baroKpa: Double?,
        timingDeg: Double?,
        stftPct: Double?,
        ltftPct: Double?,
        loadPct: Double?,
        fuelLevelPct: Double?,
        chargeTempC: Double?,
        wastegatePct: Double?,
        brakeActive: Boolean?,
        actualTorquePct: Double?,
        demandTorquePct: Double?,
        referenceTorqueNm: Double?
    ) {
        val speed = speedKmh ?: 0.0
        val accel = acceleration(timestampMonotonicMs, speed)
        countHarshEvents(timestampMonotonicMs, accel)
        countIdle(timestampMonotonicMs, speed, rpm)

        updateGearRatio(speed, rpm)

        // --- torque / power -------------------------------------------------------------
        val reference = referenceTorqueNm ?: PowertrainModel.PEAK_TORQUE_NM
        val measuredTorque = actualTorquePct?.let { PowertrainModel.torqueNmFromPercent(it, reference) }
            ?: estimateTorque(speed, rpm, accel, fuelRateLh)
        val demandedTorque = demandTorquePct?.let { PowertrainModel.torqueNmFromPercent(it, reference) }
        val powerKw = if (rpm != null && measuredTorque != null) {
            PowertrainModel.powerKw(rpm, measuredTorque)
        } else {
            PowertrainModel.powerDemandKw(speed, accel ?: 0.0, massKg)
        }

        if (rpm != null && rpm > 600) {
            val bin = (rpm.toInt() / 250) * 250
            measuredTorque?.let { torqueBins.getOrPut(bin) { BinStats() }.observe(it, throttlePct) }
            powerBins.getOrPut(bin) { BinStats() }.observe(powerKw, throttlePct)
        }

        // --- steady-state consumption per speed bin --------------------------------------
        val steady = accel != null && Math.abs(accel) < 0.25 && speed > 25
        if (steady && fuelRateLh != null && fuelRateLh > 0.05) {
            val lp100 = fuelRateLh / speed * 100.0
            if (lp100 in 1.0..40.0) {
                val bin = (speed.toInt() / 10) * 10
                lp100Bins.getOrPut(bin) { BinStats() }.observe(lp100, null)
            }
        }

        // --- coasting / driving in neutral -----------------------------------------------
        val cruiseLh = cruiseFuelLh(speed)
        coastDetector.onSample(
            CoastNeutralDetector.Sample(
                timestampMonotonicMs = timestampMonotonicMs,
                speedKmh = speed,
                rpm = rpm,
                throttlePct = throttlePct,
                pedalPct = pedalPct,
                brakeActive = brakeActive,
                fuelRateLh = fuelRateLh,
                cruiseFuelLh = cruiseLh
            )
        )

        // --- turbo ------------------------------------------------------------------------
        turboAnalyzer.onSample(
            timestampMonotonicMs = timestampMonotonicMs,
            rpm = rpm,
            mapKpa = mapKpa,
            baroKpa = baroKpa,
            pedalOrThrottlePct = pedalPct ?: throttlePct,
            chargeTempC = chargeTempC,
            wastegatePct = wastegatePct
        )

        // --- per-tank fuel behaviour -------------------------------------------------------
        fuelQuality.onSample(
            timestampMonotonicMs = timestampMonotonicMs,
            fuelLevelPct = fuelLevelPct,
            timingDeg = timingDeg,
            stftPct = stftPct,
            ltftPct = ltftPct,
            speedKmh = speedKmh,
            loadPct = loadPct,
            rpm = rpm,
            fuelRateLh = fuelRateLh
        )

        // --- 1 Hz trend buffer --------------------------------------------------------------
        if (timestampMonotonicMs - lastTrendMs >= 1000L) {
            lastTrendMs = timestampMonotonicMs
            trend.addLast(
                TrendPoint(
                    timestampMonotonicMs = timestampMonotonicMs,
                    speedKmh = speed,
                    rpm = rpm ?: 0.0,
                    powerKw = powerKw,
                    torqueNm = measuredTorque,
                    fuelLh = fuelRateLh,
                    boostKpa = turboAnalyzer.snapshot().boostKpa
                )
            )
            while (trend.size > MAX_TREND_POINTS) trend.removeFirst()
        }

        if (timestampMonotonicMs - lastSnapshotMs >= 1000L) {
            lastSnapshotMs = timestampMonotonicMs
            publish(
                measuredTorque = measuredTorque,
                demandedTorque = demandedTorque,
                powerKw = powerKw
            )
        }
    }

    fun reset() {
        coastDetector.reset()
        turboAnalyzer.reset()
        fuelQuality.reset()
        trend.clear()
        torqueBins.clear()
        powerBins.clear()
        lp100Bins.clear()
        measuredRpmPerKmh = null
        lastSpeedKmh = null
        _snapshot.value = DriveSnapshot()
    }

    // region internals

    private fun acceleration(ts: Long, speed: Double): Double? {
        val previousSpeed = lastSpeedKmh
        val previousTs = lastSpeedMs
        lastSpeedKmh = speed
        lastSpeedMs = ts
        if (previousSpeed == null || previousTs <= 0L) return null
        val dt = (ts - previousTs) / 1000.0
        if (dt < 0.2 || dt > 5.0) return null
        return (speed - previousSpeed) / 3.6 / dt
    }

    /** >2.5 m/s² (~0-100 km/h in under 11 s) counts as a harsh acceleration, debounced 3 s. */
    private fun countHarshEvents(ts: Long, accel: Double?) {
        val a = accel ?: return
        if (a > 2.5 && ts - lastHarshAccelMs > 3000L) {
            harshAccelCount++
            lastHarshAccelMs = ts
        } else if (a < -3.0 && ts - lastHarshBrakeMs > 3000L) {
            harshBrakeCount++
            lastHarshBrakeMs = ts
        }
    }

    /** Engine running while stationary burns fuel for nothing; accumulate it honestly. */
    private fun countIdle(ts: Long, speed: Double, rpm: Double?) {
        val dt = if (lastSignalMs > 0L) (ts - lastSignalMs).coerceIn(0L, 5000L) / 1000.0 else 0.0
        lastSignalMs = ts
        if (speed < 1.0 && rpm != null && rpm > 300.0) idleSeconds += dt
    }

    /**
     * Highest-gear ratio seen so far (rpm per km/h). The minimum ratio at road speed is the top
     * gear, which is exactly the gear the efficiency sweep should use for the sweet spot.
     */
    private fun updateGearRatio(speed: Double, rpm: Double?) {
        if (rpm == null || speed < 40.0 || rpm < 800.0) return
        val ratio = rpm / speed
        if (ratio < 5.0 || ratio > 80.0) return
        val current = measuredRpmPerKmh
        measuredRpmPerKmh = if (current == null) ratio else minOf(current, ratio * 1.02)
    }

    /**
     * Torque without PID 0162: fixed-point solve between the fuel-energy power and the
     * efficiency map, falling back to vehicle dynamics when no fuel rate is available.
     */
    private fun estimateTorque(speed: Double, rpm: Double?, accel: Double?, fuelRateLh: Double?): Double? {
        if (rpm == null || rpm < 600) return null
        val dynamicsPower = PowertrainModel.powerDemandKw(speed, accel ?: 0.0, massKg)
        val fuelPowerKw = fuelRateLh?.let { it * PowertrainModel.FUEL_ENERGY_MJ_PER_L / 3.6 }
        if (fuelPowerKw == null || fuelPowerKw <= 0.0) {
            return dynamicsPower * 9549.297 / rpm
        }
        var torque = dynamicsPower * 9549.297 / rpm
        repeat(3) {
            val eta = PowertrainModel.brakeThermalEfficiency(rpm, torque)
            val power = fuelPowerKw * eta
            torque = power * 9549.297 / rpm
        }
        return torque.coerceIn(0.0, PowertrainModel.PEAK_TORQUE_NM * 1.15)
    }

    private fun cruiseFuelLh(speed: Double): Double? {
        val ratio = measuredRpmPerKmh ?: return null
        val lp100 = PowertrainModel.litersPer100Km(speed, ratio, massKg) ?: return null
        return lp100 * speed / 100.0
    }

    private fun publish(measuredTorque: Double?, demandedTorque: Double?, powerKw: Double) {
        val ratio = measuredRpmPerKmh
        val modelSweep = ratio?.let { PowertrainModel.efficiencySweep(it, massKg) } ?: emptyList()
        val sweet = ratio?.let { PowertrainModel.sweetSpotKmh(it, massKg) }
        val measuredFuel = lp100Bins.entries
            .filter { it.value.count >= 5 }
            .map { it.key to it.value.average() }
            .sortedBy { it.first }
        val bestMeasured = measuredFuel.minByOrNull { it.second }

        _snapshot.value = DriveSnapshot(
            measuredPowerKw = powerKw,
            measuredTorqueNm = measuredTorque,
            demandedTorqueNm = demandedTorque,
            measuredRpmPerKmh = ratio,
            powerCurve = powerBins.curve(),
            torqueCurve = torqueBins.curve(),
            factoryTorqueCurve = FACTORY_CURVE_RPM_BINS.map { it to PowertrainModel.fullLoadTorqueNm(it.toDouble()) },
            fuelVsSpeedMeasured = measuredFuel,
            fuelVsSpeedModel = modelSweep,
            sweetSpotKmh = sweet?.first,
            sweetSpotKmL = sweet?.second?.let { PowertrainModel.kmPerLiter(it) },
            bestMeasuredSpeedKmh = bestMeasured?.first,
            bestMeasuredKmL = bestMeasured?.second?.let { PowertrainModel.kmPerLiter(it) },
            coast = coastDetector.summary,
            activeCoastMode = coastDetector.activeMode,
            lastCoastEvent = coastDetector.lastEvent,
            turbo = turboAnalyzer.snapshot(),
            tanks = fuelQuality.tanks(),
            activeTank = fuelQuality.activeTank(),
            fuelComparisonNote = fuelQuality.comparisonNote(),
            harshAccelCount = harshAccelCount,
            harshBrakeCount = harshBrakeCount,
            idleSeconds = idleSeconds,
            trend = trend.takeLast(SNAPSHOT_TREND_POINTS)
        )
    }

    private fun Map<Int, BinStats>.curve(): List<Pair<Int, Double>> =
        entries.filter { it.value.count >= 3 }.map { it.key to it.value.average() }.sortedBy { it.first }

    private class BinStats {
        var count = 0
            private set
        private var sum = 0.0
        private var highLoadMax = 0.0
        private var highLoadCount = 0

        fun observe(value: Double, throttlePct: Double?) {
            count++
            sum += value
            if (throttlePct != null && throttlePct > 70.0) {
                highLoadCount++
                if (value > highLoadMax) highLoadMax = value
            }
        }

        /** High-load samples win when present (a curve is a full-load object), else the mean. */
        fun average(): Double = if (highLoadCount >= 3) highLoadMax else if (count == 0) 0.0 else sum / count
    }

    // endregion

    companion object {
        private const val MAX_TREND_POINTS = 60 * 60 // 1 Hz for one hour
        /** Snapshot copies stay small; charts only show ~15 minutes anyway. */
        private const val SNAPSHOT_TREND_POINTS = 1800
        private val FACTORY_CURVE_RPM_BINS = listOf(1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000)
    }
}
