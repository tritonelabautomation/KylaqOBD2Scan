package com.example.analysis

/**
 * Battery voltage extremes of a trip (owner pipeline task 3, 2026-09-16: "Voltage min max
 * recording"). Pure so the same reduction serves the live recorder, the trip card and the
 * tests: minimum and maximum of the REAL 0142 samples, with the instants they occurred -
 * an 11.9 V min at 07:26 is a starter crank, not a dying battery, and the timestamp is
 * what tells those apart. Null when the trip has no voltage samples: extremes are
 * recorded, never invented (no-fake-values rule).
 */
data class VoltageExtremes(
    val minV: Double,
    val minTs: Long,
    val maxV: Double,
    val maxTs: Long
)

object VoltageStats {
    fun extremes(points: List<Pair<Long, Double>>): VoltageExtremes? {
        if (points.isEmpty()) return null
        val min = points.minBy { it.second }
        val max = points.maxBy { it.second }
        return VoltageExtremes(
            minV = min.second,
            minTs = min.first,
            maxV = max.second,
            maxTs = max.first
        )
    }
}
