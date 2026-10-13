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

    @Test
    fun `converter locked and slip seconds follow the deviation model`() {
        val rec = RideBehaviorRecorder()
        rec.onSample(0L, "CRUISING", 2200.0, 100.0, 5, null, slipRpm = -33.0, converterLocked = true)
        rec.onSample(1000L, "CRUISING", 2200.0, 100.0, 5, null, slipRpm = -33.0, converterLocked = true)
        rec.onSample(2000L, "ACCELERATING", 2900.0, 100.0, null, null, slipRpm = 667.0, converterLocked = false)
        rec.onSample(3000L, "ACCELERATING", 3200.0, 110.0, null, null, slipRpm = 800.0, converterLocked = false)
        val s = rec.summary("x")
        // Intervals [0-1s] and [1-2s] ran locked; interval [2-3s] ran slipping (prev-sample credit).
        assertEquals(2.0, s.converterLockedSec, 0.01)
        assertEquals(1.0, s.converterSlipSec, 0.01)
        assertEquals(667.0, s.maxSlipRpm!!, 0.01)
        assertEquals(1.0 / 3.0, s.converterSlipShare, 0.01)
    }

    @Test
    fun `codec r3 round trips converter health and legacy r1 still decodes`() {
        val rec = RideBehaviorRecorder()
        rec.onSample(0L, "CRUISING", 2200.0, 100.0, 5, null, slipRpm = 900.0, converterLocked = false)
        rec.onSample(1000L, "CRUISING", 2200.0, 100.0, 5, null, slipRpm = -30.0, converterLocked = true)
        val encoded = RideCodec.encode(rec.summary("2026-09-08T10:00:00Z"))
        assertTrue(encoded.startsWith("r3|"))
        val decoded = RideCodec.decode(encoded)!!
        assertEquals(1.0, decoded.converterSlipSec, 0.01)
        assertEquals(0.0, decoded.converterLockedSec, 0.01)
        assertEquals(900.0, decoded.maxSlipRpm!!, 0.01)
        // Legacy 13-field r1 lines (already stored in ride_log) decode with converter defaults.
        val legacy = "r1|2026-01-01T00:00:00Z|600|12.50|CRUISING=600|0,600,0,0,0,0|0|0|0|-|-|-|D"
        val l = RideCodec.decode(legacy)!!
        assertEquals(600.0, l.durationSec, 0.01)
        assertEquals(0.0, l.converterSlipSec, 0.01)
        assertNull(l.maxSlipRpm)
    }

    @Test
    fun `paddle shifts counted in M mode with voltage envelope`() {
        val rec = RideBehaviorRecorder()
        rec.modeTag = RideBehaviorRecorder.ModeTag.M
        var t = 0L
        fun feed(rpm: Double, speed: Double, gear: Int?, volt: Double?) {
            rec.onSample(t, "CRUISING", rpm, speed, gear, null, voltageV = volt)
            t += 5000L
        }
        feed(2000.0, 60.0, 4, 14.1)
        feed(2000.0, 60.0, 4, 13.9)
        feed(2000.0, 60.0, 4, 25.0) // out-of-range garbage must be ignored
        feed(2000.0, 60.0, 4, 12.2)
        feed(3200.0, 80.0, 4, null)  // pre-pull: rpm BEFORE the upshift is the evidence
        feed(2600.0, 85.0, 5, null)  // 4->5 in M = paddle UP @ 3200 rpm
        feed(2500.0, 70.0, 5, null)
        feed(2000.0, 60.0, 4, null)  // 5->4 in M = paddle DOWN @ 2500 rpm
        val s = rec.summary("x")
        assertEquals(1, s.paddleUp)
        assertEquals(1, s.paddleDown)
        assertEquals(2, s.paddleShifts)
        assertEquals(2850.0, s.avgPaddleRpm!!, 0.01)
        assertEquals(12.2, s.voltMinV!!, 0.01)
        assertEquals(14.1, s.voltMaxV!!, 0.01)
        assertEquals(13.4, s.voltAvgV!!, 0.01)
    }

    @Test
    fun `AC-state buckets split km and fuel per tagged climate state`() {
        val rec = RideBehaviorRecorder()
        var t = 0L
        fun feed(rpm: Double, speed: Double, fuel: Double) {
            rec.onSample(t, "CRUISING", rpm, speed, 4, null, fuelRateLh = fuel)
            t += 5000L
        }
        // 13 samples tagged OFF = 12 five-second intervals at 60 km/h, 4.0 L/h
        // -> ~1.0 km, ~0.0667 L => 15.0 km/L (plus one boundary interval after the switch).
        repeat(13) { feed(2000.0, 60.0, 4.0) }
        rec.acTag = RideBehaviorRecorder.AcTag.AC
        // 13 samples tagged AC at 50 km/h, 5.0 L/h => ~10-11 km/L (compressor load).
        repeat(13) { feed(2200.0, 50.0, 5.0) }
        val s = rec.summary("x")
        assertTrue("off km ~1.08, was ${s.acOffKm}", s.acOffKm in 1.0..1.15)
        assertEquals(15.0, s.acOffKmL!!, 0.3)
        assertTrue("on km > 0.5, was ${s.acOnKm}", s.acOnKm > 0.5)
        assertEquals(10.5, s.acOnKmL!!, 1.0)
        // Same ride, both states observed -> penalty computed (~+40% fuel per km with AC).
        val pen = s.acFuelPenaltyPct!!
        assertTrue("penalty ~40%, was $pen", pen in 25.0..60.0)
        assertEquals(0.0, s.blowerKm, 0.001)
    }

    @Test
    fun `codec r3 round trips AC buckets and legacy r2 still decodes`() {
        val rec = RideBehaviorRecorder()
        rec.acTag = RideBehaviorRecorder.AcTag.AC
        rec.onSample(0L, "CRUISING", 2000.0, 72.0, 4, null, fuelRateLh = 6.0, voltageV = 13.8)
        rec.onSample(5000L, "CRUISING", 2000.0, 72.0, 4, null, fuelRateLh = 6.0, voltageV = 14.0)
        val encoded = RideCodec.encode(rec.summary("2026-09-08T12:00:00Z"))
        assertTrue(encoded.startsWith("r3|"))
        val d = RideCodec.decode(encoded)!!
        assertEquals(5.0, d.acOnSec, 0.01)
        assertEquals(0.1, d.acOnKm, 0.002)   // 72 km/h * 5 s
        assertEquals(13.9, d.voltAvgV!!, 0.01)
        assertEquals(13.8, d.voltMinV!!, 0.01)
        assertEquals(14.0, d.voltMaxV!!, 0.01)
        // Legacy 16-field r2 lines (already stored) decode with batch-11c defaults.
        val legacyR2 = "r2|2026-02-02T00:00:00Z|300|8.00|CRUISING=300|0,300,0,0,0,0|0|0|2|2400|2600|120|D|30|270|800"
        val r2 = RideCodec.decode(legacyR2)!!
        assertEquals(30.0, r2.converterSlipSec, 0.01)
        assertEquals(800.0, r2.maxSlipRpm!!, 0.01)
        assertEquals(0, r2.paddleShifts)
        assertNull(r2.voltAvgV)
        assertEquals(0.0, r2.acOnKm, 0.001)
    }
}
