package com.example

import com.example.analysis.TripTrendAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-trip trend aggregation: averages, torque conversion and idle model-vs-actual. */
class TripTrendAnalyzerTest {

    private fun s(trip: String, pid: String, ts: Long, v: Double?) =
        TripTrendAnalyzer.Sample(trip, pid, ts, v)

    @Test
    fun `averages and torque percent conversion per trip`() {
        val samples = listOf(
            s("t1", "010C", 0, 2000.0),
            s("t1", "010D", 0, 60.0),
            s("t1", "0104", 0, 40.0),
            s("t1", "0162", 0, 50.0),
            s("t1", "010C", 5000, 3000.0),
            s("t1", "010D", 5000, 80.0),
            s("t1", "0104", 5000, 60.0),
            s("t1", "0162", 5000, 70.0)
        )
        val p = TripTrendAnalyzer.analyze(samples).single()
        assertEquals(2500.0, p.avgRpm!!, 0.01)
        assertEquals(70.0, p.avgSpeedKmh!!, 0.01)
        assertEquals(50.0, p.avgLoadPct!!, 0.01)
        assertEquals(60.0 * 1.78, p.avgTorqueNm!!, 0.01) // 60% of the 178 Nm reference
        assertEquals(0.0, p.idleSec, 0.001)
        assertNull(p.idleActualLh)
    }

    @Test
    fun `idle window measures actual burn against the 0_8 Lh model`() {
        // 4 samples x 5 s = 15 s of moving first (not idle), then idle block:
        // prev observation speed 0 & rpm 900 for three 5 s intervals = 15 s idle at 1.2 L/h.
        val samples = mutableListOf(
            s("t1", "010D", 0, 50.0),
            s("t1", "010C", 0, 2000.0),
            s("t1", "015E", 0, 4.0)
        )
        var ts = 5000L
        repeat(3) {
            samples += s("t1", "010D", ts, 0.0)   // stopped
            samples += s("t1", "010C", ts, 900.0) // idle rpm
            samples += s("t1", "015E", ts, 1.2)   // measured idle burn L/h
            ts += 5000L
        }
        // pad idle past the 30 s reporting gate (gate is strict >30 s)
        repeat(5) {
            samples += s("t1", "010D", ts, 0.0)
            samples += s("t1", "010C", ts, 900.0)
            samples += s("t1", "015E", ts, 1.2)
            ts += 5000L
        }
        val p = TripTrendAnalyzer.analyze(samples).single()
        assertTrue("idle seconds ~35, was ${p.idleSec}", p.idleSec in 30.0..40.0)
        assertEquals(1.2, p.idleActualLh!!, 0.05)
        // model expects 0.8 L/h -> measured is +50%
        assertTrue("excess ~50%, was ${p.idleExcessPct}", p.idleExcessPct!! in 40.0..60.0)
        assertEquals(0.8 * p.idleSec / 3600.0, p.idleModelL, 1e-9)
    }

    @Test
    fun `mass fuel pid converts grams per second to litres per hour`() {
        // 0.2483 g/s == 1.2 L/h at 745 g/L
        val samples = listOf(
            s("t1", "010D", 0, 0.0),
            s("t1", "010C", 0, 850.0),
            s("t1", "019D", 0, 0.2483)
        ) + List(8) { i ->
            val ts = 5000L * (i + 1)
            s("t1", "019D", ts, 0.2483)
        }
        val p = TripTrendAnalyzer.analyze(samples).single()
        assertTrue("idle seen, was ${p.idleSec}", p.idleSec >= 30.0)
        assertEquals(1.2, p.idleActualLh!!, 0.05)
    }

    @Test
    fun `multiple trips stay separate and nulls are skipped`() {
        val samples = listOf(
            s("t1", "010C", 0, 2000.0),
            s("t1", "010C", 5000, null), // null value ignored
            s("t2", "010C", 1000, 4000.0)
        )
        val pts = TripTrendAnalyzer.analyze(samples).associateBy { it.tripId }
        assertEquals(2, pts.size)
        assertEquals(2000.0, pts["t1"]!!.avgRpm!!, 0.01)
        assertEquals(4000.0, pts["t2"]!!.avgRpm!!, 0.01)
    }
}
