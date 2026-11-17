package com.example.engine

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Turbo heat-soak & idle-cooldown guardian for the EA211 1.0 TSI wastegated turbo.
 *
 * After a high-boost / high-load stint the turbine housing and the oil in the bearings are
 * far hotter than coolant. Killing the engine immediately lets residual heat soak into the
 * bearings (coking risk). This engine:
 *
 *  * integrates a 0–100 heat score from boost, RPM, load and charge-air temperature;
 *  * recommends an idle cooldown window when the car comes to a stop while still hot;
 *  * counts remaining idle seconds as the score decays at idle;
 *  * flags [State.HEAT_SOAK_RISK] if the engine is killed while the turbo is still hot.
 *
 * Pure JVM — no Android APIs.
 */
class TurboCooldownGuardian(
    private val hotBoostKpa: Double = 80.0,
    private val hotRpm: Double = 3500.0,
    private val stoppedSpeedKmh: Double = 3.0,
    private val idleRpmMin: Double = 400.0,
    private val idleRpmMax: Double = 1200.0,
    private val engineOffRpm: Double = 200.0,
    private val maxCooldownSec: Double = 90.0,
    private val minCooldownSec: Double = 20.0,
    private val heatDecayPerIdleSec: Double = 1.8,
    private val heatDecayPerMovingSec: Double = 0.35,
    private val hotThreshold: Double = 40.0,
    private val safeThreshold: Double = 15.0
) {

    enum class State {
        COLD,
        WARM,
        HOT,
        COOLDOWN_RECOMMENDED,
        COOLING,
        SAFE_TO_SHUTDOWN,
        HEAT_SOAK_RISK
    }

    data class Snapshot(
        val state: State,
        val heatScore: Double,
        val recommendedIdleSec: Int,
        val remainingIdleSec: Int,
        val peakBoostKpa: Double,
        val chargeTempC: Double?,
        val heatSoakRisk: Boolean,
        val message: String
    )

    private var lastTs: Long = 0L
    private var heatScore: Double = 0.0
    private var peakBoostKpa: Double = 0.0
    private var lastChargeTempC: Double? = null
    private var recommendedIdleSec: Int = 0
    private var heatSoakRisk: Boolean = false
    private var wasCooling: Boolean = false

    fun reset() {
        lastTs = 0L
        heatScore = 0.0
        peakBoostKpa = 0.0
        lastChargeTempC = null
        recommendedIdleSec = 0
        heatSoakRisk = false
        wasCooling = false
    }

    fun onSample(
        timestampMonotonicMs: Long,
        speedKmh: Double?,
        rpm: Double?,
        boostKpa: Double?,
        loadPct: Double? = null,
        chargeTempC: Double? = null,
        coolantC: Double? = null
    ): Snapshot {
        val dtSec = if (lastTs > 0L) {
            ((timestampMonotonicMs - lastTs).coerceIn(0L, 5000L)) / 1000.0
        } else {
            0.0
        }
        lastTs = timestampMonotonicMs

        chargeTempC?.let { lastChargeTempC = it }
        val boost = boostKpa ?: 0.0
        if (boost > peakBoostKpa) peakBoostKpa = boost

        val speed = speedKmh ?: 0.0
        val engineRpm = rpm ?: 0.0
        val stopped = speed < stoppedSpeedKmh
        val engineOff = engineRpm < engineOffRpm
        val idling = !engineOff && engineRpm in idleRpmMin..idleRpmMax && stopped

        if (dtSec > 0.0) {
            val boostHeat = (boost / hotBoostKpa).coerceAtLeast(0.0) * 1.4
            val rpmHeat = if (engineRpm > hotRpm) {
                ((engineRpm - hotRpm) / 2000.0).coerceIn(0.0, 1.5) * 0.9
            } else 0.0
            val loadHeat = ((loadPct ?: 0.0) / 100.0).coerceIn(0.0, 1.2) * 0.45
            val chargeHeat = lastChargeTempC?.let { temp ->
                if (temp > 60.0) ((temp - 60.0) / 40.0).coerceIn(0.0, 1.5) * 0.6 else 0.0
            } ?: 0.0
            val coolantHeat = coolantC?.let { c ->
                if (c > 105.0) ((c - 105.0) / 15.0).coerceIn(0.0, 1.0) * 0.4 else 0.0
            } ?: 0.0

            heatScore += (boostHeat + rpmHeat + loadHeat + chargeHeat + coolantHeat) * dtSec

            when {
                idling -> heatScore -= heatDecayPerIdleSec * dtSec
                !stopped && boost < 15.0 -> heatScore -= heatDecayPerMovingSec * dtSec
            }
            heatScore = heatScore.coerceIn(0.0, 100.0)
        }

        if (engineOff && heatScore >= hotThreshold) {
            heatSoakRisk = true
        }

        val remaining = if (heatScore <= safeThreshold) {
            0
        } else {
            ceil((heatScore - safeThreshold) / heatDecayPerIdleSec).toInt().coerceAtLeast(1)
        }
        val recommended = (minCooldownSec + (maxCooldownSec - minCooldownSec) * (heatScore / 100.0))
            .toInt()
            .coerceIn(minCooldownSec.toInt(), maxCooldownSec.toInt())

        val state = when {
            heatSoakRisk && engineOff -> State.HEAT_SOAK_RISK
            engineOff && heatScore < hotThreshold -> {
                wasCooling = false
                if (heatScore < safeThreshold) State.COLD else State.WARM
            }
            stopped && !engineOff && heatScore >= hotThreshold -> {
                if (!wasCooling) {
                    recommendedIdleSec = max(recommendedIdleSec, recommended)
                    wasCooling = true
                    State.COOLDOWN_RECOMMENDED
                } else {
                    State.COOLING
                }
            }
            wasCooling && !engineOff && heatScore < safeThreshold -> {
                wasCooling = false
                State.SAFE_TO_SHUTDOWN
            }
            wasCooling && !engineOff -> State.COOLING
            heatScore >= hotThreshold -> State.HOT
            heatScore >= safeThreshold -> State.WARM
            else -> State.COLD
        }

        if (state == State.COOLDOWN_RECOMMENDED && recommendedIdleSec == 0) {
            recommendedIdleSec = recommended
        }

        val message = when (state) {
            State.COLD -> "Turbo cold — no cooldown needed."
            State.WARM -> String.format(Locale.US, "Turbo warm (heat %.0f/100).", heatScore)
            State.HOT -> String.format(
                Locale.US,
                "Turbo hot (heat %.0f/100, peak boost %.0f kPa). Idle before shutdown.",
                heatScore, peakBoostKpa
            )
            State.COOLDOWN_RECOMMENDED -> String.format(
                Locale.US,
                "Idle %d s before shutdown — turbo heat soak risk.",
                remaining.coerceAtLeast(recommendedIdleSec)
            )
            State.COOLING -> String.format(
                Locale.US,
                "Cooling… %d s remaining (heat %.0f/100).",
                remaining, heatScore
            )
            State.SAFE_TO_SHUTDOWN -> "Turbo cooled — safe to shut down."
            State.HEAT_SOAK_RISK -> "Engine off while turbo was hot — heat-soak risk on bearings."
        }

        return Snapshot(
            state = state,
            heatScore = heatScore,
            recommendedIdleSec = if (recommendedIdleSec > 0) recommendedIdleSec else recommended,
            remainingIdleSec = remaining,
            peakBoostKpa = peakBoostKpa,
            chargeTempC = lastChargeTempC,
            heatSoakRisk = heatSoakRisk,
            message = message
        )
    }
}
