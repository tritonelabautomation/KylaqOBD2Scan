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
    fun emptyStaysEmpty() {
        assertTrue(ChartSampling.bucketizeDiscrete(emptyList(), 4).isEmpty())
    }
}
