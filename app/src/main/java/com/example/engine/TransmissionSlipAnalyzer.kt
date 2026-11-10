package com.example.engine

import java.util.Locale
import kotlin.math.abs

/**
 * 6-speed AQ250 torque-converter slip & ATF thermal-stress analyzer.
 *
 * Strictly torque-converter (ZERO DSG assumptions). Slip is the difference between engine
 * RPM and the RPM the Aisin TF-60SN (09G / AQ250-6F) should show for the nearest gear at
 * the current road speed, using SSP 291 ratios (4.148 / 2.370 / 1.556 / 1.155 / 0.859 /
 * 0.686) scaled by [Aq250GearModel.PRIOR_SCALE].
 *
 * ATF G 052 025 A2 is a "lifetime" fill, but hydrodynamic slip still dumps heat into the
 * fluid. We integrate a slip-energy proxy and, when PID-style ATF temperature is supplied,
 * fold that in. Stress is a 0–100 score with NONE / MILD / ELEVATED / SEVERE bands.
 */
class TransmissionSlipAnalyzer(
    private val lockupSlipPct: Double = 2.5,
    private val slipControlPct: Double = 12.0,
    private val lockupMinSpeedKmh: Double = 40.0,
    private val openMaxSpeedKmh: Double = 15.0,
    private val atfElevatedC: Double = 110.0,
    private val atfSevereC: Double = 130.0
) {

    enum class Lockup { UNKNOWN, OPEN, SLIP_CONTROLLED, LOCKED }

    enum class Stress { NONE, MILD, ELEVATED, SEVERE }

    data class Snapshot(
        val estimatedGear: Int?,
        val engineRpm: Double?,
        val expectedRpm: Double?,
        val slipRpm: Double?,
        val slipPercent: Double?,
        val lockup: Lockup,
        val atfStress: Stress,
        val atfStressScore: Double,
        val cumulativeSlipEnergyKj: Double,
        val atfTempC: Double?,
        val message: String
    )

    val gearModel = Aq250GearModel()

    private var lastTs: Long = 0L
    private var lastRpmPerKmh: Double? = null
    private var cumulativeSlipEnergyKj: Double = 0.0
    private var atfStressScore: Double = 0.0
    private var lastAtfTempC: Double? = null

    fun reset() {
        lastTs = 0L
        lastRpmPerKmh = null
        cumulativeSlipEnergyKj = 0.0
        atfStressScore = 0.0
        lastAtfTempC = null
        lastGear = null
    }

    fun onSample(
        timestampMonotonicMs: Long,
        speedKmh: Double?,
        engineRpm: Double?,
        loadPct: Double? = null,
        atfTempC: Double? = null,
        estimatedGear: Int? = null
    ): Snapshot {
        val dtSec = if (lastTs > 0L) {
            ((timestampMonotonicMs - lastTs).coerceIn(0L, 5000L)) / 1000.0
        } else 0.0
        lastTs = timestampMonotonicMs
        atfTempC?.let { lastAtfTempC = it }

        val speed = speedKmh ?: 0.0
        val rpm = engineRpm ?: 0.0

        if (speed < 8.0 || rpm < 800.0) {
            if (dtSec > 0.0 && speed < 3.0) {
                atfStressScore = (atfStressScore - 0.15 * dtSec).coerceAtLeast(0.0)
            }
            return snapshot(
                gear = null,
                engineRpm = engineRpm,
                expectedRpm = null,
                slipRpm = null,
                slipPercent = null,
                lockup = if (rpm > 400.0 && speed < 3.0) Lockup.OPEN else Lockup.UNKNOWN
            )
        }

        val rpmPerKmh = rpm / speed
        val nearest = gearModel.nearestGear(rpmPerKmh)
        val gear = estimatedGear?.takeIf { it in 1..6 } ?: nearest?.first

        val expected = gear?.let { gearModel.expectedRpm(it, speed) }
        val slipRpm = if (expected != null) rpm - expected else null
        val slipPercent = if (slipRpm != null && rpm > 0.0) (slipRpm / rpm) * 100.0 else null

        val stable = lastRpmPerKmh?.let { abs(it - rpmPerKmh) / rpmPerKmh < 0.02 } ?: false
        lastRpmPerKmh = rpmPerKmh
        if (stable && slipPercent != null && abs(slipPercent) < 3.0 && speed >= lockupMinSpeedKmh) {
            gearModel.observe(rpmPerKmh, stable = true)
        }

        val lockup = when {
            speed < openMaxSpeedKmh -> Lockup.OPEN
            slipPercent == null -> Lockup.UNKNOWN
            abs(slipPercent) <= lockupSlipPct && speed >= lockupMinSpeedKmh -> Lockup.LOCKED
            abs(slipPercent) <= slipControlPct -> Lockup.SLIP_CONTROLLED
            else -> Lockup.OPEN
        }

        if (dtSec > 0.0 && slipRpm != null) {
            val load = (loadPct ?: 30.0).coerceIn(0.0, 120.0) / 100.0
            if (abs(slipRpm) > 40.0) {
                cumulativeSlipEnergyKj += abs(slipRpm) * load * dtSec * 0.002
            }
            val energyScore = (cumulativeSlipEnergyKj * 2.0).coerceIn(0.0, 100.0)
            val tempBump = lastAtfTempC?.let { temp ->
                when {
                    temp >= atfSevereC -> 40.0
                    temp >= atfElevatedC -> 20.0 + (temp - atfElevatedC)
                    else -> 0.0
                }
            } ?: 0.0
            val decay = if (lockup == Lockup.LOCKED) 0.25 * dtSec else 0.0
            atfStressScore = (energyScore + tempBump - decay).coerceIn(0.0, 100.0)
        }

        return snapshot(gear, engineRpm, expected, slipRpm, slipPercent, lockup)
    }

    private fun snapshot(
        gear: Int?,
        engineRpm: Double?,
        expectedRpm: Double?,
        slipRpm: Double?,
        slipPercent: Double?,
        lockup: Lockup
    ): Snapshot {
        val stress = when {
            atfStressScore >= 70.0 || (lastAtfTempC != null && lastAtfTempC!! >= atfSevereC) -> Stress.SEVERE
            atfStressScore >= 40.0 || (lastAtfTempC != null && lastAtfTempC!! >= atfElevatedC) -> Stress.ELEVATED
            atfStressScore >= 15.0 -> Stress.MILD
            else -> Stress.NONE
        }
        val message = when {
            lockup == Lockup.UNKNOWN -> "Converter state not available."
            lockup == Lockup.LOCKED && gear != null -> String.format(
                Locale.US,
                "G%d locked (slip %+.0f rpm, %.1f%%) — ATF %s.",
                gear, slipRpm ?: 0.0, slipPercent ?: 0.0, stress.name.lowercase(Locale.US)
            )
            lockup == Lockup.SLIP_CONTROLLED && gear != null -> String.format(
                Locale.US,
                "G%d slip-controlled converter (%+.0f rpm) — ATF thermal %s.",
                gear, slipRpm ?: 0.0, stress.name.lowercase(Locale.US)
            )
            lockup == Lockup.OPEN -> String.format(
                Locale.US,
                "Converter open / launching (slip %+.0f rpm) — ATF %s.",
                slipRpm ?: 0.0, stress.name.lowercase(Locale.US)
            )
            else -> "AQ250 converter monitoring."
        }
        return Snapshot(
            estimatedGear = gear,
            engineRpm = engineRpm,
            expectedRpm = expectedRpm,
            slipRpm = slipRpm,
            slipPercent = slipPercent,
            lockup = lockup,
            atfStress = stress,
            atfStressScore = atfStressScore,
            cumulativeSlipEnergyKj = cumulativeSlipEnergyKj,
            atfTempC = lastAtfTempC,
            message = message
        )
    }
}
