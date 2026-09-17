package com.example

import com.example.analysis.TripTrendAnalyzer.powerPoints
import com.example.analysis.TripTrendAnalyzer.gearPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-09-16: derived power & gear must show on EVERY trip's Trends tab,
 * including old/recovered logs - they are computed from stored rows.
 */
class DerivedSignalSeriesTest {

    private data class Row(val ts: Long, val pid: String, val v: Double)

    @Test
    fun powerSeriesFollowsRpmAndTorquePairs() {
        val rows = listOf(
            Row(1_000, "0C", 2460.0),
            Row(1_000, "62", 50.0),     // 89 Nm -> 22.9 kW
            Row(2_000, "0C", 4920.0)   // torque still fresh -> ~45.8 kW
        )
        val pts = powerPoints(rows, { it.ts }, { it.pid }, { it.v })
        assertEquals(2, pts.size)
        assertEquals(2 * Math.PI * 2460.0 * 89.0 / 60000.0, pts[0].second, 0.05)
        assertTrue(pts[1].second > pts[0].second)
    }

    @Test
    fun tripsWithoutTorqueRowsGetNoFakePower() {
        val rows = listOf(Row(1_000, "0C", 2460.0), Row(2_000, "0C", 2500.0))
        assertEquals(0, powerPoints(rows, { it.ts }, { it.pid }, { it.v }).size)
    }

    @Test
    fun gearSeriesStepsWhileMovingAndStopsWhenParked() {
        val rows = listOf(
            Row(1_000, "0D", 40.0),
            Row(1_000, "0C", 2464.0),   // 61.6 rpm per km/h = 2nd
            Row(2_000, "0D", 0.0),
            Row(2_000, "0C", 1400.0)    // parked - no gear point
        )
        val pts = gearPoints(rows, { it.ts }, { it.pid }, { it.v })
        assertEquals(1, pts.size)
        assertEquals(2.0, pts[0].second, 1e-9)
    }

    @Test
    fun fourHexAndTwoHexPidsBothWork() {
        val rows = listOf(Row(1_000, "010D", 40.0), Row(1_000, "010C", 2464.0))
        assertEquals(1, gearPoints(rows, { it.ts }, { it.pid }, { it.v }).size)
    }
}
