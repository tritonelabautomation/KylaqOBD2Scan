package com.example

import com.example.analysis.ChartSampling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-09-17: "showing 1.5 gear ... No proper implementation".
 * Discrete signals must bucket by MODE - never fractional gears.
 */
class DiscreteBucketTest {

    private fun pts(vararg v: Double) = v.mapIndexed { i, d -> (1_000L + i * 1_000L) to d }

    @Test
    fun bucketsAreModesNotMeans() {
        // mean of [1,1,2] = 1.33; mode = 1
        val b = ChartSampling.bucketizeDiscrete(pts(1.0, 1.0, 2.0, 2.0, 2.0, 3.0), 2)
        b.forEach { bucket ->
            assertEquals("bucket value must be a whole gear", bucket.avg, Math.round(bucket.avg).toDouble(), 1e-9)
        }
        assertEquals(1.0, b.first().avg, 1e-9)
        assertEquals(2.0, b.last().avg, 1e-9)
    }

    @Test
    fun singleBucketTakesMode() {
        val b = ChartSampling.bucketizeDiscrete(pts(3.0, 3.0, 4.0), 1).single()
        assertEquals(3.0, b.avg, 1e-9)
    }

    @Test
    fun briefPhaseSurvivesFullSpanZoom() {
        // 10-minute span, one 3-second 2nd-gear window inside a sea of 3rd:
        // 24 s mean-buckets used to erase it; the 2 s cap must keep it.
        val pts = mutableListOf<Pair<Long, Double>>()
        var ts = 0L
        repeat(200) { pts += ts to 3.0; ts += 3_000 }
        val phaseStart = 300_000L
        pts += phaseStart to 2.0
        pts += phaseStart + 1_000 to 2.0
        pts += phaseStart + 2_000 to 2.0
        val buckets = ChartSampling.bucketizeDiscrete(pts.sortedBy { it.first }, 180)
        assertTrue("a 2-gear bucket must survive: ${buckets.distinctBy { it.avg }.map { it.avg }}",
            buckets.any { it.avg == 2.0 })
    }

    @Test
    fun emptyStaysEmpty() {
        assertTrue(ChartSampling.bucketizeDiscrete(emptyList(), 4).isEmpty())
    }
}
