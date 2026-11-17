package com.example.analysis

/**
 * DRIVE ANALYSIS sub-scores for the replicated OBDeleven trip-detail screen
 * ("Hard braking / Rapid acceleration / Speed variability" sliders + traffic split).
 * Pure over the stored speed series so it stays unit-testable; thresholds are
 * documented comfort/safety heuristics, not ECU claims.
 */
object TripDriveAnalysis {

    /** dv/dt thresholds in km/h per second. */
    const val HARD_BRAKE_KMH_PS = -3.0
    const val RAPID_ACCEL_KMH_PS = 2.5

    data class Result(
        val hardBrakingEvents: Int,
        val rapidAccelEvents: Int,
        /** stddev/mean of moving speeds (0 = perfectly steady). */
        val speedVariability: Double,
        /** 0..1 slider positions, 1 = best (Controlled / Steady). */
        val brakingControl: Double,
        val accelControl: Double,
        val speedSteadiness: Double,
        /** Minutes per traffic band, ordered Normal, Slow, Congested, Stopped. */
        val bandMinutes: List<Double>
    )

    /** [speedPoints] = timestampMs asc -> km/h (010D series). */
    fun analyse(
        speedPoints: List<Pair<Long, Double>>,
        idleSeconds: Double,
        speedHistogram: List<Pair<Int, Double>>
    ): Result {
        var brakes = 0
        var accels = 0
        for (i in 1 until speedPoints.size) {
            val dtSec = (speedPoints[i].first - speedPoints[i - 1].first) / 1000.0
            if (dtSec in 0.25..5.0) {
                val rate = (speedPoints[i].second - speedPoints[i - 1].second) / dtSec
                if (rate <= HARD_BRAKE_KMH_PS) brakes++
                if (rate >= RAPID_ACCEL_KMH_PS) accels++
            }
        }
        val moving = speedPoints.map { it.second }.filter { it > 1.0 }
        val mean = moving.average()
        val variability = if (mean > 1.0 && moving.size > 2) {
            val variance = moving.sumOf { (it - mean) * (it - mean) } / moving.size
            kotlin.math.sqrt(variance) / mean
        } else 0.0
        val hours = (speedPoints.lastOrNull()?.first?.let { last ->
            (last - (speedPoints.firstOrNull()?.first ?: last)) / 3_600_000.0
        } ?: 0.0).coerceAtLeast(0.01)
        val bandSec = mutableListOf(0.0, 0.0, 0.0, 0.0) // N, S, C, St
        for ((bin, sec) in speedHistogram) {
            when {
                bin < 20 -> bandSec[2] += sec
                bin < 50 -> bandSec[1] += sec
                else -> bandSec[0] += sec
            }
        }
        bandSec[3] = idleSeconds
        return Result(
            hardBrakingEvents = brakes,
            rapidAccelEvents = accels,
            speedVariability = variability,
            brakingControl = (1.0 - (brakes / hours) / 6.0).coerceIn(0.0, 1.0),
            accelControl = (1.0 - (accels / hours) / 6.0).coerceIn(0.0, 1.0),
            speedSteadiness = (1.0 - variability / 0.6).coerceIn(0.0, 1.0),
            bandMinutes = bandSec.map { it / 60.0 }
        )
    }

    /** "1 h 15 min" / "17 min" / "0 min" like the reference legend. */
    fun fmtMinutes(min: Double): String {
        val m = min.toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
