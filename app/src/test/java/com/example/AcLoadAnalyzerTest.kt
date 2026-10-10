package com.example

import com.example.analysis.AcLoadAnalyzer
import com.example.analysis.AcVoltageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-load impact of the measured AC state (owner pipeline task 4) - pure JVM.
 * Segments are the detector's measured regimes; the analyzer must attribute observations
 * exactly, compare honestly and refuse thin regimes.
 */
class AcLoadAnalyzerTest {

    private fun seg(startSec: Long, endSec: Long, on: Boolean) =
        AcVoltageDetector.Segment(startTs = startSec * 1000L, endTs = endSec * 1000L, acOn = on)

    @Test
    fun `no measured segments means no comparison - nothing fabricated`() {
        val c = AcLoadAnalyzer.compare(emptyList(), listOf(0L to 20.0), emptyList(), emptyList())
        assertFalse(c.isMeaningful)
        assertNull(c.loadDeltaPct)
        assertEquals(0, c.acOn.loadSamples)
        assertEquals(0, c.acOff.loadSamples)
    }

    @Test
    fun `clean off-on split compares the regime means`() {
        // OFF 0..60 s, ON 60..120 s; 6 load samples per regime, one per 10 s.
        val segments = listOf(seg(0, 60, false), seg(60, 120, true))
        val off = (0..5).map { it * 10_000L to (10.0 + it) }      // mean 12.5
        val on = (0..5).map { 60_000L + it * 10_000L to (20.0 + it) } // mean 22.5
        val c = AcLoadAnalyzer.compare(segments, off + on, emptyList(), emptyList())
        assertTrue(c.isMeaningful)
        assertEquals(12.5, c.acOff.meanLoadPct!!, 1e-9)
        assertEquals(22.5, c.acOn.meanLoadPct!!, 1e-9)
        assertEquals(10.0, c.loadDeltaPct!!, 1e-9)
        assertEquals(60.0, c.acOff.seconds, 1e-9)
        assertEquals(60.0, c.acOn.seconds, 1e-9)
    }

    @Test
    fun `attribution boundaries - start inclusive, mid ends exclusive, trip end inclusive`() {
        val segments = listOf(seg(0, 60, false), seg(60, 120, true))
        val loads = listOf(
            0L to 1.0,        // first instant of OFF
            59_999L to 1.0,   // last instant of OFF
            60_000L to 2.0,   // boundary belongs to ON, not OFF
            120_000L to 2.0   // final segment owns its endTs
        )
        val c = AcLoadAnalyzer.compare(segments, loads, emptyList(), emptyList())
        assertEquals(2, c.acOff.loadSamples)
        assertEquals(2, c.acOn.loadSamples)
        assertEquals(1.0, c.acOff.meanLoadPct!!, 1e-9)
        assertEquals(2.0, c.acOn.meanLoadPct!!, 1e-9)
    }

    @Test
    fun `observations outside every segment are ignored`() {
        val segments = listOf(seg(10, 20, true), seg(30, 40, false))
        val loads = listOf(0L to 99.0, 25_000L to 99.0, 50_000L to 99.0, 15_000L to 30.0)
        val c = AcLoadAnalyzer.compare(segments, loads, emptyList(), emptyList())
        assertEquals(1, c.acOn.loadSamples)
        assertEquals(0, c.acOff.loadSamples)
        assertNull(c.loadDeltaPct) // one regime empty -> no delta
        assertFalse(c.isMeaningful)
    }

    @Test
    fun `thin regimes compute means but are NOT meaningful`() {
        val segments = listOf(seg(0, 60, false), seg(60, 120, true))
        val loads = listOf(10_000L to 10.0, 20_000L to 12.0, 70_000L to 20.0, 80_000L to 24.0)
        val c = AcLoadAnalyzer.compare(segments, loads, emptyList(), emptyList())
        assertEquals(22.0 - 11.0, c.loadDeltaPct!!, 1e-9)
        assertFalse("2 samples per regime is a guess, not a comparison", c.isMeaningful)
    }

    @Test
    fun `rpm and fuel ride along independently - missing channels stay null`() {
        val segments = listOf(seg(0, 60, false), seg(60, 120, true))
        val loads = (0..5).map { it * 10_000L to 10.0 } + (0..5).map { 60_000L + it * 10_000L to 20.0 }
        val rpms = (0..5).map { it * 10_000L to 800.0 } + (0..5).map { 60_000L + it * 10_000L to 950.0 }
        val c = AcLoadAnalyzer.compare(segments, loads, rpms, emptyList())
        assertEquals(150.0, c.rpmDelta!!, 1e-9)
        assertNull("no fuel channel answered -> no fuel delta invented", c.fuelDeltaLh)
        assertEquals(10.0, c.loadDeltaPct!!, 1e-9)
    }

    @Test
    fun `regime seconds sum across multiple segments of the same state`() {
        val segments = listOf(seg(0, 30, true), seg(30, 90, false), seg(90, 120, true))
        val c = AcLoadAnalyzer.compare(segments, emptyList(), emptyList(), emptyList())
        assertEquals(60.0, c.acOn.seconds, 1e-9)
        assertEquals(60.0, c.acOff.seconds, 1e-9)
    }
}
