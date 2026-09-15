package com.example

import com.example.analysis.TripFuelSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TripFuelSummaryTest {

    private fun point(pid: String, ts: Long, value: Double?) =
        TripFuelSummary.SamplePoint(pid, ts, value)

    @Test
    fun `stored 2-hex sample pids (0D 5E 9D) summarise identically to 4-hex`() {
        // Regression for the owner screenshot 2026-09-12: DB rows carry the 2-hex
        // TransactionRecord suffix; before the normalizePidKey fix every stat read zero.
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 1_000_000L
        // 60 s window at 4 L/h = ~0.066 L: above the 0.05 L integration threshold.
        repeat(60) {
            samples.add(point("0D", ts, 60.0))   // speed, 2-hex form
            samples.add(point("5E", ts, 4.0))    // fuel rate L/h, 2-hex form
            ts += 1000
        }
        val s2 = TripFuelSummary.summarize(samples)
        assertTrue("distance must integrate from 2-hex 0D rows", s2.distanceKm > 0.05)
        assertTrue("fuel must integrate from 2-hex 5E rows", s2.fuelLiters > 0.05)
        assertEquals(60.0, s2.maxSpeedKmh, 1e-9)
        assertEquals(120, s2.sampleCount)
    }

    @Test
    fun `integrates fuel rate and speed into distance and consumption`() {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        // 10 minutes at 60 km/h burning 4 L/h, sampled every 10 s
        var ts = 0L
        repeat(61) {
            samples.add(point("010D", ts, 60.0))
            samples.add(point("015E", ts, 4.0))
            ts += 10_000
        }
        val summary = TripFuelSummary.summarize(samples)
        assertTrue(abs(summary.distanceKm - 10.0) < 0.2)
        assertTrue(abs(summary.fuelLiters - 4.0 * 600.0 / 3600.0) < 0.05)
        assertNotNull(summary.kmPerLiter)
        assertTrue(abs((summary.kmPerLiter ?: 0.0) - 15.0) < 0.5)
        assertEquals(600L, summary.durationSeconds)
        assertTrue(abs(summary.averageSpeedKmh - 60.0) < 1.0)
    }

    @Test
    fun `fuel-cut coasting and idling are counted`() {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 0L
        // 2 minutes idling at 0.8 L/h
        repeat(12) {
            samples.add(point("010D", ts, 0.0))
            samples.add(point("015E", ts, 0.8))
            ts += 10_000
        }
        // 2 minutes coasting at 80 km/h with 0 L/h
        repeat(12) {
            samples.add(point("010D", ts, 80.0))
            samples.add(point("015E", ts, 0.0))
            ts += 10_000
        }
        val summary = TripFuelSummary.summarize(samples)
        assertTrue("coast seconds ${summary.coastSeconds}", summary.coastSeconds > 60.0)
        assertTrue("idle seconds ${summary.idleSeconds}", summary.idleSeconds > 60.0)
        assertTrue(summary.fuelLiters > 0.01)
    }

    @Test
    fun `mass flow converts through petrol density`() {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 0L
        repeat(36) {
            samples.add(point("010D", ts, 90.0))
            // 0.828 g/s == 4.0 L/h at 0.745 kg/L
            samples.add(point("019D", ts, 4.0 * 745.0 / 3600.0))
            ts += 10_000
        }
        val summary = TripFuelSummary.summarize(samples)
        assertTrue(abs(summary.fuelLiters - 4.0 * 360.0 / 3600.0) < 0.05)
    }

    @Test
    fun `empty samples produce an empty summary`() {
        val summary = TripFuelSummary.summarize(emptyList())
        assertEquals(0.0, summary.fuelLiters, 0.0001)
        assertEquals(0, summary.sampleCount)
    }

    @Test
    fun `start-stop stall is engine-off time, not idle, and the saving is estimated`() {
        // Owner 2026-09-15: the Kylaq's Idle Start-Stop stalls the engine at lights while
        // the ECU keeps answering. Before the split, those seconds landed in idleSeconds
        // and coaching billed 1.05 L/h of imaginary fuel for time the engine was OFF.
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 0L
        // 60 s warm idle: rpm 800, standing still, 0.9 L/h
        repeat(6) {
            samples.add(point("010C", ts, 800.0))
            samples.add(point("010D", ts, 0.0))
            samples.add(point("015E", ts, 0.9))
            ts += 10_000
        }
        // 60 s start-stop stall: rpm 0, standing still, 0 L/h
        repeat(6) {
            samples.add(point("010C", ts, 0.0))
            samples.add(point("010D", ts, 0.0))
            samples.add(point("015E", ts, 0.0))
            ts += 10_000
        }
        // restart and drive away
        repeat(4) {
            samples.add(point("010C", ts, 2000.0))
            samples.add(point("010D", ts, 40.0))
            samples.add(point("015E", ts, 5.0))
            ts += 10_000
        }

        val summary = TripFuelSummary.summarize(samples)

        assertTrue("idle seconds ${summary.idleSeconds}", summary.idleSeconds in 50.0..60.0)
        assertTrue("engine-off seconds ${summary.engineOffSeconds}", summary.engineOffSeconds in 50.0..60.0)
        assertEquals(1, summary.startStop.stopEvents)
        assertEquals(1, summary.startStop.restartCount)
        assertEquals(
            com.example.analysis.StartStopAnalyzer.Baseline.MEASURED,
            summary.startStop.baselineSource
        )
        assertTrue("idle baseline ${summary.startStop.warmIdleLh}", abs((summary.startStop.warmIdleLh ?: 0.0) - 0.9) < 0.05)
        // ~60 s x 0.9 L/h = ~0.015 L saved
        assertTrue("saved ${summary.startStop.estimatedFuelSavedL}", summary.startStop.estimatedFuelSavedL in 0.010..0.018)
        // The stall itself burned nothing measurable.
        assertTrue(summary.startStop.fuelBurnedWhileStoppedL < 0.001)
    }

    @Test
    fun `ac state is measured from voltage ripple in stored samples`() {
        // Owner 2026-09-16: voltage fluctuation reveals compressor clutch cycling.
        // 10 min quiet alternator band, then 10 min of clutch/blower ripple.
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 0L
        repeat(120) {
            samples.add(point("0142", ts, 14.4 + 0.04 * (((it % 5) - 2) / 2.0)))
            samples.add(point("010C", ts, 1500.0))
            ts += 5_000
        }
        repeat(120) {
            samples.add(point("0142", ts, 13.3 + 0.45 * (((it % 12) - 6) / 6.0)))
            samples.add(point("010C", ts, 1500.0))
            ts += 5_000
        }

        val summary = TripFuelSummary.summarize(samples)

        assertTrue(summary.ac.hasEvidence)
        assertTrue("ac-on seconds ${summary.ac.acOnSeconds}", summary.ac.acOnSeconds > 300.0)
        assertTrue("an ON switch is reported", summary.ac.switchEvents.any { it.second })
        assertTrue("ripple MAD ${summary.ac.onMadV}", (summary.ac.onMadV ?: 0.0) > 0.1)
    }

    @Test
    fun `trips without rpm evidence keep the legacy idle attribution`() {
        // No 010C samples at all (legacy trip / ECU silent on rpm): standstill stays
        // "idling" - the split must never invent engine-off time without evidence.
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 0L
        repeat(8) {
            samples.add(point("010D", ts, 0.0))
            samples.add(point("015E", ts, 0.8))
            ts += 10_000
        }

        val summary = TripFuelSummary.summarize(samples)

        assertTrue("idle ${summary.idleSeconds}", summary.idleSeconds > 60.0)
        assertEquals(0.0, summary.engineOffSeconds, 1e-9)
        assertEquals(0, summary.startStop.stopEvents)
    }
}
