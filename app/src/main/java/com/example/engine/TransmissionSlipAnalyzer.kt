package com.example.engine

import kotlin.math.abs

/** Kylaq variant ratio model. Does not change Aq250GearModel's independently calibrated ratios.
 * Requires validated actual forward gear from TCU 7E9, never an inferred gear.
 * Engine-vs-road slip includes normal converter slip; only flag it when lockup is confirmed.
 * Stress is an accumulated proxy in degree-seconds above 100 C, NOT an ATF temperature estimate.
 */
class TransmissionSlipAnalyzer(private val tyreCircumferenceM: Double) {
    init { require(tyreCircumferenceM.isFinite() && tyreCircumferenceM > 0.0) }
    companion object {
        val GEAR_RATIOS = listOf(4.044, 2.371, 1.556, 1.159, 0.852, 0.672)
        const val FINAL_DRIVE = 3.867
    }
    data class Result(
        val expectedRpm: Double?, val slipRpm: Double?, val slipPercent: Double?,
        val excessiveLockedSlip: Boolean, val thermalStressDegreeSeconds: Double
    )
    private var lastTime: Long? = null
    private var previousStress: Double? = null
    private var stress = 0.0
    private var latest = Result(null, null, null, false, 0.0)

    fun update(
        timeMs: Long, engineRpm: Double?, speedKmh: Double?, actualGear: Int?,
        ecu: String = "7E9", converterLocked: Boolean = false, atfTemperatureC: Double? = null
    ): Result {
        if (timeMs < 0 || lastTime?.let { timeMs <= it } == true) return latest
        val valid = ecu == "7E9" && actualGear != null && actualGear in 1..6 &&
            engineRpm != null && engineRpm.isFinite() && engineRpm in 500.0..8000.0 &&
            speedKmh != null && speedKmh.isFinite() && speedKmh in 5.0..250.0
        val expected = if (valid) speedKmh!! / 3.6 / tyreCircumferenceM * 60.0 *
            FINAL_DRIVE * GEAR_RATIOS[actualGear!! - 1] else null
        val slip = expected?.let { engineRpm!! - it }
        val percent = expected?.let { slip!! / it * 100.0 }
        val temperatureStress = atfTemperatureC?.takeIf {
            ecu == "7E9" && it.isFinite() && it in -40.0..180.0
        }?.let { (it - 100.0).coerceAtLeast(0.0) }
        val dt = lastTime?.let { timeMs - it } ?: 0L
        if (dt in 1..5000 && previousStress != null && temperatureStress != null) {
            stress += (previousStress!! + temperatureStress) / 2.0 * dt / 1000.0
        }
        lastTime = timeMs
        previousStress = temperatureStress
        latest = Result(expected, slip, percent, converterLocked && percent != null && abs(percent) > 10.0, stress)
        return latest
    }

    fun reset() {
        lastTime = null; previousStress = null; stress = 0.0
        latest = Result(null, null, null, false, 0.0)
    }
}
