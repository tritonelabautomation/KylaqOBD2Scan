package com.example

import com.example.engine.TransmissionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Expected-vs-actual drivetrain deviation: the AQ250 gear model predicts the engine rpm the
 * gearbox should show for the nearest gear at the current speed; the difference is torque-
 * converter slip (launch / slip-controlled phase / shift transient) or a below-model state.
 * Prior scales: g1 = 26*4.148 = 107.848, g5 = 26*0.859 = 22.334, g6 = 26*0.686 = 17.836 rpm/kmh.
 */
class TransmissionDeviationTest {

    @Test
    fun `locked cruise reports small slip against the factory model`() {
        val state = TransmissionEngine().evaluate(speedKmh = 100.0, engineRpm = 2200.0)
        assertEquals("Gear 5 (Estimated)", state.estimatedGearDisplay)
        assertTrue(state.isEstimatedGearConfident)
        val slip = state.torqueConverterSlipRpm
        assertNotNull(slip)
        // expected 22.334 * 100 = 2233 rpm -> slip about -33 rpm
        assertTrue("slip=$slip", abs(slip!! + 33.4) < 1.0)
        assertTrue(state.torqueConverterLockup.startsWith("Locked / coupled"))
    }

    @Test
    fun `launch slip is quantified when no gear matches`() {
        // 15 km/h at 2500 rpm = 166.7 rpm/kmh - far above 1st gear's 107.8 -> open converter.
        val state = TransmissionEngine().evaluate(speedKmh = 15.0, engineRpm = 2500.0)
        assertEquals("—", state.estimatedGearDisplay)
        val slip = state.torqueConverterSlipRpm
        assertNotNull(slip)
        // expected 107.848 * 15 = 1617.7 -> slip about +882 rpm
        assertTrue("slip=$slip", abs(slip!! - 882.3) < 2.0)
        assertTrue(state.torqueConverterLockup.contains("Converter slip +882"))
    }

    @Test
    fun `between-gear deviation reports slip instead of guessing a gear`() {
        // 25.5 rpm/kmh: 14.2% above gear 5 scale - outside the 12% tolerance (see GearModelTest).
        val state = TransmissionEngine().evaluate(speedKmh = 100.0, engineRpm = 2550.0)
        assertEquals("—", state.estimatedGearDisplay)
        assertNull(state.estimatedGear)
        val slip = state.torqueConverterSlipRpm
        assertNotNull(slip)
        assertTrue("slip=$slip", abs(slip!! - 316.6) < 2.0)
        assertTrue(state.torqueConverterLockup.startsWith("Converter slip +317"))
    }

    @Test
    fun `rpm below every gear band is flagged as below-model decel state`() {
        // 15.0 rpm/kmh: 15.9% below gear 6 scale -> engine braking / decel, not a gear match.
        val state = TransmissionEngine().evaluate(speedKmh = 100.0, engineRpm = 1500.0)
        assertEquals("—", state.estimatedGearDisplay)
        val slip = state.torqueConverterSlipRpm
        assertNotNull(slip)
        // expected 17.836 * 100 = 1783.6 -> slip about -284 rpm
        assertTrue("slip=$slip", abs(slip!! + 283.6) < 2.0)
        assertTrue(state.torqueConverterLockup.startsWith("Below model"))
    }

    @Test
    fun `idle keeps deviation fields untouched`() {
        val state = TransmissionEngine().evaluate(speedKmh = 0.0, engineRpm = 800.0)
        assertEquals("P/N (Idle)", state.selectedRange)
        assertNull(state.torqueConverterSlipRpm)
        assertEquals("Not available", state.torqueConverterLockup)
    }
}
