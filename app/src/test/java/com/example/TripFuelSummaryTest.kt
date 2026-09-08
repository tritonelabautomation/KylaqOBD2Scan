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
}
