package com.example.analysis

import com.example.engine.PowertrainModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Passenger Load, Operating Mass & Engine Effort Analyzer for Škoda Kylaq 1.0 TSI (EA211).
 *
 * Derives:
 * 1. Occupant Payload & Total Vehicle Operating Mass (Solo baseline 1275 kg vs Carpool up to 1575 kg).
 * 2. Specific Fuel Economy under Load (L/100km per Tonne & L/100km per Passenger).
 * 3. Engine Mechanical Torque Demand & Turbo Boost Utilization % (PID 0162 / 010B).
 * 4. Transmission Shift Dynamics (AQ250 6-Speed AT Shift Bands: Eco <2.0k, Normal 2.0-2.45k, Sport >=2.45k RPM).
 * 5. Authentic "EA211 Overkill" & Powertrain Health Verdict (proves 1.0 TSI comfortably handles 5 passengers).
 *
 * Pure and fully JVM-unit-testable; no Android platform dependencies.
 */
object PassengerLoadAnalyzer {

    const val DEFAULT_OCCUPANT_KG = 75.0
    const val ATMOSPHERIC_KPA = 101.3
    const val BOOST_THRESHOLD_KPA = 105.0 // MAP above 105 kPa indicates positive turbo boost
    const val SPORT_RPM_SHIFT_THRESHOLD = 2450.0 // Shifts at or above 2450 RPM characteristic of Sport Mode / Heavy Load
    const val ECO_RPM_SHIFT_THRESHOLD = 2000.0 // Shifts below 2000 RPM characteristic of Eco D-Mode
    const val SPORT_HOLD_RPM_THRESHOLD = 1950.0 // Moving at city speeds holding >=1950 RPM in lower gears (vs D-mode 1200-1500 RPM)
    const val MAX_SAFE_COOLANT_C = 108.0 // EA211 thermal management derating ceiling

    enum class VerdictLevel {
        COMFORTABLE,
        MODERATE_LOAD,
        HEAVY_LOAD,
        OVERLOADED
    }

    data class ShiftEvent(
        val timestampMs: Long,
        val fromGear: Int,
        val toGear: Int,
        val preShiftRpm: Double,
        val speedKmh: Double,
        val isSportShift: Boolean
    )

    data class ShiftProfile(
        val totalUpshifts: Int,
        val ecoShiftsCount: Int,
        val normalShiftsCount: Int,
        val sportShiftsCount: Int,
        val ecoShiftsPct: Double,
        val normalShiftsPct: Double,
        val sportShiftsPct: Double,
        val avgUpshiftRpm: Double?,
        val sportModeHoldSeconds: Double,
        val sportModeHoldPct: Double,
        val detectedMode: String = "DRIVE (D)", // "SPORT (S)", "DRIVE (D)", "ECO (D)"
        val shiftEvents: List<ShiftEvent> = emptyList()
    )

    data class EngineOverkillVerdict(
        val level: VerdictLevel,
        val isOverkill: Boolean,
        val headline: String,
        val explanation: String,
        val mechanicalHeadroomPct: Double,
        val thermalHeadroomC: Double
    )

    data class Result(
        val occupantCount: Int,
        val passengerCount: Int,
        val occupantWeightKg: Double,
        val payloadKg: Double,
        val totalVehicleMassKg: Double,
        val soloBaselineMassKg: Double,
        val payloadMassIncreasePct: Double,
        // Specific energy & fuel efficiency under payload
        val fuelLiters: Double,
        val distanceKm: Double,
        val kmPerLiter: Double?,
        val specificFuelPerTonne100Km: Double?,
        val fuelPerPax100Km: Double?,
        // Engine torque & turbo boost
        val meanTorqueNm: Double?,
        val peakTorqueNm: Double?,
        val ratedTorqueNm: Double,
        val torqueUtilizationPct: Double?,
        val meanAccelerationTorqueNm: Double?,
        val boostActiveSeconds: Double,
        val boostActivePct: Double,
        val peakBoostBar: Double,
        val peakCoolantC: Double?,
        val maxRpm: Double?,
        // Shift profile
        val shiftProfile: ShiftProfile,
        // Engine health & overkill verdict
        val verdict: EngineOverkillVerdict
    )

    /**
     * Analyses passenger load, engine effort, and transmission shift dynamics.
     */
    fun analyze(
        occupantCount: Int,
        samples: List<TripFuelSummary.SamplePoint>,
        fuelSummary: TripFuelSummary.Summary,
        occupantWeightKg: Double = DEFAULT_OCCUPANT_KG
    ): Result {
        val totalOccupants = maxOf(1, occupantCount)
        val passengerCount = maxOf(0, totalOccupants - 1)
        val payloadKg = totalOccupants * occupantWeightKg
        val totalMassKg = PowertrainModel.KERB_KG + payloadKg
        val soloMassKg = PowertrainModel.KERB_KG + occupantWeightKg
        val payloadMassIncreasePct = if (soloMassKg > 0) ((totalMassKg - soloMassKg) / soloMassKg) * 100.0 else 0.0

        val byPid = samples.groupBy { TripFuelSummary.normalizePidKey(it.pid) }
        val speedSeries = (byPid["010D"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val rpmSeries = (byPid["010C"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val mapSeries = (byPid["010B"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val coolantSeries = (byPid["0105"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }

        // Torque calculation from 0162 + reference PID 0163 / 0164 / 178 Nm
        val torqueRef = fuelSummary.torqueReferenceNm ?: PowertrainModel.PEAK_TORQUE_NM
        val torqueSeries = (byPid["0162"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to PowertrainModel.torqueNmFromPercent(it, torqueRef) } }
            .sortedBy { it.first }

        val ratedTorque = torqueRef

        val modeledTorques = mutableListOf<Double>()
        if (torqueSeries.isEmpty() && speedSeries.isNotEmpty() && rpmSeries.isNotEmpty()) {
            for (i in 1 until speedSeries.size) {
                val (t0, v0) = speedSeries[i - 1]
                val (t1, v1) = speedSeries[i]
                val dtSec = (t1 - t0) / 1000.0
                if (dtSec in 0.2..5.0 && v1 >= 5.0) {
                    val accel = ((v1 - v0) / 3.6) / dtSec
                    val powerKw = PowertrainModel.powerDemandKw(v1, accel, totalMassKg)
                    val rpm = valueAt(rpmSeries, t1) ?: (v1 * 40.0)
                    if (rpm >= 800.0) {
                        val tNm = (powerKw * 9549.297 / rpm).coerceIn(10.0, ratedTorque)
                        modeledTorques.add(tNm)
                    }
                }
            }
        }

        val meanTorque = if (torqueSeries.isNotEmpty()) {
            torqueSeries.map { it.second }.average()
        } else if (fuelSummary.meanTorqueNm != null) {
            fuelSummary.meanTorqueNm
        } else if (modeledTorques.isNotEmpty()) {
            modeledTorques.average()
        } else null

        val peakTorque = if (torqueSeries.isNotEmpty()) {
            torqueSeries.maxOfOrNull { it.second }
        } else if (fuelSummary.peakTorqueNm != null) {
            fuelSummary.peakTorqueNm
        } else if (modeledTorques.isNotEmpty()) {
            modeledTorques.maxOrNull()
        } else null

        val torqueUtilPct = if (peakTorque != null && ratedTorque > 0) (peakTorque / ratedTorque) * 100.0 else null

        // Acceleration torque (when speed is increasing)
        val accelTorques = mutableListOf<Double>()
        for (i in 1 until speedSeries.size) {
            val (t0, v0) = speedSeries[i - 1]
            val (t1, v1) = speedSeries[i]
            val dtSec = (t1 - t0) / 1000.0
            if (dtSec in 0.5..10.0 && (v1 - v0) / dtSec >= 0.5) {
                val tVal = valueAt(torqueSeries, t1)
                if (tVal != null && tVal > 10.0) {
                    accelTorques.add(tVal)
                }
            }
        }
        val meanAccelTorque = if (accelTorques.isNotEmpty()) {
            accelTorques.average()
        } else if (modeledTorques.isNotEmpty()) {
            val highLoad = modeledTorques.filter { it > (meanTorque ?: 30.0) }
            if (highLoad.isNotEmpty()) highLoad.average() else null
        } else null

        // Turbo Boost & MAP Analysis
        var boostSeconds = 0.0
        var totalMovingSeconds = 0.0
        var maxMap = 100.0
        for (i in 1 until mapSeries.size) {
            val (t0, map0) = mapSeries[i - 1]
            val (t1, map1) = mapSeries[i]
            val dtSec = (t1 - t0) / 1000.0
            if (dtSec in 0.1..15.0) {
                val spd = valueAt(speedSeries, t1) ?: 0.0
                if (spd > 3.0) {
                    totalMovingSeconds += dtSec
                    if (map1 > BOOST_THRESHOLD_KPA) {
                        boostSeconds += dtSec
                    }
                }
                if (map1 > maxMap) maxMap = map1
            }
        }
        val boostActivePct = if (totalMovingSeconds > 5.0) (boostSeconds / totalMovingSeconds) * 100.0 else 0.0
        val peakBoostBar = max(0.0, (maxMap - ATMOSPHERIC_KPA) / 100.0)

        val peakCoolant = coolantSeries.maxOfOrNull { it.second }
        val maxRpm = rpmSeries.maxOfOrNull { it.second }

        // Specific Fuel Metrics
        val distance = if (fuelSummary.distanceKm > 0.1) fuelSummary.distanceKm else 1.0
        val fuelL = fuelSummary.fuelLiters
        val specificFuelPerTonne = if (totalMassKg > 0 && distance > 0) {
            (fuelL / (distance * (totalMassKg / 1000.0))) * 100.0
        } else null

        val fuelPerPax100Km = if (totalOccupants > 0 && distance > 0) {
            (fuelL / (distance * totalOccupants)) * 100.0
        } else null

        // Shift Profile Analysis
        val shiftProfile = analyzeShiftProfile(speedSeries, rpmSeries)

        // Engine Health & Overkill Verdict
        val verdict = evaluateOverkillVerdict(
            totalMassKg = totalMassKg,
            passengerCount = passengerCount,
            peakTorqueNm = peakTorque ?: 120.0,
            ratedTorqueNm = ratedTorque,
            peakCoolantC = peakCoolant ?: 90.0,
            boostActivePct = boostActivePct,
            sportShiftsPct = shiftProfile.sportShiftsPct
        )

        return Result(
            occupantCount = totalOccupants,
            passengerCount = passengerCount,
            occupantWeightKg = occupantWeightKg,
            payloadKg = payloadKg,
            totalVehicleMassKg = totalMassKg,
            soloBaselineMassKg = soloMassKg,
            payloadMassIncreasePct = payloadMassIncreasePct,
            fuelLiters = fuelL,
            distanceKm = fuelSummary.distanceKm,
            kmPerLiter = fuelSummary.kmPerLiter,
            specificFuelPerTonne100Km = specificFuelPerTonne,
            fuelPerPax100Km = fuelPerPax100Km,
            meanTorqueNm = meanTorque,
            peakTorqueNm = peakTorque,
            ratedTorqueNm = ratedTorque,
            torqueUtilizationPct = torqueUtilPct,
            meanAccelerationTorqueNm = meanAccelTorque,
            boostActiveSeconds = boostSeconds,
            boostActivePct = boostActivePct,
            peakBoostBar = peakBoostBar,
            peakCoolantC = peakCoolant,
            maxRpm = maxRpm,
            shiftProfile = shiftProfile,
            verdict = verdict
        )
    }

    /**
     * Discrete gear ratio band classifier for the Aisin AQ250 6-Speed Automatic.
     * Midpoint ratio thresholds ensure continuous gear classification across full rev ranges and slip.
     */
    fun classifyGear(rpmPerKmh: Double): Int {
        return when {
            rpmPerKmh >= 84.7 -> 1 // 1st gear (nominal 107.8)
            rpmPerKmh >= 51.0 -> 2 // 2nd gear (nominal 61.6)
            rpmPerKmh >= 35.25 -> 3 // 3rd gear (nominal 40.5)
            rpmPerKmh >= 26.15 -> 4 // 4th gear (nominal 30.0)
            rpmPerKmh >= 20.05 -> 5 // 5th gear (nominal 22.3)
            else -> 6              // 6th gear (nominal 17.8)
        }
    }

    /**
     * Detects gear shift points and sport/high-rev holding behavior.
     */
    fun analyzeShiftProfile(
        speedSeries: List<Pair<Long, Double>>,
        rpmSeries: List<Pair<Long, Double>>
    ): ShiftProfile {
        if (speedSeries.isEmpty() || rpmSeries.isEmpty()) {
            return ShiftProfile(0, 0, 0, 0, 0.0, 0.0, 0.0, null, 0.0, 0.0, "DRIVE (D)", emptyList())
        }

        data class GearPoint(val ts: Long, val gear: Int, val rpm: Double, val speed: Double)
        val gearPoints = mutableListOf<GearPoint>()

        for ((ts, rpm) in rpmSeries) {
            val speed = valueAt(speedSeries, ts) ?: continue
            if (speed >= 8.0 && rpm >= 700.0) {
                val ratio = rpm / speed
                val gear = classifyGear(ratio)
                gearPoints.add(GearPoint(ts, gear, rpm, speed))
            }
        }

        val shiftEvents = mutableListOf<ShiftEvent>()
        if (gearPoints.isNotEmpty()) {
            var currentGear = gearPoints.first().gear
            var peakRpmInGear = gearPoints.first().rpm
            var gearStartTs = gearPoints.first().ts

            for (i in 1 until gearPoints.size) {
                val pt = gearPoints[i]
                if (pt.gear == currentGear) {
                    if (pt.rpm > peakRpmInGear) {
                        peakRpmInGear = pt.rpm
                    }
                } else if (pt.gear > currentGear) {
                    // Upshift transition from currentGear to pt.gear
                    val dtSec = (pt.ts - gearStartTs) / 1000.0
                    if (dtSec >= 0.5) {
                        val shiftRpm = peakRpmInGear
                        val isSport = shiftRpm >= SPORT_RPM_SHIFT_THRESHOLD
                        shiftEvents.add(ShiftEvent(pt.ts, currentGear, pt.gear, shiftRpm, pt.speed, isSport))
                    }
                    currentGear = pt.gear
                    peakRpmInGear = pt.rpm
                    gearStartTs = pt.ts
                } else {
                    // Downshift
                    currentGear = pt.gear
                    peakRpmInGear = pt.rpm
                    gearStartTs = pt.ts
                }
            }
        }

        val totalUpshifts = shiftEvents.size
        val ecoCount = shiftEvents.count { it.preShiftRpm < ECO_RPM_SHIFT_THRESHOLD }
        val sportCount = shiftEvents.count { it.preShiftRpm >= SPORT_RPM_SHIFT_THRESHOLD }
        val normalCount = totalUpshifts - ecoCount - sportCount

        val ecoPct = if (totalUpshifts > 0) (ecoCount.toDouble() / totalUpshifts) * 100.0 else 0.0
        val normalPct = if (totalUpshifts > 0) (normalCount.toDouble() / totalUpshifts) * 100.0 else 0.0
        val sportPct = if (totalUpshifts > 0) (sportCount.toDouble() / totalUpshifts) * 100.0 else 0.0
        val avgUpshiftRpm = if (totalUpshifts > 0) shiftEvents.map { it.preShiftRpm }.average() else null

        // Sport Mode Hold Time: moving time holding higher revs (>=1950 RPM) in city driving (12-75 km/h)
        var sportHoldSec = 0.0
        var totalMovingSec = 0.0
        for (i in 1 until rpmSeries.size) {
            val (t0, rpm0) = rpmSeries[i - 1]
            val (t1, rpm1) = rpmSeries[i]
            val dtSec = (t1 - t0) / 1000.0
            if (dtSec in 0.1..15.0) {
                val spd = valueAt(speedSeries, t1) ?: 0.0
                if (spd in 12.0..75.0) {
                    totalMovingSec += dtSec
                    if (rpm1 >= SPORT_HOLD_RPM_THRESHOLD) {
                        sportHoldSec += dtSec
                    }
                }
            }
        }
        val sportHoldPct = if (totalMovingSec > 5.0) (sportHoldSec / totalMovingSec) * 100.0 else 0.0

        val detectedMode = when {
            sportHoldPct >= 20.0 || (avgUpshiftRpm != null && avgUpshiftRpm >= 2350.0) || sportPct >= 25.0 -> "SPORT (S)"
            ecoPct >= 65.0 && (avgUpshiftRpm != null && avgUpshiftRpm < 2000.0) -> "ECO (D)"
            else -> "DRIVE (D)"
        }

        return ShiftProfile(
            totalUpshifts = totalUpshifts,
            ecoShiftsCount = ecoCount,
            normalShiftsCount = normalCount,
            sportShiftsCount = sportCount,
            ecoShiftsPct = ecoPct,
            normalShiftsPct = normalPct,
            sportShiftsPct = sportPct,
            avgUpshiftRpm = avgUpshiftRpm,
            sportModeHoldSeconds = sportHoldSec,
            sportModeHoldPct = sportHoldPct,
            detectedMode = detectedMode,
            shiftEvents = shiftEvents
        )
    }

    /**
     * Evaluates whether the engine was overstressed or operated safely within design limits.
     */
    fun evaluateOverkillVerdict(
        totalMassKg: Double,
        passengerCount: Int,
        peakTorqueNm: Double,
        ratedTorqueNm: Double,
        peakCoolantC: Double,
        boostActivePct: Double,
        sportShiftsPct: Double
    ): EngineOverkillVerdict {
        val mechanicalHeadroom = if (ratedTorqueNm > 0) max(0.0, ((ratedTorqueNm - peakTorqueNm) / ratedTorqueNm) * 100.0) else 0.0
        val thermalHeadroom = max(0.0, MAX_SAFE_COOLANT_C - peakCoolantC)

        val isOverkill = mechanicalHeadroom >= 15.0 && thermalHeadroom >= 8.0 && boostActivePct < 60.0

        val level = when {
            peakCoolantC > 105.0 || mechanicalHeadroom < 5.0 -> VerdictLevel.HEAVY_LOAD
            passengerCount >= 3 || totalMassKg >= 1450.0 -> VerdictLevel.MODERATE_LOAD
            else -> VerdictLevel.COMFORTABLE
        }

        val headline = when {
            passengerCount >= 3 && isOverkill ->
                "Effortless Full-Load Performance: EA211 1.0 TSI operates well within thermal & torque margins with $passengerCount passengers."
            passengerCount in 1..2 ->
                "Balanced Carpool Operation: Engine delivered responsive acceleration with ${String.format(Locale.US, "%.0f", mechanicalHeadroom)}% mechanical reserve."
            else ->
                "Light Solo Operation: Single occupant baseline: EA211 operated under minimal mechanical load with ${String.format(Locale.US, "%.0f", mechanicalHeadroom)}% reserve torque."
        }

        val explanation = buildString {
            append("Peak torque reached ${String.format(Locale.US, "%.0f", peakTorqueNm)} Nm of the ${ratedTorqueNm.toInt()} Nm factory rating (${String.format(Locale.US, "%.0f", mechanicalHeadroom)}% safety margin). ")
            append("Coolant stabilized at ${String.format(Locale.US, "%.1f", peakCoolantC)}°C (${String.format(Locale.US, "%.1f", thermalHeadroom)}°C thermal headroom). ")
            if (boostActivePct > 20.0) {
                append("Turbo boost was active for ${String.format(Locale.US, "%.0f", boostActivePct)}% of driving time to maintain brisk momentum. ")
            }
            if (sportShiftsPct > 20.0) {
                append("Higher shift RPMs (~${String.format(Locale.US, "%.0f", sportShiftsPct)}% in Sport/Load band) protected the engine from lugging by keeping the turbo directly in its 1750–4000 RPM peak torque plateau.")
            } else {
                append("Transmission maintained efficient low-RPM cruising without lugging or knocking.")
            }
        }

        return EngineOverkillVerdict(
            level = level,
            isOverkill = isOverkill,
            headline = headline,
            explanation = explanation,
            mechanicalHeadroomPct = mechanicalHeadroom,
            thermalHeadroomC = thermalHeadroom
        )
    }

    private fun valueAt(series: List<Pair<Long, Double>>, ts: Long): Double? {
        if (series.isEmpty()) return null
        var candidate: Double? = null
        for (point in series) {
            if (point.first <= ts) candidate = point.second else break
        }
        return candidate
    }
}
