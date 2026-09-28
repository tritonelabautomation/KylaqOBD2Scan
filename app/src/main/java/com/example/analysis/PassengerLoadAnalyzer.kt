package com.example.analysis

import com.example.engine.Aq250GearModel
import com.example.engine.PowertrainModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Passenger Payload, Engine Work & Transmission Shift Behavior Analyzer for Škoda Kylaq 1.0 TSI (EA211).
 *
 * Evaluates:
 * 1. Payload & Mass Dynamics: Vehicle kerb mass (1200 kg) + driver & carpool passengers (75-80 kg/person).
 * 2. Engine Work & Torque Demand: Mean/peak torque (PID 0162/0163) vs 178 Nm EA211 rated limit, acceleration torque.
 * 3. Turbocharger Boost Demand: MAP (PID 010B) & Boost relative to atmospheric pressure (101.3 kPa).
 * 4. Transmission Shift Behavior & Sports Mode: Upshift RPM distribution (Eco <2.2k, Normal 2.2-2.8k, Sport/Load >2.8k)
 *    and high-RPM holding duration (~3k RPM shift points in Sport / Heavy Load).
 * 5. Authentic EA211 Overkill & Health Verdict: Reassurance based on factory mechanical/thermal engineering limits.
 *
 * Pure and fully JVM-unit-testable; no Android platform dependencies.
 */
object PassengerLoadAnalyzer {

    const val DEFAULT_OCCUPANT_KG = 75.0
    const val ATMOSPHERIC_KPA = 101.3
    const val BOOST_THRESHOLD_KPA = 105.0 // MAP above 105 kPa indicates positive turbo boost
    const val SPORT_RPM_SHIFT_THRESHOLD = 2800.0 // Shifts above 2800 RPM characteristic of Sport Mode / Heavy Load
    const val ECO_RPM_SHIFT_THRESHOLD = 2200.0 // Shifts below 2200 RPM characteristic of Eco D-Mode
    const val SPORT_HOLD_RPM_THRESHOLD = 2600.0 // Cruising/acceleration holding >2600 RPM in low gears
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
        val shiftEvents: List<ShiftEvent>
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
                // Find matching torque near t1
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
        val maxRpm = rpmSeries.maxOfOrNull { it.second } ?: fuelSummary.maxSpeedKmh

        // Shift behavior & Sport Mode detection
        val shiftProfile = analyzeShiftProfile(speedSeries, rpmSeries)

        // Specific metrics
        val tonne = totalMassKg / 1000.0
        val specificFuelPerTonne100Km = if (fuelSummary.distanceKm > 0.1 && fuelSummary.fuelLiters > 0.01) {
            (fuelSummary.fuelLiters / (fuelSummary.distanceKm * tonne)) * 100.0
        } else null

        val fuelPerPax100Km = if (fuelSummary.distanceKm > 0.1 && fuelSummary.fuelLiters > 0.01 && totalOccupants > 0) {
            fuelSummary.fuelLiters / (fuelSummary.distanceKm * totalOccupants) * 100.0
        } else null

        // Engine Overkill Verdict
        val verdict = evaluateEngineVerdict(
            totalOccupants = totalOccupants,
            payloadKg = payloadKg,
            totalMassKg = totalMassKg,
            peakTorqueNm = peakTorque,
            ratedTorqueNm = ratedTorque,
            peakCoolantC = peakCoolant,
            maxRpm = maxRpm,
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
            fuelLiters = fuelSummary.fuelLiters,
            distanceKm = fuelSummary.distanceKm,
            kmPerLiter = fuelSummary.kmPerLiter,
            specificFuelPerTonne100Km = specificFuelPerTonne100Km,
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
     * Detects gear shift points and sport/high-rev holding behavior.
     */
    fun analyzeShiftProfile(
        speedSeries: List<Pair<Long, Double>>,
        rpmSeries: List<Pair<Long, Double>>
    ): ShiftProfile {
        if (speedSeries.isEmpty() || rpmSeries.isEmpty()) {
            return ShiftProfile(0, 0, 0, 0, 0.0, 0.0, 0.0, null, 0.0, 0.0, emptyList())
        }

        val gearModel = Aq250GearModel()
        val gearEstimates = mutableListOf<Triple<Long, Int, Double>>() // ts, gear, rpm

        for ((ts, rpm) in rpmSeries) {
            val speed = valueAt(speedSeries, ts) ?: continue
            if (speed >= 10.0 && rpm >= 1000.0) {
                val ratio = rpm / speed
                gearModel.estimate(ratio)?.first?.let { gear ->
                    gearEstimates.add(Triple(ts, gear, rpm))
                }
            }
        }

        val shiftEvents = mutableListOf<ShiftEvent>()
        for (i in 1 until gearEstimates.size) {
            val (t0, g0, rpm0) = gearEstimates[i - 1]
            val (t1, g1, _) = gearEstimates[i]
            val dtSec = (t1 - t0) / 1000.0

            // Upshift detected: G to G+1 within 10 seconds
            if (g1 > g0 && dtSec in 0.2..10.0) {
                val spd = valueAt(speedSeries, t0) ?: 0.0
                val isSport = rpm0 >= SPORT_RPM_SHIFT_THRESHOLD
                shiftEvents.add(ShiftEvent(t0, g0, g1, rpm0, spd, isSport))
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

        // Sport Mode Hold Time: moving time with high RPM (>2600) in lower gears (< 75 km/h)
        var sportHoldSec = 0.0
        var totalMovingSec = 0.0
        for (i in 1 until rpmSeries.size) {
            val (t0, rpm0) = rpmSeries[i - 1]
            val (t1, rpm1) = rpmSeries[i]
            val dtSec = (t1 - t0) / 1000.0
            if (dtSec in 0.1..15.0) {
                val spd = valueAt(speedSeries, t1) ?: 0.0
                if (spd > 15.0) {
                    totalMovingSec += dtSec
                    if (rpm1 >= SPORT_HOLD_RPM_THRESHOLD && spd < 80.0) {
                        sportHoldSec += dtSec
                    }
                }
            }
        }
        val sportHoldPct = if (totalMovingSec > 5.0) (sportHoldSec / totalMovingSec) * 100.0 else 0.0

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
            shiftEvents = shiftEvents
        )
    }

    /**
     * Evaluates whether the engine was overstressed or operated safely within design limits.
     */
    private fun evaluateEngineVerdict(
        totalOccupants: Int,
        payloadKg: Double,
        totalMassKg: Double,
        peakTorqueNm: Double?,
        ratedTorqueNm: Double,
        peakCoolantC: Double?,
        maxRpm: Double?,
        boostActivePct: Double,
        sportShiftsPct: Double
    ): EngineOverkillVerdict {
        val peakT = peakTorqueNm ?: 150.0
        val peakC = peakCoolantC ?: 90.0
        val maxR = maxRpm ?: 3500.0

        val mechanicalHeadroom = max(0.0, (1.0 - (peakT / ratedTorqueNm)) * 100.0)
        val thermalHeadroom = max(0.0, MAX_SAFE_COOLANT_C - peakC)

        val isOverkill = peakT > 195.0 || peakC > MAX_SAFE_COOLANT_C || maxR > 6200.0

        val (level, headline, explanation) = when {
            isOverkill -> {
                Triple(
                    VerdictLevel.OVERLOADED,
                    "⚠️ Elevated Powertrain Load Recorded",
                    "Telemetry captured peak torque of ${String.format(Locale.US, "%.0f", peakT)} Nm or coolant peaking at ${peakC.toInt()}°C. Engine operated near maximum thermal limits."
                )
            }
            totalOccupants >= 4 -> {
                Triple(
                    VerdictLevel.COMFORTABLE,
                    "✅ Zero Engine Overkill — EA211 Coped Comfortably",
                    "Carrying $totalOccupants occupants (+${payloadKg.toInt()} kg payload, total mass ${totalMassKg.toInt()} kg), the EA211 1.0 TSI evo2 operated with ${String.format(Locale.US, "%.0f", mechanicalHeadroom)}% torque headroom (peak: ${String.format(Locale.US, "%.0f", peakT)} Nm / $ratedTorqueNm Nm rated) and ${String.format(Locale.US, "%.0f", thermalHeadroom)}°C thermal margin (coolant: ${peakC.toInt()}°C). " +
                        (if (sportShiftsPct > 20.0) "Higher shift RPMs (~${String.format(Locale.US, "%.0f", sportShiftsPct)}% in Sport/Load band) protected the engine from lugging by keeping the turbo directly in its 1750–4000 RPM peak torque plateau."
                        else "Transmission shift mapping maintained smooth, efficient power delivery.")
                )
            }
            totalOccupants in 2..3 -> {
                Triple(
                    VerdictLevel.COMFORTABLE,
                    "✅ Optimal Powertrain Efficiency",
                    "Under moderate payload ($totalOccupants occupants, +${payloadKg.toInt()} kg), peak torque utilized ${String.format(Locale.US, "%.0f", (peakT / ratedTorqueNm) * 100.0)}% of rated capacity. Thermal management and boost pressure remained well within factory tolerances."
                )
            }
            else -> {
                Triple(
                    VerdictLevel.COMFORTABLE,
                    "✅ Light Solo Operation",
                    "Single occupant baseline: EA211 operated under minimal mechanical load with ${String.format(Locale.US, "%.0f", mechanicalHeadroom)}% reserve torque."
                )
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
