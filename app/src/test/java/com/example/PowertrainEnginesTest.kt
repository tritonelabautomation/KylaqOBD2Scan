package com.example

import com.example.discovery.PidCapabilityManager
import com.example.engine.DrivingStateEngine
import com.example.engine.EconomyEngine
import com.example.engine.TransmissionEngine
import com.example.model.CapabilityStatus
import com.example.model.DrivingState
import com.example.model.PidDefinition
import com.example.model.PollingPriority
import com.example.model.ValueSource
import com.example.protocol.PidDecoder
import org.junit.Assert.*
import org.junit.Test

class PowertrainEnginesTest {

    @Test
    fun testEconomyEngine_InstantaneousAndIdle() {
        val engine = EconomyEngine()

        // Idle test: speed = 0, fuel rate = 0.65 L/h
        val idleSnapshot = engine.computeInstantEconomy(speedKmh = 0.0, fuelRateLh = 0.65, engineRpm = 800.0)
        assertTrue(idleSnapshot.isIdle)
        assertEquals(0.65, idleSnapshot.idleConsumptionLh ?: 0.0, 0.01)
        assertEquals("—", idleSnapshot.instantKmLDisplay)
        assertEquals(ValueSource.CALCULATED, idleSnapshot.source)

        // Moving test: speed = 80 km/h, fuel rate = 4.0 L/h -> 20 km/L (5.0 L/100km)
        val movingSnapshot = engine.computeInstantEconomy(speedKmh = 80.0, fuelRateLh = 4.0, engineRpm = 1800.0)
        assertFalse(movingSnapshot.isIdle)
        assertEquals(20.0, movingSnapshot.instantKmL ?: 0.0, 0.1)
        assertEquals(5.0, movingSnapshot.instantL100km ?: 0.0, 0.1)
        assertEquals("20.0 km/L", movingSnapshot.instantKmLDisplay)
        assertEquals("5.0 L/100km", movingSnapshot.instantL100kmDisplay)

        // Fuel-cut deceleration test: speed = 60 km/h, fuel rate = 0.0 L/h
        val fuelCutSnapshot = engine.computeInstantEconomy(speedKmh = 60.0, fuelRateLh = 0.0, engineRpm = 2000.0)
        assertEquals(99.9, fuelCutSnapshot.instantKmL ?: 0.0, 0.1)
        assertEquals(0.0, fuelCutSnapshot.instantL100km ?: 0.0, 0.1)
    }

    @Test
    fun testEconomyEngine_TripRiemannIntegration() {
        val engine = EconomyEngine()

        // 1st sample at t = 1000ms
        engine.processTripSample(
            timestampMonotonic = 1000L,
            speedKmh = 60.0,
            fuelRateLh = 3.6,
            engineRpm = 1500.0,
            isFuelCut = false,
            isCoasting = false
        )

        // 2nd sample at t = 3000ms (dt = 2000ms = 2.0s)
        // distance = 60 km/h * (2 / 3600) h = 0.0333 km
        // fuel = 3.6 L/h * (2 / 3600) h = 0.002 L
        val stats = engine.processTripSample(
            timestampMonotonic = 3000L,
            speedKmh = 60.0,
            fuelRateLh = 3.6,
            engineRpm = 1500.0,
            isFuelCut = false,
            isCoasting = false
        )

        assertEquals(2L, stats.tripDurationSec)
        assertEquals(0.033, stats.distanceKm, 0.005)
        assertEquals(0.002, stats.totalFuelLiters, 0.001)
        assertEquals(16.6, stats.averageKmL, 1.0)
        assertEquals(6.0, stats.averageL100km, 0.5)
        assertTrue(stats.isFuelIntegrated)
    }

    @Test
    fun testTransmissionEngine_6SpeedTorqueConverter() {
        val engine = TransmissionEngine()

        // Test stationary / idle
        val stationary = engine.evaluate(speedKmh = 0.0, engineRpm = 800.0)
        assertEquals("P/N (Idle)", stationary.selectedRange)
        assertNull(stationary.estimatedGear)
        assertEquals("Not available / Not detected", stationary.actualGearDisplay)

        // Test 5th gear: band is 19.5..25.5 -> at 100 km/h, 2200 RPM -> ratio is 22.0
        val highwayCruise5 = engine.evaluate(speedKmh = 100.0, engineRpm = 2200.0)
        assertEquals("D (Driving)", highwayCruise5.selectedRange)
        assertEquals(5, highwayCruise5.estimatedGear)
        assertTrue(highwayCruise5.isEstimatedGearConfident)
        assertEquals("Gear 5 (Estimated)", highwayCruise5.estimatedGearDisplay)
        assertEquals(ValueSource.ESTIMATED, highwayCruise5.source)

        // Test 6th gear: band is 14.5..19.0 -> at 100 km/h, 1750 RPM -> ratio is 17.5
        val highwayCruise6 = engine.evaluate(speedKmh = 100.0, engineRpm = 1750.0)
        assertEquals(6, highwayCruise6.estimatedGear)
        assertEquals("Gear 6 (Estimated)", highwayCruise6.estimatedGearDisplay)
    }

    @Test
    fun testDrivingStateEngine_Transitions() {
        val engine = DrivingStateEngine()

        // 1. Stopped
        val stopped = engine.evaluate(1000L, speedKmh = 0.0, engineRpm = 0.0, acceleratorPct = 0.0, throttlePct = 0.0, fuelRateLh = 0.0)
        assertEquals(DrivingState.STOPPED, stopped.state)

        // 2. Idle
        val idle = engine.evaluate(2000L, speedKmh = 0.0, engineRpm = 820.0, acceleratorPct = 0.0, throttlePct = 12.0, fuelRateLh = 0.65)
        assertEquals(DrivingState.IDLE, idle.state)

        // 3. Accelerating
        engine.evaluate(3000L, speedKmh = 20.0, engineRpm = 1800.0, acceleratorPct = 25.0, throttlePct = 28.0, fuelRateLh = 3.5)
        val accel = engine.evaluate(4000L, speedKmh = 35.0, engineRpm = 2400.0, acceleratorPct = 30.0, throttlePct = 32.0, fuelRateLh = 4.8)
        assertEquals(DrivingState.ACCELERATING, accel.state)

        // 4. Fuel-cut deceleration (speed decreasing from 60 to 58 km/h without brake)
        engine.evaluate(5000L, speedKmh = 60.0, engineRpm = 2400.0, acceleratorPct = 0.0, throttlePct = 0.0, fuelRateLh = 0.0, brakeSignal = false)
        val fuelCut = engine.evaluate(6000L, speedKmh = 58.0, engineRpm = 2200.0, acceleratorPct = 0.0, throttlePct = 0.0, fuelRateLh = 0.0, brakeSignal = false)
        assertEquals(DrivingState.FUEL_CUT_DECELERATION, fuelCut.state)
        assertTrue(fuelCut.isFuelCut)
    }

    @Test
    fun testPidDecoder_Formulas() {
        val allPids = com.example.model.DefaultPidDefinitions.getDefaults()

        // PID 015E: Volume fuel rate ((A * 256) + B) / 20.0
        val pid5E = allPids.first { it.id == "015E" }
        // 4.0 L/h -> raw = 80 -> A=0, B=80
        val dec5E = PidDecoder.decode(pid5E, listOf(0x41, 0x5E, 0x00, 0x50))
        assertEquals(4.0, dec5E.numericValue ?: 0.0, 0.05)
        assertEquals("4.00", dec5E.displayValue)

        // PID 019D: Mass fuel rate ((A * 256) + B) / 10.0 (g/s)
        val pid9D = allPids.first { it.id == "019D" }
        // 12.5 g/s -> raw = 125 -> A=0, B=125 (0x7D)
        val dec9D = PidDecoder.decode(pid9D, listOf(0x41, 0x9D, 0x00, 0x7D))
        assertEquals(12.5, dec9D.numericValue ?: 0.0, 0.05)
        assertEquals("12.50", dec9D.displayValue)

        // PID 0110: MAF ((A * 256) + B) / 100.0 (g/s)
        val pid10 = allPids.first { it.id == "0110" }
        // 15.00 g/s -> raw = 1500 (0x05DC)
        val dec10 = PidDecoder.decode(pid10, listOf(0x41, 0x10, 0x05, 0xDC))
        assertEquals(15.0, dec10.numericValue ?: 0.0, 0.05)
        assertEquals("15.00", dec10.displayValue)

        // PID 010A: Fuel Pressure A * 3 kPa gauge
        val pid0A = allPids.first { it.id == "010A" }
        val dec0A = PidDecoder.decode(pid0A, listOf(0x41, 0x0A, 0x64)) // 100 * 3 = 300 kPa
        assertEquals(300.0, dec0A.numericValue ?: 0.0, 0.1)
        assertEquals("300", dec0A.displayValue)

        // PID 01A4: Gear ratio
        val pidA4 = allPids.first { it.id == "01A4" }
        // Ratio 1.560 -> raw = 1560 (0x0618)
        val decA4 = PidDecoder.decode(pidA4, listOf(0x41, 0xA4, 0x01, 0x06, 0x18))
        assertEquals(1.56, decA4.numericValue ?: 0.0, 0.01)
        assertEquals("Ratio 1.560", decA4.displayValue)
    }

    @Test
    fun testCapabilityManager_BitmaskParsing() {
        val manager = PidCapabilityManager()

        // Mock bitmap for 0100: support 0101 (bit 7 of byte 0) and 010C (bit 4 of byte 1), etc.
        // Byte 0: 0x80 (PID 0101 supported)
        // Byte 1: 0x10 (PID 010C supported)
        // Byte 2: 0x08 (PID 0115 supported)
        // Byte 3: 0x01 (PID 0120 supported -> hasNext = true)
        val hasNext = manager.parseCapabilityBitmap(0x00, listOf(0x80, 0x10, 0x08, 0x01))

        assertTrue(hasNext)
        assertEquals(CapabilityStatus.SUPPORTED, manager.getStatus("0101"))
        assertEquals(CapabilityStatus.SUPPORTED, manager.getStatus("010C"))
        assertEquals(CapabilityStatus.SUPPORTED, manager.getStatus("0115"))
        assertEquals(CapabilityStatus.SUPPORTED, manager.getStatus("0120"))
        assertEquals(CapabilityStatus.NOT_SUPPORTED, manager.getStatus("0102"))
        assertEquals(CapabilityStatus.NOT_SUPPORTED, manager.getStatus("010D"))
    }
}
