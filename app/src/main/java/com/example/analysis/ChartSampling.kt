package com.example.analysis

/**
 * Even-stride downsampling for charts.
 *
 * 2026-09-13 (owner: "Trends are not proper"): trip recordings hold thousands of
 * telemetry samples per PID; feeding all of them into a canvas path makes the trend
 * heavy to draw and visually unreadable. Charts downsample to a bounded number of
 * evenly spaced points instead - first and last samples are ALWAYS kept so the
 * plotted window never lies about its start/end.
 *
 * Pure JVM, no Android deps - unit tested in DashboardUpdateConsistencyTest.
 */
object ChartSampling {

    /**
     * Returns [values] unchanged when it already fits in [maxPoints]; otherwise an
     * evenly strided subsequence of at most [maxPoints] entries including the first
     * and the last element.
     */
    fun <T> downsample(values: List<T>, maxPoints: Int): List<T> {
        if (maxPoints < 3 || values.size <= maxPoints) return values
        // Integer index math on purpose: a float-stride loop (`i += stride`) can
        // squeeze one extra sample past the budget at fp rounding edges (CI caught
        // 601 > 600 on 5000 inputs). This form yields EXACTLY maxPoints strictly
        // increasing indices, always including the first and the last sample.
        val out = ArrayList<T>(maxPoints)
        val last = values.size - 1
        for (k in 0 until maxPoints - 1) {
            out += values[(k * last) / (maxPoints - 1)]
        }
        out += values[last]
        return out
    }
}
