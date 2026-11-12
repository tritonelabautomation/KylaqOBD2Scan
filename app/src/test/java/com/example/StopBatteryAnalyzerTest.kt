package com.example

import com.example.analysis.AcVoltageDetector
import com.example.analysis.StopBatteryAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery picture during start-stop stalls (owner pipeline task 5) - pure JVM.
 * The analyzer must slice the REAL stall windows, keep the charging baseline separate
 * and refuse to claim anything without evidence.
 */
class StopBatteryAnalyzerTest {

    private val window = 60_000L to 90_000L // a 30 s stall

    @Test
    fun `no stalls or no voltage means no evidence - nothing claimed`() {
        assertFalse(StopBatteryAnalyzer.analyze(emptyList(), listOf(0L to 14.2), listOf(0L to 14.2), emptyList()).hasEvidence)
        assertFalse(StopBatteryAnalyzer.analyze(listOf(window), emptyList(), emptyList(), emptyList()).hasEvidence)
    }

    @Test
    fun `stall samples are sliced mean and min, baseline stays separate`() {
        val voltage = listOf(
            10_000L to 14.3,          // running
            61_000L to 12.8,          // in stall
            70_000L to 12.4,          // in stall - blower load sags it
            80_000L to 12.6,          // in stall
            120_000L to 14.5          // running again
        )
        val running = listOf(10_000L to 14.3, 120_000L to 14.5)
        val r = StopBatteryAnalyzer.analyze(listOf(window), voltage, running, emptyList())
        assertTrue(r.hasEvidence)
        assertEquals(3, r.samplesInStops)
        assertEquals((12.8 + 12.4 + 12.6) / 3.0, r.meanVInStops!!, 1e-9)
        assertEquals(12.4, r.minVInStops!!, 1e-9)
        assertEquals(14.4, r.meanVRunning!!, 1e-9)
        assertEquals(14.4 - (12.8 + 12.4 + 12.6) / 3.0, r.depressionV!!, 1e-9)
        assertEquals(1, r.stopsWithVoltage)
    }

    @Test
    fun `samples outside every stall window never leak into stall stats`() {
        val voltage = listOf(0L to 9.0, 61_000L to 12.5, 62_000L to 12.5, 63_000L to 12.5, 200_000L to 9.0)
        val r = StopBatteryAnalyzer.analyze(listOf(window), voltage, listOf(0L to 14.0), emptyList())
        assertEquals(12.5, r.meanVInStops!!, 1e-9)
        assertEquals(12.5, r.minVInStops!!, 1e-9)
    }

    @Test
    fun `stalls overlapping measured AC-on segments are counted`() {
        val segments = listOf(
            AcVoltageDetector.Segment(0L, 70_000L, acOn = false),
            AcVoltageDetector.Segment(70_000L, 200_000L, acOn = true)
        )
        val stalls = listOf(30_000L to 40_000L, 80_000L to 100_000L)
        val voltage = listOf(81_000L to 12.6, 82_000L to 12.5, 83_000L to 12.4)
        val r = StopBatteryAnalyzer.analyze(stalls, voltage, listOf(0L to 14.2), segments)
        assertEquals(2, r.acOnStopsTotal)
        assertEquals(1, r.acOnStops) // only the second stall sits inside the ON segment
    }

    @Test
    fun `two stall samples are below the evidence floor`() {
        val voltage = listOf(61_000L to 12.8, 62_000L to 12.7)
        val r = StopBatteryAnalyzer.analyze(listOf(window), voltage, listOf(0L to 14.2), emptyList())
        assertEquals(2, r.samplesInStops)
        assertFalse(r.hasEvidence)
        assertNull(r.depressionV?.takeIf { r.hasEvidence })
    }

    @Test
    fun `window edges are inclusive for stall slicing`() {
        val voltage = listOf(60_000L to 12.9, 75_000L to 12.6, 90_000L to 12.3)
        val r = StopBatteryAnalyzer.analyze(listOf(window), voltage, listOf(0L to 14.2), emptyList())
        assertEquals(3, r.samplesInStops)
        assertEquals(12.3, r.minVInStops!!, 1e-9)
    }
}
