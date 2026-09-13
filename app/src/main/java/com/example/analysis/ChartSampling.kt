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
        val out = ArrayList<T>(maxPoints)
        val stride = (values.size - 1).toDouble() / (maxPoints - 1)
        var i = 0.0
        while (i < values.size - 1.0) {
            out += values[i.toInt()]
            i += stride
        }
        out += values.last()
        return out
    }
}
