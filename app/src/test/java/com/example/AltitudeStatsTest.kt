package com.example

import com.example.analysis.AltitudeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Per-trip GPS altitude window (owner 2026-09-15: "Why altitude is not taken from GPS
 * why it's empty in trip summary?"). GpsManager feeds this with accuracy-gated fixes;
 * RecordingManager persists min/max into trips.maxAltitudeM / minAltitudeM (DB v10);
 * the trip summary renders real metres — or an honest blank when nothing was recorded.
 */
class AltitudeStatsTest {

    @Test
    fun `empty stats stay null so the UI keeps its honest blank`() {
        val s = AltitudeStats()
        assertNull(s.minAltitudeM)
        assertNull(s.maxAltitudeM)
        assertNull(s.rangeM)
        assertEquals(0, s.sampleCount)
    }

    @Test
    fun `min max and range track a realistic trip profile`() {
        val s = AltitudeStats()
        listOf(542.1, 538.0, 561.4, 555.0, 570.9, 549.2).forEach { s.record(it) }
        assertEquals(538.0, s.minAltitudeM!!, 0.001)
        assertEquals(570.9, s.maxAltitudeM!!, 0.001)
        assertEquals(32.9, s.rangeM!!, 0.001)
        assertEquals(6, s.sampleCount)
    }

    @Test
    fun `single fix gives a zero range not null`() {
        val s = AltitudeStats()
        s.record(601.5)
        assertEquals(601.5, s.minAltitudeM!!, 0.001)
        assertEquals(601.5, s.maxAltitudeM!!, 0.001)
        assertEquals(0.0, s.rangeM!!, 0.001)
    }

    @Test
    fun `implausible sensor glitches are rejected`() {
        val s = AltitudeStats()
        s.record(-1200.0) // below the Dead Sea - glitch
        s.record(31_000.0) // cruise altitude, not a car - glitch
        assertNull(s.minAltitudeM)
        assertNull(s.maxAltitudeM)
        assertEquals(0, s.sampleCount)
        s.record(540.0) // a real fix still lands afterwards
        assertEquals(540.0, s.minAltitudeM!!, 0.001)
    }

    @Test
    fun `reset clears the window for the next trip`() {
        val s = AltitudeStats()
        s.record(100.0); s.record(250.0)
        s.reset()
        assertNull(s.minAltitudeM)
        assertNull(s.maxAltitudeM)
        assertNull(s.rangeM)
        assertEquals(0, s.sampleCount)
    }

    @Test
    fun `reduce rebuilds the window from persisted rows after a kill`() {
        // KILL-AUDIT FIX B: a recovered trip's LIVE accumulator is empty RAM, but every journaled
        // sample row carries its altitude_m - reducing the rows must give the same window STOP
        // would have written.
        val s = AltitudeStats.reduce(listOf(120.5, 98.0, 143.25, 110.0))
        assertNotNull(s)
        assertEquals(98.0, s!!.minAltitudeM!!, 0.001)
        assertEquals(143.25, s.maxAltitudeM!!, 0.001)
        assertEquals(45.25, s.rangeM!!, 0.001)
        assertEquals(4, s.sampleCount)
    }

    @Test
    fun `reduce of nothing or only glitches stays honestly null`() {
        assertNull(AltitudeStats.reduce(emptyList()))
        // Same plausibility gate as the live path: glitches alone must not invent a window.
        assertNull(AltitudeStats.reduce(listOf(-1200.0, 31_000.0)))
        val mixed = AltitudeStats.reduce(listOf(-1200.0, 540.0, 31_000.0))
        assertNotNull(mixed)
        assertEquals(540.0, mixed!!.minAltitudeM!!, 0.001)
        assertEquals(540.0, mixed.maxAltitudeM!!, 0.001)
    }
}
