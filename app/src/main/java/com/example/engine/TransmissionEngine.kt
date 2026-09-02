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
    // Final Drive: ~3.87
    // 1st (4.04): ~105 RPM / km/h
    // 2nd (2.37): ~62 RPM / km/h
    // 3rd (1.56): ~41 RPM / km/h
    // 4th (1.16): ~30 RPM / km/h
    // 5th (0.85): ~22 RPM / km/h
    // 6th (0.67): ~17.5 RPM / km/h
    private val gearRpmPerKmhBands = listOf(
        Pair(1, 92.0..120.0),
        Pair(2, 54.0..72.0),
        Pair(3, 36.0..48.0),
        Pair(4, 26.5..34.5),
        Pair(5, 19.5..25.5),
        Pair(6, 14.5..19.0)
    )

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

            for ((gear, band) in gearRpmPerKmhBands) {
                if (currentRpmPerKmh in band) {
                    estimatedGear = gear
                    isConfident = true
                    break
                }
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
