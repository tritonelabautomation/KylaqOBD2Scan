package com.example

import com.example.analysis.VoltageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Voltage min/max recording reduction (owner pipeline task 3) - pure JVM. */
class VoltageStatsTest {

    @Test
    fun `no samples means no extremes - never invented`() {
        assertNull(VoltageStats.extremes(emptyList()))
    }

    @Test
    fun `single sample is both min and max at its own instant`() {
        val e = VoltageStats.extremes(listOf(500L to 14.2))!!
        assertEquals(14.2, e.minV, 1e-9)
        assertEquals(14.2, e.maxV, 1e-9)
        assertEquals(500L, e.minTs)
        assertEquals(500L, e.maxTs)
    }

    @Test
    fun `extremes carry the instants they happened at`() {
        val points = listOf(
            0L to 12.4,
            1_000L to 11.9,   // starter crank -> the min
            2_000L to 14.1,
            3_000L to 14.9,   // charging peak -> the max
            4_000L to 13.8
        )
        val e = VoltageStats.extremes(points)!!
        assertEquals(11.9, e.minV, 1e-9)
        assertEquals(1_000L, e.minTs)
        assertEquals(14.9, e.maxV, 1e-9)
        assertEquals(3_000L, e.maxTs)
    }

    @Test
    fun `order of samples does not matter`() {
        val a = VoltageStats.extremes(listOf(0L to 14.9, 1L to 11.9, 2L to 13.0))!!
        val b = VoltageStats.extremes(listOf(2L to 13.0, 0L to 14.9, 1L to 11.9))!!
        assertEquals(a, b)
    }
}
