package com.example.analysis

import kotlin.math.abs

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

    /**
     * One display bucket: the mean of every sample inside the time slice (the readable
     * signal line), the raw extremes inside it (drawn as a faint envelope so volatility
     * survives the smoothing) and the slice centre timestamp (what the crosshair reads).
     */
    data class Bucket(
        val ts: Long,
        val avg: Double,
        val min: Double,
        val max: Double,
        val count: Int
    )

    /**
     * Time-slice averaging for readable trend lines (owner 2026-09-16: "graphs are not
     * good on the app see how bad they're"). Even-stride [downsample] bounds the point
     * count but still plots RAW 1 Hz values, so a 70-minute city drive renders as
     * spaghetti. Bucketizing averages each time slice: the line shows the SIGNAL and the
     * min/max envelope shows the VOLATILITY. Nothing is invented - every bucket is the
     * mean of the real samples in its window, and empty windows are skipped rather than
     * interpolated across.
     *
     * Pure JVM, unit tested in ChartSamplingTest.
     */
    fun bucketize(points: List<Pair<Long, Double>>, maxBuckets: Int): List<Bucket> {
        if (points.isEmpty() || maxBuckets < 1) return emptyList()
        val sorted = if (isSortedByTs(points)) points else points.sortedBy { it.first }
        val first = sorted.first().first
        val last = sorted.last().first
        if (last <= first) {
            val values = sorted.map { it.second }
            return listOf(Bucket(first, values.average(), values.min(), values.max(), values.size))
        }
        val bucketCount = minOf(maxBuckets.toLong(), last - first).toInt().coerceAtLeast(1)
        val width = (last - first + 1) / bucketCount.toDouble()
        val sums = DoubleArray(bucketCount)
        val mins = DoubleArray(bucketCount) { Double.MAX_VALUE }
        val maxs = DoubleArray(bucketCount) { -Double.MAX_VALUE }
        val counts = IntArray(bucketCount)
        for ((ts, value) in sorted) {
            val idx = ((ts - first) / width).toInt().coerceIn(0, bucketCount - 1)
            sums[idx] += value
            if (value < mins[idx]) mins[idx] = value
            if (value > maxs[idx]) maxs[idx] = value
            counts[idx]++
        }
        val out = ArrayList<Bucket>(bucketCount)
        for (i in 0 until bucketCount) {
            if (counts[i] == 0) continue // empty window: skipped, never interpolated
            out += Bucket(
                ts = first + ((i + 0.5) * width).toLong(),
                avg = sums[i] / counts[i],
                min = mins[i],
                max = maxs[i],
                count = counts[i]
            )
        }
        return out
    }

    private fun isSortedByTs(points: List<Pair<Long, Double>>): Boolean {
        for (i in 1 until points.size) {
            if (points[i].first < points[i - 1].first) return false
        }
        return true
    }
}

/**
 * Which axis a series is drawn against in the multi-signal trend chart
 * (owner 2026-09-16 pipeline task 1: "let me add multiple signals the same trend see the
 * behaviour w.r.t other signal"):
 *  - LEFT_AXIS: the first selected signal keeps the labelled left axis (full treatment:
 *    envelope, area, mean line, min/max markers);
 *  - RIGHT_AXIS: the second signal gets its own labelled right axis in its own unit;
 *  - FITTED: third and further signals are scaled to the plot height WITHOUT an axis -
 *    their true values are read via the crosshair bubble and the legend says "fit" so
 *    the scaling is never silently implied to be axis-true.
 */
enum class SeriesRole { LEFT_AXIS, RIGHT_AXIS, FITTED }

/** Axis assignment by selection order (first picked = primary). */
fun roleOfSeries(index: Int): SeriesRole = when (index) {
    0 -> SeriesRole.LEFT_AXIS
    1 -> SeriesRole.RIGHT_AXIS
    else -> SeriesRole.FITTED
}

/**
 * Padded y-domain (min, span) for a series: 8% head/foot room on real variation, and a
 * small absolute floor for flat signals so a constant line still draws mid-plot instead
 * of dividing by zero. Pure so it is testable and shared by every series.
 */
fun yDomain(min: Double, max: Double): Pair<Double, Double> {
    val spanRaw = max - min
    val pad = if (spanRaw > 0.001) spanRaw * 0.08 else maxOf(abs(max) * 0.05, 0.5)
    // OWNER BUG 2026-09-16 (parked-session screenshots): an all-zero speed series drew a
    // -0.5…+0.5 km/h axis and a gradient slab under the zero line. Physically
    // non-negative signals must never acquire a negative axis: when the data itself
    // never dips below zero, the padded domain is floored at zero (real negatives,
    // e.g. sub-zero coolant, keep theirs).
    val lo = if (min >= 0.0) (min - pad).coerceAtLeast(0.0) else min - pad
    val hi = max + pad
    return lo to (hi - lo).coerceAtLeast(1e-6)
}
