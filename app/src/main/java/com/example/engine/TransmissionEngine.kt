package com.example.engine

import com.example.model.TransmissionState
import com.example.model.ValueSource
import kotlin.math.abs

/**
 * Transmission monitoring and estimation engine for Škoda Kylaq 1.0 TSI (6-Speed Torque Converter Automatic).
 *
 * Strict Principles:
 * 1. ZERO DSG assumptions (no dual-clutch packs, no DSG-only DIDs).
 * 2. Actual Gear is reported ONLY when an ECU/TCU signal is validated. Otherwise: "Not available / Not detected".
 * 3. Estimated Gear is derived strictly from verified Engine RPM, Wheel Speed, and gear-ratio matching
 *    under confident lockup/coupling conditions (speed >= 10 km/h, RPM >= 1000).
 */
class TransmissionEngine {

    // Calibrated gear ratio bands for Škoda Kylaq 1.0 TSI (EA211 + 6-Speed Torque Converter AT)
    // Values represent RPM per km/h in locked-up / coupled converter state:
    /**
     * AQ250-6F / 09G (Aisin TF-60SN) per SSP 291 - ratios 4.148/2.370/1.556/1.155/0.859/0.686,
     * spread 6.05, slip-controlled lock-up. Relative ratios are factory truth; the absolute
     * rpm-per-km/h scale self-calibrates from steady cruise samples (final drive x idler x
     * tyre circumference varies by application), so D/S/M paddle gears all read correctly.
     */
    val gearModel = Aq250GearModel()
    private var lastRpmPerKmh: Double? = null

    /**
     * Evaluates transmission state.
     *
     * @param speedKmh Verified vehicle wheel speed
     * @param engineRpm Verified engine RPM
     * @param validatedActualGear Authoritative gear reported by vehicle (if validated)
     * @param rawGearRatio Raw gear ratio from standard PID 01A4 (if supported)
     */
    fun evaluate(
        speedKmh: Double?,
        engineRpm: Double?,
        validatedActualGear: Int? = null,
        rawGearRatio: Double? = null,
        validatedRange: String? = null
    ): TransmissionState {
        // 1. Authoritative Actual Gear
        val actualGearDisplay = when {
            validatedActualGear != null && validatedActualGear in 1..6 -> "Gear $validatedActualGear"
            rawGearRatio != null && rawGearRatio > 0.1 -> "Ratio %.3f".format(rawGearRatio)
            else -> "Not available / Not detected"
        }

        // 2. Selected Range (P / R / N / D)
        val range = when {
            validatedRange != null -> validatedRange
            speedKmh != null && speedKmh >= 5.0 -> "D (Driving)"
            engineRpm != null && engineRpm > 400.0 && (speedKmh == null || speedKmh < 2.0) -> "P/N (Idle)"
            else -> "—"
        }

        // 3. Estimated Gear (derived with high confidence threshold)
        var estimatedGear: Int? = null
        var isConfident = false

        if (speedKmh != null && engineRpm != null && speedKmh >= 10.0 && engineRpm >= 1000.0) {
            val currentRpmPerKmh = engineRpm / speedKmh
            // Only steady-ratio samples (converter locked, no shift transient) adapt the model.
            val stable = lastRpmPerKmh?.let {
                kotlin.math.abs(it - currentRpmPerKmh) / currentRpmPerKmh < 0.02
            } ?: false
            gearModel.observe(currentRpmPerKmh, stable)
            lastRpmPerKmh = currentRpmPerKmh

            val est = gearModel.estimate(currentRpmPerKmh)
            if (est != null) {
                estimatedGear = est.first
                isConfident = est.second
            }
        }

        val estimatedGearDisplay = if (estimatedGear != null && isConfident) {
            "Gear $estimatedGear (Estimated)"
        } else {
            "—"
        }

        return TransmissionState(
            selectedRange = range,
            actualGear = validatedActualGear,
            actualGearDisplay = actualGearDisplay,
            estimatedGear = estimatedGear,
            estimatedGearDisplay = estimatedGearDisplay,
            targetGearDisplay = "Not available",
            inputRpm = engineRpm,
            outputRpm = speedKmh?.let { it * 15.0 }, // approximate output shaft scale if unvalidated
            torqueConverterSlipRpm = null,
            torqueConverterLockup = if (isConfident) "Coupled / Locked" else "Not available",
            atfTemperatureC = null,
            isEstimatedGearConfident = isConfident,
            source = if (validatedActualGear != null) ValueSource.STANDARD_OBD else ValueSource.ESTIMATED
        )
    }
}
