package com.example.engine

import java.util.Locale

/**
 * Cranking-voltage dip profiler and 12 V battery health scorer.
 *
 * PID 0142 (control-module voltage) at rest, during the starter engagement, and after the
 * alternator picks up is the honest 12 V health signal on this car:
 *
 *  * rest ≥ 12.6 V → fully charged open-circuit;
 *  * crank min ≥ 10.5 V → healthy CCA;
 *  * crank min 9.6–10.5 V → weakening;
 *  * crank min < 9.6 V → likely to strand on a hot restart.
 *
 * A crank event is RPM crossing 50→400 (starter) then > 500 (fired). Score is 0–100 from
 * rest (40) + crank min (50) + alternator recover (10).
 */
class BatteryCrankProfiler(
    private val restHealthyV: Double = 12.6,
    private val crankHealthyV: Double = 10.5,
    private val crankWeakV: Double = 9.6,
    private val rpmOff: Double = 50.0,
    private val rpmCrankingMax: Double = 450.0,
    private val rpmRunningMin: Double = 500.0,
    private val recoverHealthyV: Double = 13.6,
    private val maxEvents: Int = 40
) {

    enum class Phase { RESTING, CRANKING, RUNNING }

    data class CrankEvent(
        val startMs: Long,
        val restVoltageV: Double?,
        val minCrankVoltageV: Double,
        val recoverVoltageV: Double?,
        val crankDurationMs: Long,
        val healthScore: Int
    )

    data class Snapshot(
        val phase: Phase,
        val lastEvent: CrankEvent?,
        val eventCount: Int,
        val healthScore: Int,
        val restVoltageV: Double?,
        val minCrankVoltageV: Double?,
        val recoverVoltageV: Double?,
        val verdict: String,
        val message: String
    )

    private var phase: Phase = Phase.RESTING
    private var restVoltageV: Double? = null
    private var crankMinV: Double? = null
    private var recoverVoltageV: Double? = null
    private var crankStartMs: Long = 0L
    private var lastHealth: Int = 70
    private val events = ArrayDeque<CrankEvent>()

    fun reset() {
        phase = Phase.RESTING
        restVoltageV = null
        crankMinV = null
        recoverVoltageV = null
        crankStartMs = 0L
        lastHealth = 70
        events.clear()
    }

    fun recentEvents(): List<CrankEvent> = events.toList()

    fun onSample(
        timestampMonotonicMs: Long,
        rpm: Double?,
        voltageV: Double?
    ): Snapshot {
        val engineRpm = rpm ?: 0.0
        val volts = voltageV

        when (phase) {
            Phase.RESTING -> {
                if (volts != null && engineRpm < rpmOff) {
                    restVoltageV = blend(restVoltageV, volts, 0.35)
                }
                if (engineRpm in rpmOff..rpmCrankingMax) {
                    phase = Phase.CRANKING
                    crankStartMs = timestampMonotonicMs
                    crankMinV = volts
                } else if (engineRpm >= rpmRunningMin) {
                    phase = Phase.RUNNING
                    volts?.let { recoverVoltageV = it }
                }
            }
            Phase.CRANKING -> {
                if (volts != null) {
                    crankMinV = minOf(crankMinV ?: volts, volts)
                }
                if (engineRpm >= rpmRunningMin) {
                    phase = Phase.RUNNING
                    volts?.let { recoverVoltageV = it }
                    closeEvent(timestampMonotonicMs, recoverVoltageV)
                } else if (engineRpm < rpmOff) {
                    phase = Phase.RESTING
                    crankMinV = null
                }
            }
            Phase.RUNNING -> {
                if (volts != null && engineRpm >= rpmRunningMin) {
                    recoverVoltageV = blend(recoverVoltageV, volts, 0.2)
                }
                if (engineRpm < rpmOff) {
                    phase = Phase.RESTING
                    crankMinV = null
                } else if (engineRpm in rpmOff..rpmCrankingMax && (volts != null && volts < 11.5)) {
                    phase = Phase.CRANKING
                    crankStartMs = timestampMonotonicMs
                    crankMinV = volts
                }
            }
        }

        return snapshot()
    }

    private fun closeEvent(nowMs: Long, recoverV: Double?) {
        val minV = crankMinV ?: return
        val rest = restVoltageV
        val score = scoreOf(rest, minV, recoverV)
        lastHealth = score
        val event = CrankEvent(
            startMs = crankStartMs,
            restVoltageV = rest,
            minCrankVoltageV = minV,
            recoverVoltageV = recoverV,
            crankDurationMs = (nowMs - crankStartMs).coerceAtLeast(0L),
            healthScore = score
        )
        events.addLast(event)
        while (events.size > maxEvents) events.removeFirst()
    }

    fun scoreOf(restV: Double?, minCrankV: Double, recoverV: Double?): Int {
        val restPts = when {
            restV == null -> 20
            restV >= restHealthyV -> 40
            restV >= 12.4 -> 30
            restV >= 12.2 -> 20
            restV >= 12.0 -> 10
            else -> 0
        }
        val crankPts = when {
            minCrankV >= crankHealthyV -> 50
            minCrankV >= 10.0 -> 35
            minCrankV >= crankWeakV -> 20
            minCrankV >= 9.0 -> 8
            else -> 0
        }
        val recPts = when {
            recoverV == null -> 5
            recoverV >= recoverHealthyV -> 10
            recoverV >= 13.2 -> 6
            else -> 2
        }
        return (restPts + crankPts + recPts).coerceIn(0, 100)
    }

    private fun blend(previous: Double?, sample: Double, alpha: Double): Double =
        if (previous == null) sample else previous * (1.0 - alpha) + sample * alpha

    private fun snapshot(): Snapshot {
        val last = events.lastOrNull()
        val score = last?.healthScore ?: lastHealth
        val verdict = when {
            score >= 85 -> "HEALTHY"
            score >= 65 -> "FAIR"
            score >= 40 -> "WEAK"
            else -> "REPLACE SOON"
        }
        val minV = last?.minCrankVoltageV ?: crankMinV
        val message = when {
            last == null && phase == Phase.RESTING -> String.format(
                Locale.US,
                "Battery rest %s — crank a start to score CCA.",
                restVoltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—"
            )
            last != null -> String.format(
                Locale.US,
                "Last crank dipped to %.2f V (rest %s) — health %d/100 %s.",
                last.minCrankVoltageV,
                last.restVoltageV?.let { String.format(Locale.US, "%.2f V", it) } ?: "—",
                score,
                verdict
            )
            phase == Phase.CRANKING -> String.format(
                Locale.US,
                "Cranking… min %.2f V so far.",
                minV ?: 0.0
            )
            else -> "Battery crank profiler armed."
        }
        return Snapshot(
            phase = phase,
            lastEvent = last,
            eventCount = events.size,
            healthScore = score,
            restVoltageV = restVoltageV,
            minCrankVoltageV = minV,
            recoverVoltageV = last?.recoverVoltageV ?: recoverVoltageV,
            verdict = verdict,
            message = message
        )
    }
}
