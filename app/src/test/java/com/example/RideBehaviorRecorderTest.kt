package com.example

import com.example.analysis.RideBehaviorRecorder
import com.example.analysis.RideCodec
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM tests for the per-ride behaviour recorder and its codec. */
class RideBehaviorRecorderTest {

    private fun feed(rec: RideBehaviorRecorder) {
        // 0-2s ACCELERATING in gear 2, 2-4s CRUISING in 3, 4-6s COASTING in 3,
        // rpm drop at t=2s looks like the 2->3 upshift at 3200 rpm.
        rec.onSample(0L, "ACCELERATING", 2500.0, 30.0, 2, 100.0)
        rec.onSample(1000L, "ACCELERATING", 2600.0, 38.0, 2, 100.5)
        rec.onSample(2000L, "CRUISING", 1900.0, 42.0, 3, 101.0)
        rec.onSample(3000L, "CRUISING", 1950.0, 43.0, 3, 104.0)
        rec.onSample(4000L, "COASTING", 1700.0, 40.0, 3, 108.0)
        rec.onSample(5000L, "COASTING", 1500.0, 36.0, 3, 103.0)
    }

    @Test
    fun `state seconds gears elevation and shift are accumulated`() {
        val rec = RideBehaviorRecorder()
        feed(rec)
        val s = rec.summary("2026-09-08T10:00:00Z")
        assertEquals(5.0, s.durationSec, 0.01)
        assertEquals(2.0, s.stateSeconds["ACCELERATING"]!!, 0.01)
        assertEquals(2.0, s.stateSeconds["CRUISING"]!!, 0.01)
        assertEquals(1.0, s.stateSeconds["COASTING"]!!, 0.01)
        assertEquals(2.0, s.gearSeconds[2], 0.01)
        assertEquals(3.0, s.gearSeconds[3], 0.01)
        // +0.5 +3.0 +4.0 gated at 1m -> 0.5 rejected, 3+4 counted
        assertEquals(7.0, s.elevationGainM, 0.01)
        assertEquals(5.0, s.elevationLossM, 0.01)
        assertEquals(1, s.shiftCount)
        assertEquals(2600.0, s.avgUpshiftRpm!!, 0.01) // D-map-like shift point
        assertFalse(s.sportLikeShiftMap)
    }

    @Test
    fun `sport-like shift map flag triggers above 3000 rpm`() {
        val rec = RideBehaviorRecorder()
        rec.onSample(0L, "ACCELERATING", 3000.0, 30.0, 2, null)
        rec.onSample(1000L, "ACCELERATING", 4200.0, 40.0, 2, null)
        rec.onSample(2000L, "CRUISING", 2400.0, 45.0, 3, null)
        val s = rec.summary("x")
        assertEquals(4200.0, s.avgUpshiftRpm!!, 0.01)
        assertTrue(s.sportLikeShiftMap)
    }

    @Test
    fun `codec round trip preserves the x-ray`() {
        val rec = RideBehaviorRecorder()
        rec.modeTag = RideBehaviorRecorder.ModeTag.S
        feed(rec)
        val decoded = RideCodec.decode(RideCodec.encode(rec.summary("2026-09-08T10:00:00Z")))!!
        assertEquals("S", decoded.modeTag)
        assertEquals(5.0, decoded.durationSec, 0.01)
        assertEquals(2.0, decoded.stateSeconds["ACCELERATING"]!!, 0.01)
        assertEquals(3.0, decoded.gearSeconds[3], 0.01)
        assertEquals(7.0, decoded.elevationGainM, 0.01)
        assertEquals(1, decoded.shiftCount)
        assertNull(RideCodec.decode("garbage"))
        assertNull(RideCodec.decode("r1|a|b"))
    }
}
