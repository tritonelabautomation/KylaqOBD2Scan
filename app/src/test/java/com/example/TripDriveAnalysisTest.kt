package com.example

import com.example.analysis.TripDriveAnalysis
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the replicated trip-detail "Drive analysis" math (owner reference screen 2).
 * Hand-computed: hourly 60 s-step series at 50 km/h with one 1 s brake (50->20, -30 km/h/s)
 * and one 1 s accel (20->50, +30 km/h/s) => 1 hard brake + 1 rapid accel over ~1.0006 h
 * => control sliders = 1 - (1/1.0006)/6 = 0.8337; variability: mean 49.52, sd 3.775 ->
 * cov 0.0762 -> steadiness = 1 - 0.0762/0.6 = 0.873.
 */
class TripDriveAnalysisTest {

    private fun series(): List<Pair<Long, Double>> {
        val pts = mutableListOf<Pair<Long, Double>>()
        var t = 0L
        while (t <= 3600_000L) {
            if (t == 1800_000L) {
                pts.add(t to 50.0)
                pts.add(t + 1_000 to 20.0)   // hard brake: -30 km/h in 1 s
                pts.add(t + 2_000 to 50.0)   // rapid accel: +30 km/h in 1 s
                t += 60_000L
                continue
            }
            pts.add(t to 50.0)
            t += 60_000L
        }
        return pts
    }

    @Test
    fun `events and sliders match hand computation`() {
        val r = TripDriveAnalysis.analyse(series(), idleSeconds = 0.0, speedHistogram = listOf(50 to 3600.0))
        assertEquals(1, r.hardBrakingEvents)
        assertEquals(1, r.rapidAccelEvents)
        assertEquals(0.8337, r.brakingControl, 0.01)
        assertEquals(0.8337, r.accelControl, 0.01)
        assertEquals(0.873, r.speedSteadiness, 0.02)
        assertEquals(listOf(60.0, 0.0, 0.0, 0.0), r.bandMinutes)
    }

    @Test
    fun `band minutes split histogram and idle`() {
        val r = TripDriveAnalysis.analyse(
            emptyList(), idleSeconds = 300.0,
            speedHistogram = listOf(10 to 600.0, 30 to 900.0, 70 to 1200.0)
        )
        assertEquals(listOf(20.0, 15.0, 10.0, 5.0), r.bandMinutes)
        assertEquals(0, r.hardBrakingEvents)
    }

    @Test
    fun `legend formatting matches reference strings`() {
        assertEquals("1 h 15 min", TripDriveAnalysis.fmtMinutes(75.0))
        assertEquals("17 min", TripDriveAnalysis.fmtMinutes(17.0))
        assertEquals("0 min", TripDriveAnalysis.fmtMinutes(0.0))
    }
}
