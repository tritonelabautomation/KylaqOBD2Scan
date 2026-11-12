package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-11 IndianOil COCO Begumpet auto-cut: receipt 40.27 L, start 17.6 %,
 * cut 93.7 %. The 50 L factory number made every trip litre (and km/L vs MID) wrong.
 *
 * Owner 2026-11-12 (trip screenshots): the app still printed a 3.53 pt drop as 1.76 L
 * (a ~49.9 L tank) on every trip while a 40.3 pt fill window read 34.25 L (a 45.0 L tank).
 * The Kylaq spec is 45 L; 50 L is the Kushaq figure.
 */
class TankCapacityTest {

    @Test
    fun factoryTankIsTheKylaq45LNotTheKushaq50L() {
        assertEquals(45.0, TankCapacity.NOMINAL_L, 1e-9)
        // 2026-11-12 trip: 91.8 % → 83.9 % = 7.9 pt. At 45 L that is 3.56 L.
        assertEquals(3.555, TankCapacity.litresFromPercent(7.9), 0.01)
        // 2026-11-11 trip: 27.8 % → 22.0 % = 5.8 pt ⇒ 2.61 L, not the old 2.90 L.
        assertEquals(2.61, TankCapacity.litresFromPercent(5.8), 0.01)
    }

    @Test
    fun effectiveCapacityIsFactoryUntilAFillMeasuresIt() {
        assertEquals(45.0, TankCapacity.effective(null), 1e-9)
        // Every device that has not logged a fill since the spec fix still carries the old
        // 50 L boot value in prefs. That is a leftover, not a measurement.
        assertEquals(45.0, TankCapacity.effective(50.0), 1e-9)
        assertEquals(45.0, TankCapacity.effective(45.0), 1e-9)
        assertEquals(52.9, TankCapacity.effective(52.9), 1e-9)
        assertFalse(TankCapacity.isCalibrated(null))
        assertFalse(TankCapacity.isCalibrated(45.0))
        assertFalse(TankCapacity.isCalibrated(50.0)) // legacy boot value
        assertTrue(TankCapacity.isCalibrated(52.9))
    }

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
