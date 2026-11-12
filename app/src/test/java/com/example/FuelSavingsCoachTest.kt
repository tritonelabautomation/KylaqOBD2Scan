package com.example

import com.example.analysis.FuelSavingsCoach
import com.example.data.FuelLogCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Save Fuel playbook's personalisation math (owner request 2026-09-13).
 * All expected values hand-computed:
 *  - intervals: 300/20=15, 320/20=16, 280/20=14, 340/20=17, then a partial (5 L, no anchor)
 *    folded into the next close: (1540-1240)/(5+25)=10
 *  - recent window = last 4 -> (16+14+17+10)/4 = 14.25; previous = [15] -> 15.0; trend = -5 %
 *  - idle 0.8 L/h x 106 = 84.8 ₹/h; /6 = 14.13 ₹ per 10 min
 *  - AC model: fallback Δ8 -> 1.1+0.8=1.9 kW -> 1.9/2.691666 = 0.706 L/h
 *              auto Δ11 -> (1.1+1.1)*0.75=1.65 kW -> 0.613 L/h
 */
class FuelSavingsCoachTest {

    private fun entry(
        idMs: Long, liters: Double, pricePerL: Double,
        odometerKm: Double?, partial: Boolean = false
    ) = FuelLogCodec.FuelEntry(
        idMs = idMs, dateUtc = "2026-09-1${idMs}T00:00:00Z", liters = liters,
        pricePerL = pricePerL, odometerKm = odometerKm, station = "HP", grade = "X95",
        note = "", partial = partial
    )

    @Test
    fun `empty log yields null baseline and loud-state inputs`() {
        val b = FuelSavingsCoach.baseline(emptyList())
        assertNull(b.recentKmL)
        assertEquals(0, b.tanks)
        assertNull(b.latestPricePerL)
    }

    @Test
    fun `four full tanks average with no trend when history is short`() {
        val b = FuelSavingsCoach.baseline(
            listOf(
                entry(1, 20.0, 100.0, 0.0),
                entry(2, 20.0, 101.0, 300.0),
                entry(3, 20.0, 102.0, 620.0),
                entry(4, 20.0, 103.0, 900.0),
                entry(5, 20.0, 104.0, 1240.0)
            )
        )
        assertEquals(15.5, b.recentKmL!!, 0.001) // (15+16+14+17)/4
        assertNull(b.previousKmL)
        assertNull(b.trendPct)
        assertEquals(4, b.tanks)
        assertEquals(104.0, b.latestPricePerL!!, 0.001)
    }

    @Test
    fun `partial fill-up swells the next interval and trend turns negative`() {
        val b = FuelSavingsCoach.baseline(
            listOf(
                entry(1, 20.0, 100.0, 0.0),
                entry(2, 20.0, 101.0, 300.0),
                entry(3, 20.0, 102.0, 620.0),
                entry(4, 20.0, 103.0, 900.0),
                entry(5, 20.0, 104.0, 1240.0),
                entry(6, 5.0, 105.0, null, partial = true),
                entry(7, 25.0, 106.0, 1540.0)
            )
        )
        assertEquals(14.25, b.recentKmL!!, 0.001) // (16+14+17+10)/4
        assertEquals(15.0, b.previousKmL!!, 0.001)
        assertEquals(-5.0, b.trendPct!!, 0.001)
        assertEquals(106.0, b.latestPricePerL!!, 0.001)
    }

    @Test
    fun `idle cost is priced at the latest pump price`() {
        val idleLh = com.example.engine.PowertrainModel.IDLE_FUEL_LH // calibrated to the owner's car (median 1.05 L/h)
        assertEquals(idleLh * 106.0, FuelSavingsCoach.idleRupeesPerHour(106.0)!!, 0.001)
        assertEquals(idleLh * 106.0 / 6.0, FuelSavingsCoach.idleRupeesPer10Min(106.0)!!, 0.001)
        assertNull(FuelSavingsCoach.idleRupeesPerHour(null))
        assertNull(FuelSavingsCoach.idleRupeesPerHour(0.0))
    }

    @Test
    fun `AC penalty follows the documented climate model`() {
        // extreme: unknown temps -> fallback delta 8 C, manual -> no AUTO modulation
        assertEquals(0.706, FuelSavingsCoach.acExtraLh("AC", false, null, null), 0.01)
        // typical hot day, AUTO: delta 11 C modulated by 0.75
        assertEquals(0.613, FuelSavingsCoach.acExtraLh("AC", true, 35.0, 24.0), 0.01)
        // Hyderabad-summer extreme: delta 27 C -> 1.1+2.7=3.8 kW -> 3.8/2.691666 = 1.412 L/h
        assertEquals(1.41, FuelSavingsCoach.acExtraLh("AC", false, 45.0, 18.0), 0.01)
        // blower-only and off
        assertTrue(FuelSavingsCoach.acExtraLh("BLOWER", false, null, null) < 0.06)
        assertEquals(0.0, FuelSavingsCoach.acExtraLh("OFF", false, null, null), 0.001)
    }
}
