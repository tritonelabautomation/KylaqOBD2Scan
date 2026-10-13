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
    fun realOneSecondCadenceAlternatingPidsStillPairs() {
        // Recovered-log shape: pids arrive round-robin, rpm and speed ~0.7 s apart.
        val rows = mutableListOf<Row>()
        var ts = 1_000L
        repeat(10) {
            rows += Row(ts, "0C", 2464.0); ts += 700
            rows += Row(ts, "0D", 40.0); ts += 700
            rows += Row(ts, "62", 50.0); ts += 700
        }
        assertTrue(gearPoints(rows, { it.ts }, { it.pid }, { it.v }).size >= 9)
        assertTrue(powerPoints(rows, { it.ts }, { it.pid }, { it.v }).size >= 9)
    }

    @Test
    fun gearSurvivesSpeedPolledRightAfterRpm() {
        // The owner's real poll order: 0D lands just AFTER 0C, so at the next 0C row the
        // speed is a whole 3 s cycle stale - the old rpm-only emission produced ZERO
        // points ("Still gears doesn't display"). Symmetric emission must fix it.
        val rows = mutableListOf<Row>()
        var ts = 1_000L
        repeat(10) {
            rows += Row(ts, "0C", 2464.0); ts += 300
            rows += Row(ts, "0D", 40.0); ts += 2700
        }
        val pts = gearPoints(rows, { it.ts }, { it.pid }, { it.v })
        assertTrue("gear points from rpm-then-speed cadence: ${pts.size}", pts.size >= 9)
        assertEquals(2.0, pts.first().second, 1e-9)
    }

    @Test
    fun impossibleGearSkipReconstructsTheMissedPhase() {
        // 1st gear crawl, then (sampler blind through the 2nd-gear window) 3rd gear:
        // the series must pass through 2, never draw a 1->3 teleport.
        val rows = mutableListOf<Row>()
        var ts = 1_000L
        repeat(3) { rows += Row(ts, "0D", 12.0); rows += Row(ts + 100, "0C", 1294.0); ts += 1_000 }
        ts += 6_000
        repeat(3) { rows += Row(ts, "0D", 45.0); rows += Row(ts + 100, "0C", 1820.0); ts += 1_000 }
        val gears = gearPoints(rows, { it.ts }, { it.pid }, { it.v }).map { it.second }
        assertTrue("series must contain the reconstructed 2nd: $gears", gears.contains(2.0))
        val seq = gears.distinct()
        for (i in 1 until seq.size) {
            assertTrue("consecutive plateaus step by 1: $seq", kotlin.math.abs(seq[i] - seq[i - 1]) <= 1.0)
        }
    }

    @Test
    fun fourHexAndTwoHexPidsBothWork() {
        val rows = listOf(Row(1_000, "010D", 40.0), Row(1_000, "010C", 2464.0))
        assertEquals(1, gearPoints(rows, { it.ts }, { it.pid }, { it.v }).size)
    }
}
