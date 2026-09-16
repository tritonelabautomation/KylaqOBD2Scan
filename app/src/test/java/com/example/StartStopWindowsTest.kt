package com.example

import com.example.analysis.StartStopAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stall WINDOW exposure (owner pipeline task 5): the analyzer already counted stalls;
 * downstream battery slicing needs their (start, end) timestamps. End must be the last
 * observation still proven stopped - never the restart instant.
 */
class StartStopWindowsTest {

    private fun obs(tsSec: Long, rpm: Double?, speed: Double? = 0.0) =
        StartStopAnalyzer.Observation(tsMs = tsSec * 1000L, rpm = rpm, speedKmh = speed, fuelLh = if (rpm != null && rpm > 300) 0.9 else 0.0)

    @Test
    fun `a real stall yields one window bracketing the stopped stretch`() {
        val observations = listOf(
            obs(0, 850.0), obs(2, 850.0), obs(4, 850.0),      // warm idle, standing
            obs(6, 0.0), obs(8, 0.0), obs(10, 0.0), obs(12, 0.0), obs(14, 0.0), // stalled 8 s
            obs(16, 900.0), obs(18, 900.0)                    // restarted
        )
        val s = StartStopAnalyzer.analyze(observations)
        assertEquals(1, s.stopEvents)
        assertEquals(1, s.stopWindows.size)
        val (start, end) = s.stopWindows[0]
        // Window opens at the observation that carried the engine-off state into the
        // interval and closes at the last proven-stopped observation, not the restart.
        assertEquals(4_000L, start)
        assertEquals(14_000L, end)
        assertTrue(end - start >= 8_000L)
    }

    @Test
    fun `two stalls yield two windows in trip order`() {
        val observations = listOf(
            obs(0, 850.0), obs(2, 850.0),
            obs(4, 0.0), obs(6, 0.0), obs(8, 0.0), obs(10, 0.0),   // stall 1
            obs(12, 900.0), obs(14, 900.0), obs(16, 900.0),
            obs(18, 0.0), obs(20, 0.0), obs(22, 0.0), obs(24, 0.0), // stall 2
            obs(26, 900.0)
        )
        val s = StartStopAnalyzer.analyze(observations)
        assertEquals(2, s.stopEvents)
        assertEquals(2, s.stopWindows.size)
        assertTrue(s.stopWindows[0].first < s.stopWindows[1].first)
    }

    @Test
    fun `a flicker below the minimum stop length produces no window`() {
        val observations = listOf(
            obs(0, 850.0), obs(2, 850.0),
            obs(4, 0.0), obs(5, 0.0),          // 1-2 s dropout: not a stall
            obs(6, 850.0), obs(8, 850.0)
        )
        val s = StartStopAnalyzer.analyze(observations)
        assertEquals(0, s.stopEvents)
        assertEquals(0, s.stopWindows.size)
    }

    @Test
    fun `a trip that ends while stalled still records its window`() {
        val observations = listOf(
            obs(0, 850.0), obs(2, 850.0),
            obs(4, 0.0), obs(6, 0.0), obs(8, 0.0), obs(10, 0.0), obs(12, 0.0) // parked, watchdog stopped recording
        )
        val s = StartStopAnalyzer.analyze(observations)
        assertEquals(1, s.stopEvents)
        assertEquals(1, s.stopWindows.size)
        assertEquals(12_000L, s.stopWindows[0].second) // closes at the last observation
    }

    @Test
    fun `link silence never fabricates window time`() {
        val observations = listOf(
            obs(0, 850.0), obs(2, 850.0),
            obs(4, 0.0), obs(6, 0.0), obs(8, 0.0), obs(10, 0.0),  // stalled
            obs(60, 900.0)                                         // 50 s Bluetooth gap
        )
        val s = StartStopAnalyzer.analyze(observations)
        assertEquals(1, s.stopWindows.size)
        // Gap-expiry closes the window at the last observation before the silence.
        assertEquals(10_000L, s.stopWindows[0].second)
    }
}
