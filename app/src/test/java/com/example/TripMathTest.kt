package com.example

import com.example.engine.TripEstimator
import com.example.engine.TripSplitter
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM tests for trip economics: estimator maths and minimum-transaction settlement. */
class TripMathTest {

    @Test
    fun `estimator computes fuel tolls time and rideshare delta`() {
        val e = TripEstimator.estimate(
            distanceKm = 100.0, kmPerL = 16.0, pricePerL = 112.0,
            avgSpeedKmh = 50.0, tollPerKm = 1.0, ridesharePerKm = 15.0
        )!!
        assertEquals(100.0 / 16.0 * 112.0, e.fuelCost, 0.001)   // 700
        assertEquals(100.0, e.tollCost, 0.001)
        assertEquals(800.0, e.totalCost, 0.001)
        assertEquals(2.0, e.driveHours, 0.001)
        assertEquals(1500.0, e.rideshareCost, 0.001)
        assertEquals(700.0, e.savingVsRideshare, 0.001)
    }

    @Test
    fun `estimator rejects impossible inputs`() {
        assertNull(TripEstimator.estimate(0.0, 16.0, 112.0))
        assertNull(TripEstimator.estimate(100.0, 0.0, 112.0))
        assertNull(TripEstimator.estimate(100.0, 16.0, 0.0))
    }

    @Test
    fun `splitter settles equal shares with fewest transfers`() {
        val transfers = TripSplitter.settle(
            listOf("A", "B", "C"),
            listOf("A" to 3000.0)
        )
        assertEquals(2, transfers.size)
        assertEquals(2000.0, transfers.sumOf { it.amount }, 0.001)
        assertTrue(transfers.all { it.to == "A" })
        assertEquals(setOf("B", "C"), transfers.map { it.from }.toSet())
    }

    @Test
    fun `splitter handles multiple payers and ignores unknown names`() {
        val transfers = TripSplitter.settle(
            listOf("A", "B"),
            listOf("A" to 1000.0, "B" to 500.0, "Ghost" to 500.0)
        )
        // total counted = 1500 (ghost ignored), share 750 => B owes A 250
        assertEquals(1, transfers.size)
        assertEquals("B", transfers[0].from)
        assertEquals("A", transfers[0].to)
        assertEquals(250.0, transfers[0].amount, 0.001)
    }

    @Test
    fun `splitter with one member or zero spend settles nothing`() {
        assertTrue(TripSplitter.settle(listOf("A"), listOf("A" to 100.0)).isEmpty())
        assertTrue(TripSplitter.settle(listOf("A", "B"), emptyList()).isEmpty())
    }
}
