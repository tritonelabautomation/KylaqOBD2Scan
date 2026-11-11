package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner 2026-11-11 IndianOil COCO Begumpet auto-cut: receipt 40.27 L, start 17.6 %,
 * cut 93.7 %. The 50 L factory number made every trip litre (and km/L vs MID) wrong.
 */
class TankCapacityTest {

    @Test
    fun ownerAutoCutFillImplies52_9LNot50() {
        val cap = TankCapacity.impliedFromFill(
            pumpLitres = 40.27,
            startPct = 17.6,
            autoCutPct = 93.7
        )!!
        assertEquals(52.9, cap, 0.05)
        assertEquals(40.27, TankCapacity.litresFromPercent(93.7 - 17.6, cap), 0.05)
    }

    @Test
    fun tripPercentDeltaUsesCalibratedCapacity() {
        // Same recovered trip card: 22.0 → 93.7 = 71.7 pt at 50 L was 35.88 L.
        val at50 = TankCapacity.litresFromPercent(71.8, 50.0)
        assertEquals(35.9, at50, 0.05)
        val atCal = TankCapacity.litresFromPercent(71.8, 52.9)
        assertEquals(38.0, atCal, 0.1)
    }

    @Test
    fun tinyRiseOrEmptyPumpIsNotACalibration() {
        assertNull(TankCapacity.impliedFromFill(40.27, 90.0, 91.0))
        assertNull(TankCapacity.impliedFromFill(0.2, 17.6, 93.7))
        assertNull(TankCapacity.sanitize(10.0))
        assertNull(TankCapacity.sanitize(120.0))
    }
}
