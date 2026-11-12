package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidTripFuelTest {

    @Test
    fun brimTripDoesNotCountFillAsBurn() {
        val s = MidTripFuel.split(
            tripStartPct = 22.0,
            tripEndPct = 93.7,
            tankL = 52.9,
            fillBeforePct = 17.6,
            fillAfterPct = 93.7,
            pumpLitres = 40.27,
            fillOnThisTrip = true
        )
        assertEquals(37.93, s.pidTripWindowLitres!!, 0.05)
        assertEquals(40.26, s.pidFillLitres!!, 0.05)
        assertEquals(0.01, s.pumpMinusPidFillL!!, 0.05)
        assertEquals(4.4, s.netBurnPct!!, 0.05)
        assertEquals(2.33, s.netBurnLitres!!, 0.05)
        assertTrue(s.tripIsBrim)
        assertTrue(s.netBurnLitres!! < 5.0)
        assertTrue(s.pidFillLitres!! > 40.0)
        assertTrue(MidTripFuel.pumpVsPidLine(s)!!.contains("pump 40.27"))
    }

    @Test
    fun tripStartingAfterFillDoesNotCountRiseAsPreBurn() {
        val s = MidTripFuel.split(
            tripStartPct = 93.7,
            tripEndPct = 88.0,
            tankL = 52.9,
            fillBeforePct = 17.6,
            fillAfterPct = 93.7,
            pumpLitres = 40.27,
            fillOnThisTrip = true
        )
        assertEquals(5.7, s.netBurnPct!!, 0.05)
        assertTrue(s.netBurnLitres!! < 4.0)
        assertEquals(40.26, s.pidFillLitres!!, 0.05)
    }

    @Test
    fun normalDropIsBurn() {
        val s = MidTripFuel.split(
            tripStartPct = 45.0,
            tripEndPct = 36.0,
            tankL = 45.0
        )
        assertEquals(9.0, s.netBurnPct!!, 1e-9)
        assertEquals(4.05, s.netBurnLitres!!, 0.01)
        assertNull(s.pidFillLitres)
    }

    @Test
    fun brimWithoutFillWindowIsNotBurn() {
        val s = MidTripFuel.split(
            tripStartPct = 22.0,
            tripEndPct = 93.7,
            tankL = 52.9
        )
        assertNull(s.netBurnLitres)
        assertTrue(s.tripIsBrim)
        assertEquals(37.93, s.pidTripWindowLitres!!, 0.05)
    }

    @Test
    fun pointEightLitreBunkShortIsVisible() {
        val s = MidTripFuel.split(
            tripStartPct = 20.0,
            tripEndPct = 94.0,
            tankL = 50.0,
            fillBeforePct = 20.0,
            fillAfterPct = 94.0,
            pumpLitres = 36.2,
            fillOnThisTrip = true
        )
        assertEquals(37.0, s.pidFillLitres!!, 0.05)
        assertEquals(-0.8, s.pumpMinusPidFillL!!, 0.05)
        assertNull(s.netBurnLitres)
    }
}
