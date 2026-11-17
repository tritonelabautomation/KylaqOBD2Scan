package com.example

import com.example.data.PollingSpeedMode
import org.junit.Assert.*
import org.junit.Test

class PollingSpeedModeTest {

    @Test
    fun `verifies polling speed modes and fidelity properties`() {
        val safe = PollingSpeedMode.SAFE
        assertEquals("Safe", safe.displayName)
        assertEquals(2.0f, safe.multiplier, 0.001f)
        assertEquals(500, safe.targetDelayMs)
        assertTrue(safe.fuelAccuracyLoss.contains("±8–12%"))
        assertTrue(safe.transientPeakLoss.contains("40–60%"))
        assertTrue(safe.stabilityVerdict.contains("100% Stability"))

        val normal = PollingSpeedMode.NORMAL
        assertEquals("Normal", normal.displayName)
        assertEquals(1.0f, normal.multiplier, 0.001f)
        assertEquals(250, normal.targetDelayMs)
        assertTrue(normal.fuelAccuracyLoss.contains("< 2.5–3.5%"))
        assertTrue(normal.transientPeakLoss.contains("15–20%"))
        assertTrue(normal.stabilityVerdict.contains("High Stability"))

        val fast = PollingSpeedMode.FAST
        assertEquals("Fast", fast.displayName)
        assertEquals(0.5f, fast.multiplier, 0.001f)
        assertEquals(125, fast.targetDelayMs)
        assertTrue(fast.fuelAccuracyLoss.contains("< 1.0%"))
        assertTrue(fast.transientPeakLoss.contains("< 5%"))
        assertTrue(fast.stabilityVerdict.contains("Requires Capable Hardware"))
    }
}
