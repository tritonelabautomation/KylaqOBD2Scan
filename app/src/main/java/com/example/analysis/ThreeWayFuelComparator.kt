package com.example.analysis

import com.example.engine.PowertrainModel
import java.util.Locale
import kotlin.math.abs

/**
 * Three-Way Fuel Comparison and Calibration Analysis Engine.
 *
 * Compares three independent measures of trip fuel consumption and efficiency:
 *
 *  1. **Factor 1 (High-Frequency OBD Injection Integration)**:
 *     Microsecond integration of PID 019D (Mass Air/Fuel Flow g/s) or PID 015E (Fuel Rate L/h)
 *     divided by petrol density (0.745 kg/L). Captures continuous throttle transients, decel fuel
 *     cut-offs (DFCO), and idle fuel flow.
 *
 *  2. **Factor 2 (Tank Float Level Delta PID 012F %)**:
 *     Fuel tank float level percentage drop ($\Delta \text{Level} = \text{Start \%} - \text{End \%}$)
 *     multiplied by 45.0 L tank capacity. Subject to float potentiometer step quantization (~0.5%
 *     resolution = ±0.225 L), fuel slosh, vehicle tilt, and thermal expansion.
 *
 *  3. **Factor 3 (Vehicle Instrument Cluster MID "Since Start")**:
 *     Ground-truth cluster readout calculated by the Bosch/Continental ECU from injector pulse
 *     durations with factory display damping and calibration bias.
 */
object ThreeWayFuelComparator {

    data class FactorResult(
        val name: String,
        val methodCode: String,
        val fuelLiters: Double?,
        val economyKmL: Double?,
        val economyL100Km: Double?,
        val deltaLitersVsMid: Double?,
        val errorPctVsMid: Double?,
        val accuracyVerdict: String,
        val explanation: String
    )

    data class ThreeWayComparison(
        val tripId: String,
        val distanceKm: Double,
        val durationMinutes: Long,
        // The Three Factors
        val factor1ObdIntegration: FactorResult,
        val factor2TankFloatDelta: FactorResult,
        val factor3ClusterMid: FactorResult,
        // Overall synthesis
        val closestFactorNumber: Int, // 1 or 2
        val calibrationRatio: Double?, // Factor 1 Litres / Factor 3 Litres
        val distanceDiscrepancyKm: Double?,
        val durationDiscrepancyMin: Long?,
        val primaryInsightVerdict: String,
        val recommendation: String
    )

    /**
     * Executes the comprehensive 3-way fuel calibration analysis.
     */
    fun compare(
        tripId: String,
        fuelSummary: TripFuelSummary.Summary,
        midData: MidClusterData,
        tankCapacityL: Double = PowertrainModel.TANK_CAPACITY_L
    ): ThreeWayComparison {
        val distance = if (fuelSummary.distanceKm > 0.05) fuelSummary.distanceKm else midData.distanceKm
        val durationMin = if (fuelSummary.durationSeconds > 0) fuelSummary.durationSeconds / 60 else midData.durationMinutes.toLong()

        // ── Factor 3: Vehicle Cluster MID (Ground-truth cluster display) ───────────────
        val f3KmL = midData.avgFuelEconomyKmL
        val f3Liters = midData.impliedFuelLiters
        val f3L100 = midData.fuelEconomyL100Km

        val factor3 = FactorResult(
            name = "Vehicle MID Cluster",
            methodCode = "MID_SINCE_START",
            fuelLiters = f3Liters,
            economyKmL = f3KmL,
            economyL100Km = f3L100,
            deltaLitersVsMid = 0.0,
            errorPctVsMid = 0.0,
            accuracyVerdict = "OEM Reference Baseline",
            explanation = "Direct readout from the instrument cluster. Uses factory ECU injector pulse width integration with display smoothing."
        )

        // ── Factor 1: High-Frequency OBD Injection Integration ───────────────────────
        val f1Liters = fuelSummary.fuelLiters
        val f1KmL = fuelSummary.kmPerLiter ?: if (f1Liters > 0.05 && distance > 0.05) distance / f1Liters else null
        val f1L100 = if (f1KmL != null && f1KmL > 0.1) 100.0 / f1KmL else null
        val f1DeltaLiters = if (f1Liters > 0.01 && f3Liters > 0.01) f1Liters - f3Liters else null
        val f1ErrorPct = if (f1KmL != null && f3KmL > 0.1) ((f1KmL - f3KmL) / f3KmL) * 100.0 else null

        val f1Verdict = when {
            f1ErrorPct == null -> "Awaiting High-Frequency Samples"
            abs(f1ErrorPct) <= 4.0 -> "Near-Perfect Match (<4% delta)"
            abs(f1ErrorPct) <= 8.0 -> "High Physics Convergence (4-8% delta)"
            f1ErrorPct < -8.0 -> "More Conservative Than MID (Reflects Dynamic Boost Pulls)"
            else -> "Slightly Optimistic vs MID"
        }

        val f1Explanation = when {
            f1ErrorPct == null -> "OBD fuel rate samples were insufficient for full dynamic integration."
            abs(f1ErrorPct) <= 5.0 -> "Integrates microsecond PID 019D/015E fuel mass directly at cylinder ports, converging almost identically with the factory cluster."
            f1ErrorPct < 0 -> "Captures aggressive turbo boost transient enrichments and stop-and-go idle fuel burn that cluster smoothing sometimes filters out."
            else -> "OBD injector sampling aligns smoothly with steady-state cruising."
        }

        val factor1 = FactorResult(
            name = "OBD Fuel Integration",
            methodCode = "PID_019D_015E_INTEGRAL",
            fuelLiters = f1Liters,
            economyKmL = f1KmL,
            economyL100Km = f1L100,
            deltaLitersVsMid = f1DeltaLiters,
            errorPctVsMid = f1ErrorPct,
            accuracyVerdict = f1Verdict,
            explanation = f1Explanation
        )

        // ── Factor 2: Tank Float Level Delta (PID 012F %) ─────────────────────────────
        val startPct = fuelSummary.startFuelPercent
        val endPct = fuelSummary.endFuelPercent
        val deltaPct = if (startPct != null && endPct != null) startPct - endPct else null // positive = consumed

        val f2Liters = if (deltaPct != null && deltaPct > 0.1) {
            (deltaPct / 100.0) * tankCapacityL
        } else fuelSummary.fuelDeltaLiters

        val f2KmL = if (f2Liters != null && f2Liters > 0.05 && distance > 0.05) distance / f2Liters else null
        val f2L100 = if (f2KmL != null && f2KmL > 0.1) 100.0 / f2KmL else null
        val f2DeltaLiters = if (f2Liters != null && f3Liters > 0.01) f2Liters - f3Liters else null
        val f2ErrorPct = if (f2KmL != null && f3KmL > 0.1) ((f2KmL - f3KmL) / f3KmL) * 100.0 else null

        val f2Verdict = when {
            f2ErrorPct == null -> "Float Sensor Delta Unavailable"
            abs(f2ErrorPct) <= 8.0 -> "Good Float Tracking"
            abs(f2ErrorPct) <= 20.0 -> "Coarse Float Quantization (±0.5% steps)"
            else -> "Float Slosh / Gradient Inaccuracy"
        }

        val f2Explanation = when {
            f2ErrorPct == null -> "PID 012F fuel tank level was unmeasured or fuel level change was smaller than sensor resolution."
            abs(f2ErrorPct) <= 8.0 -> "Tank float level sensor tracked fuel drawdown consistently across this distance."
            else -> "The mechanical fuel float has ~0.5% step resolution (±0.23 L on a 45L tank) and is susceptible to road incline tilt and cornering slosh over short trips."
        }

        val factor2 = FactorResult(
            name = "Tank Float % Delta",
            methodCode = "PID_012F_FLOAT_DELTA",
            fuelLiters = f2Liters,
            economyKmL = f2KmL,
            economyL100Km = f2L100,
            deltaLitersVsMid = f2DeltaLiters,
            errorPctVsMid = f2ErrorPct,
            accuracyVerdict = f2Verdict,
            explanation = f2Explanation
        )

        // ── Synthesis & Convergence Analysis ─────────────────────────────────────────
        val f1AbsError = f1ErrorPct?.let { abs(it) } ?: Double.MAX_VALUE
        val f2AbsError = f2ErrorPct?.let { abs(it) } ?: Double.MAX_VALUE
        val closestFactor = if (f1AbsError <= f2AbsError) 1 else 2

        val calibrationRatio = if (f1Liters > 0.01 && f3Liters > 0.01) f1Liters / f3Liters else null
        val distDiff = if (fuelSummary.distanceKm > 0.05 && midData.distanceKm > 0.05) abs(fuelSummary.distanceKm - midData.distanceKm) else null
        val durDiff = if (fuelSummary.durationSeconds > 0 && midData.durationMinutes > 0) abs((fuelSummary.durationSeconds / 60) - midData.durationMinutes) else null

        val primaryVerdict = buildString {
            if (f1ErrorPct != null && abs(f1ErrorPct) <= 6.0) {
                append(String.format(Locale.US, "OBD Fuel Integration (Factor 1) matches MID (Factor 3) with remarkable precision (%.1f km/L vs %.1f km/L, only %.1f%% delta). ", f1KmL ?: 0.0, f3KmL, abs(f1ErrorPct)))
            } else if (f1KmL != null) {
                append(String.format(Locale.US, "OBD Fuel Integration is %.1f km/L (vs MID %.1f km/L, %.1f%% delta). ", f1KmL, f3KmL, f1ErrorPct))
            } else {
                append(String.format(Locale.US, "MID recorded %.1f km/L over %.1f km. ", f3KmL, midData.distanceKm))
            }

            if (f2ErrorPct != null && abs(f2ErrorPct) > 12.0) {
                append("Tank Float Delta (Factor 2) exhibits coarse potentiometer quantization and should be used only for multi-hundred km brim-to-brim tracking.")
            } else {
                append("Dynamic injector mass flow provides the highest continuous fidelity.")
            }
        }

        val recommendation = when {
            f1ErrorPct != null && abs(f1ErrorPct) <= 5.0 ->
                "Factor 1 (OBD Mass Integration) is your most reliable ground truth for daily commute tracking."
            f1ErrorPct != null && f1ErrorPct < -5.0 ->
                "Factor 1 captures heavy acceleration/turbo enrichment that MID smoothing slightly underestimates. Trust Factor 1 for true chemical fuel burn."
            else ->
                "Factor 3 (MID) and Factor 1 (OBD) provide dual-validated benchmarks for your EA211 1.0 TSI."
        }

        return ThreeWayComparison(
            tripId = tripId,
            distanceKm = distance,
            durationMinutes = durationMin,
            factor1ObdIntegration = factor1,
            factor2TankFloatDelta = factor2,
            factor3ClusterMid = factor3,
            closestFactorNumber = closestFactor,
            calibrationRatio = calibrationRatio,
            distanceDiscrepancyKm = distDiff,
            durationDiscrepancyMin = durDiff,
            primaryInsightVerdict = primaryVerdict,
            recommendation = recommendation
        )
    }
}
