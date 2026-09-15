package com.example

import com.example.analysis.ChartSampling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time-slice bucketizing for the redesigned trend charts (owner 2026-09-16: "graphs are
 * not good on the app see how bad they're"). The old canvas connected raw 1 Hz samples,
 * so city drives drew as spaghetti; the new renderer plots per-slice MEANS with a raw
 * min/max envelope. These tests pin the honesty rules of that smoothing:
 *  - every bucket value is the mean of the REAL samples in its window (the count-weighted
 *    mean of the buckets equals the mean of the raw series);
 *  - raw extremes survive inside the envelope (bucket mins/maxs reproduce series min/max);
 *  - gaps are skipped, never interpolated across;
 *  - degenerate input (empty / single timestamp / unsorted) behaves, never crashes.
 */
class ChartSamplingTest {

    @Test
    fun `empty input produces no buckets`() {
        assertEquals(emptyList<ChartSampling.Bucket>(), ChartSampling.bucketize(emptyList(), 100))
    }

    @Test
    fun `single timestamp collapses into one honest bucket`() {
        val points = listOf(5L to 1.0, 5L to 3.0, 5L to 2.0)

        val buckets = ChartSampling.bucketize(points, 100)

        assertEquals(1, buckets.size)
        assertEquals(2.0, buckets[0].avg, 1e-9)
        assertEquals(1.0, buckets[0].min, 1e-9)
        assertEquals(3.0, buckets[0].max, 1e-9)
        assertEquals(3, buckets[0].count)
    }

    @Test
    fun `uniform series bucketizes to exact slice means`() {
        val points = (0 until 100).map { it.toLong() to it.toDouble() }

        val buckets = ChartSampling.bucketize(points, 10)

        assertEquals(10, buckets.size)
        buckets.forEachIndexed { i, b ->
            assertEquals("slice $i mean", i * 10 + 4.5, b.avg, 1e-9)
            assertEquals("slice $i min", (i * 10).toDouble(), b.min, 1e-9)
            assertEquals("slice $i max", (i * 10 + 9).toDouble(), b.max, 1e-9)
            assertEquals(10, b.count)
        }
        // bucket centres sit in the middle of their slice, strictly increasing
        assertTrue(buckets.zipWithNext().all { (a, b) -> a.ts < b.ts })
        assertTrue(buckets[0].ts in 0..9)
    }

    @Test
    fun `unsorted input is ordered by time before slicing`() {
        val shuffled = listOf(30L to 30.0, 10L to 10.0, 40L to 40.0, 20L to 20.0)

        val buckets = ChartSampling.bucketize(shuffled, 4)

        assertEquals(4, buckets.size)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), buckets.map { it.avg })
    }

    @Test
    fun `a data gap is skipped, never interpolated across`() {
        val points =
            (0 until 10).map { it.toLong() to 10.0 } +          // cluster A ~10
                (1000 until 1010).map { it.toLong() to 90.0 }   // cluster B ~90

        val buckets = ChartSampling.bucketize(points, 50)

        assertTrue("gap must not create buckets", buckets.size < 50)
        assertTrue(buckets.isNotEmpty())
        // every bucket belongs entirely to one cluster - no bridging averages ~50
        buckets.forEach { b ->
            assertTrue("bucket avg ${b.avg} bridges the gap", b.avg < 20.0 || b.avg > 80.0)
            assertTrue(b.min == b.max) // single-valued clusters stay single-valued
        }
    }

    @Test
    fun `bucket count never exceeds the time span in milliseconds`() {
        val points = (0..5).map { it.toLong() to it.toDouble() }

        val buckets = ChartSampling.bucketize(points, 500)

        assertTrue("buckets ${buckets.size}", buckets.size <= 6)
    }

    @Test
    fun `smoothing conserves total fuel-like integrals and extremes`() {
        // sawtooth: heavy 1 Hz noise over a slow ramp - exactly the owner's load trace
        val points = (0 until 1200).map { ts ->
            ts.toLong() to (ts / 1200.0 * 60.0 + if (ts % 2 == 0) 15.0 else -15.0)
        }

        val buckets = ChartSampling.bucketize(points, 180)

        val rawMean = points.map { it.second }.average()
        val weightedMean = buckets.sumOf { it.avg * it.count } / buckets.sumOf { it.count }
        assertEquals("smoothing must not shift the mean", rawMean, weightedMean, 1e-6)

        assertEquals(points.minOf { it.second }, buckets.minOf { it.min }, 1e-9)
        assertEquals(points.maxOf { it.second }, buckets.maxOf { it.max }, 1e-9)

        // the envelope is strictly wider than or equal to the mean line everywhere
        buckets.forEach { b ->
            assertTrue(b.min <= b.avg && b.avg <= b.max)
        }
        assertEquals(1200, buckets.sumOf { it.count })
    }

    @Test
    fun `downsample still bounds raw point counts exactly`() {
        val values = (0 until 5000).toList()

        val sampled = ChartSampling.downsample(values, 600)

        assertEquals(600, sampled.size)
        // strided subsequence: strictly increasing, no duplicates, ends preserved
        assertEquals(sampled.size, sampled.distinct().size)
        assertTrue(sampled.zipWithNext().all { (a, b) -> a < b })
        assertEquals(values.first(), sampled.first())
        assertEquals(values.last(), sampled.last())
        // input shorter than the budget is returned untouched
        assertEquals(values.take(10), ChartSampling.downsample(values.take(10), 600))
    }
}
