package com.example

import com.example.analysis.DriveAnalytics
import com.example.engine.CoastNeutralDetector
import com.example.engine.FuelQualityAnalyzer
import com.example.engine.PowertrainModel
import com.example.engine.TurboAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the drive-intelligence engines: factory powertrain anchors, coasting-in-neutral
 * detection, turbo boost derivation and per-tank fuel-quality segmentation.
 */
class DriveIntelligenceTest {

    private fun closeTo(expected: Double, actual: Double?, tolerance: Double) {
        assertNotNull("value was null", actual)
        assertTrue(
            "expected $expected ±$tolerance but was $actual",
            abs(expected - (actual ?: Double.NaN)) <= tolerance
        )
    }

    // region PowertrainModel

    @Test
    fun `torque plateau matches factory 178 Nm between 1750 and 4000 rpm`() {
        assertEquals(178.0, PowertrainModel.fullLoadTorqueNm(2000.0), 0.01)
        assertEquals(178.0, PowertrainModel.fullLoadTorqueNm(3500.0), 0.01)
        assertEquals(178.0, PowertrainModel.fullLoadTorqueNm(1750.0), 0.01)
    }

    @Test
    fun `rated point reproduces 85 kW at 5500 rpm`() {
        closeTo(85.0, PowertrainModel.fullLoadPowerKw(5500.0), 1.5)
    }

    @Test
    fun `low rpm shows documented turbo lag ramp`() {
        assertTrue(PowertrainModel.fullLoadTorqueNm(1200.0) < PowertrainModel.fullLoadTorqueNm(1750.0))
        assertTrue(PowertrainModel.fullLoadTorqueNm(1000.0) < 130.0)
    }

    @Test
    fun `power from torque uses the 9549 constant`() {
        closeTo(37.2, PowertrainModel.powerKw(2000.0, 178.0), 0.1)
    }

    @Test
    fun `impossible gear speeds return null consumption`() {
        // 26.7 rpm per km/h is a typical 6th gear: 20 km/h would be 534 rpm, below idle.
        assertNull(PowertrainModel.litersPer100Km(20.0, 26.7))
        assertNotNull(PowertrainModel.litersPer100Km(100.0, 26.7))
    }

    @Test
    fun `sweet spot lands in the efficient cruise band`() {
        val sweet = PowertrainModel.sweetSpotKmh(26.7)
        assertNotNull(sweet)
        assertTrue("sweet spot ${sweet!!.first} outside 50..110", sweet.first in 50.0..110.0)
        assertTrue("sweet spot consumption implausible: ${sweet.second}", sweet.second in 3.0..8.0)
    }

    @Test
    fun `part load is less efficient than high load`() {
        val high = PowertrainModel.brakeThermalEfficiency(2500.0, 120.0)
        val low = PowertrainModel.brakeThermalEfficiency(2500.0, 20.0)
        assertTrue(high > low)
        assertTrue(high in 0.08..0.40)
    }

    @Test
    fun `fuel rate converts power through fuel energy density`() {
        closeTo(20.0 * 3.6 / (0.35 * PowertrainModel.FUEL_ENERGY_MJ_PER_L), PowertrainModel.fuelRateLh(20.0, 0.35), 0.001)
    }

    // endregion

    // region CoastNeutralDetector

    private fun sample(
        ts: Long,
        speed: Double,
        rpm: Double? = 2500.0,
        throttle: Double? = 0.0,
        pedal: Double? = 0.0,
        brake: Boolean? = false,
        fuel: Double? = 0.0,
        cruise: Double? = 4.0
    ) = CoastNeutralDetector.Sample(ts, speed, rpm, throttle, pedal, brake, fuel, cruise)

    @Test
    fun `powered cruising is not coasting`() {
        val detector = CoastNeutralDetector()
        assertNull(detector.onSample(sample(1000, 80.0, throttle = 25.0, pedal = 20.0, fuel = 4.5)))
        assertNull(detector.activeMode)
    }

    @Test
    fun `fuel-cut coast completes with distance and idle savings`() {
        val detector = CoastNeutralDetector()
        var ts = 0L
        // one powered sample so a later event has a clean start
        detector.onSample(sample(ts, 80.0, throttle = 25.0, pedal = 20.0))
        ts += 1000
        repeat(5) {
            detector.onSample(sample(ts, 80.0, rpm = 2400.0, fuel = 0.0))
            ts += 1000
        }
        val event = detector.onSample(sample(ts, 80.0, throttle = 30.0, pedal = 25.0))
        assertNotNull("coast event expected", event)
        event!!
        assertEquals(CoastNeutralDetector.CoastMode.ENGINE_BRAKING_FUEL_CUT, event.mode)
        closeTo(80.0 / 3.6 * 4.0, event.distanceM, 1.0)
        assertTrue(event.fuelSavedVsIdleL > 0.0)
        assertTrue(event.fuelSavedVsCruiseL > 0.0)
        assertEquals(1, detector.summary.eventCount)
        assertEquals(1, detector.summary.fuelCutEvents)
    }

    @Test
    fun `idle rpm while moving classifies as neutral coast`() {
        val detector = CoastNeutralDetector()
        var ts = 0L
        detector.onSample(sample(ts, 60.0, throttle = 20.0))
        ts += 1000
        repeat(3) {
            detector.onSample(sample(ts, 60.0, rpm = 850.0, fuel = 0.8))
            ts += 1000
        }
        val event = detector.onSample(sample(ts, 60.0, throttle = 30.0))
        assertNotNull(event)
        assertEquals(CoastNeutralDetector.CoastMode.NEUTRAL_IDLE_COAST, event!!.mode)
        assertEquals(1, detector.summary.neutralEvents)
    }

    @Test
    fun `outside the 20 to 130 kmh window nothing is recorded`() {
        val detector = CoastNeutralDetector()
        var ts = 0L
        repeat(4) {
            detector.onSample(sample(ts, 15.0))
            ts += 1000
        }
        val slow = detector.onSample(sample(ts, 15.0, throttle = 40.0))
        assertNull(slow)
        assertEquals(0, detector.summary.eventCount)

        ts = 0L
        repeat(4) {
            detector.onSample(sample(ts, 140.0))
            ts += 1000
        }
        assertNull(detector.onSample(sample(ts, 140.0, throttle = 40.0)))
        assertEquals(0, detector.summary.eventCount)
    }

    @Test
    fun `brake pedal suppresses coasting`() {
        val detector = CoastNeutralDetector()
        var ts = 0L
        repeat(3) {
            detector.onSample(sample(ts, 70.0, brake = true))
            ts += 1000
        }
        assertNull(detector.onSample(sample(ts, 70.0, throttle = 30.0)))
        assertEquals(0, detector.summary.eventCount)
    }

    // endregion

    // region TurboAnalyzer

    @Test
    fun `boost is MAP minus baro`() {
        val turbo = TurboAnalyzer()
        val snapshot = turbo.onSample(1000, 2500.0, 130.0, 100.0, 60.0)
        closeTo(30.0, snapshot.boostKpa, 0.001)
        closeTo(30.0 * 0.145038, snapshot.boostPsi, 0.001)
        assertTrue(snapshot.isBoosting)
    }

    @Test
    fun `vacuum below atmospheric is tracked separately from boost`() {
        val turbo = TurboAnalyzer()
        turbo.onSample(1000, 1200.0, 40.0, 100.0, 5.0)
        val snapshot = turbo.onSample(2000, 3000.0, 150.0, 100.0, 80.0)
        closeTo(-60.0, snapshot.vacuumKpa, 0.001)
        closeTo(50.0, snapshot.peakBoostKpa, 0.001)
    }

    @Test
    fun `tip-in lag is measured from pedal stab to spool threshold`() {
        val turbo = TurboAnalyzer()
        var ts = 0L
        turbo.onSample(ts, 1500.0, 60.0, 100.0, 8.0)
        ts += 200
        turbo.onSample(ts, 1600.0, 70.0, 100.0, 70.0) // pedal stab
        ts += 400
        turbo.onSample(ts, 2000.0, 90.0, 100.0, 75.0) // not spooled yet (boost 90-100 = -10)
        ts += 400
        val snapshot = turbo.onSample(ts, 2600.0, 145.0, 100.0, 80.0) // boost 45 => spooled
        assertEquals(1, snapshot.tipInCount)
        assertEquals(800L, snapshot.lastLagMs)
    }

    @Test
    fun `overboost events counted above threshold`() {
        val turbo = TurboAnalyzer()
        turbo.onSample(1000, 4000.0, 270.0, 100.0, 100.0) // boost 169 > 160
        assertEquals(1, turbo.snapshot().overBoostEvents)
    }

    // endregion

    // region FuelQualityAnalyzer

    @Test
    fun `refuel starts a new tank segment`() {
        val analyzer = FuelQualityAnalyzer()
        var ts = 0L
        repeat(3) {
            analyzer.onSample(ts, 40.0, 25.0, 1.0, 1.0, 70.0, 25.0, 2000.0, 4.0)
            ts += 1000
        }
        assertEquals(1, analyzer.tanks().size)
        analyzer.onSample(ts, 85.0, 25.0, 1.0, 1.0, 0.0, 10.0, 900.0, 0.8)
        assertEquals(2, analyzer.tanks().size)
        assertNotNull(analyzer.activeTank())
        assertEquals(2, analyzer.activeTank()!!.index)
    }

    @Test
    fun `cruise timing samples accumulate only inside the cruise window`() {
        val analyzer = FuelQualityAnalyzer()
        var ts = 0L
        repeat(40) {
            analyzer.onSample(ts, 50.0, 28.0, 0.5, 0.5, 70.0, 25.0, 2000.0, 4.0)
            ts += 1000
        }
        val tank = analyzer.activeTank()!!
        assertEquals(40, tank.cruiseTimingSamples)
        closeTo(28.0, tank.avgCruiseTimingDeg, 0.001)

        // city crawling is not cruise: no extra samples
        repeat(10) {
            analyzer.onSample(ts, 20.0, 10.0, 0.5, 0.5, 20.0, 40.0, 1200.0, 2.0)
            ts += 1000
        }
        assertEquals(40, analyzer.activeTank()!!.cruiseTimingSamples)
    }

    @Test
    fun `comparison needs two qualified tanks`() {
        val analyzer = FuelQualityAnalyzer()
        assertNull(analyzer.comparisonNote())
        var ts = 0L
        repeat(40) {
            analyzer.onSample(ts, 50.0, 30.0, 0.0, 0.0, 70.0, 25.0, 2000.0, 4.0)
            ts += 1000
        }
        assertNull(analyzer.comparisonNote())
        analyzer.onSample(ts, 90.0, 30.0, 0.0, 0.0, 0.0, 10.0, 900.0, 0.8)
        ts += 1000
        repeat(40) {
            analyzer.onSample(ts, 50.0, 22.0, 4.0, 4.0, 70.0, 25.0, 2000.0, 4.5)
            ts += 1000
        }
        val note = analyzer.comparisonNote()
        assertNotNull(note)
        assertTrue(note!!.contains("better fuel"))
    }

    // endregion

    // region DriveAnalytics facade

    @Test
    fun `measured torque percent converts through reference torque`() {
        val analytics = DriveAnalytics()
        var ts = 0L
        repeat(3) {
            analytics.onSignals(
                timestampMonotonicMs = ts, speedKmh = 90.0, rpm = 2400.0, throttlePct = 80.0,
                pedalPct = 70.0, fuelRateLh = 20.0, mapKpa = 140.0, baroKpa = 100.0,
                timingDeg = 25.0, stftPct = 0.0, ltftPct = 0.0, loadPct = 60.0,
                fuelLevelPct = 50.0, chargeTempC = 45.0, wastegatePct = null,
                brakeActive = false, actualTorquePct = 50.0, demandTorquePct = 55.0,
                referenceTorqueNm = 178.0
            )
            ts += 1100
        }
        val snapshot = analytics.snapshot.value
        closeTo(89.0, snapshot.measuredTorqueNm, 0.01)
        closeTo(97.9, snapshot.demandedTorqueNm, 0.1)
        assertNotNull(snapshot.measuredPowerKw)
        assertNotNull(snapshot.measuredRpmPerKmh)
        assertTrue(snapshot.trend.isNotEmpty())
    }

    @Test
    fun `snapshot exposes sweet spot once a top-gear ratio is observed`() {
        val analytics = DriveAnalytics()
        var ts = 0L
        repeat(4) {
            analytics.onSignals(
                timestampMonotonicMs = ts, speedKmh = 100.0, rpm = 2200.0, throttlePct = 30.0,
                pedalPct = 25.0, fuelRateLh = 5.0, mapKpa = 80.0, baroKpa = 100.0,
                timingDeg = 28.0, stftPct = 0.0, ltftPct = 0.0, loadPct = 30.0,
                fuelLevelPct = 50.0, chargeTempC = null, wastegatePct = null,
                brakeActive = false, actualTorquePct = null, demandTorquePct = null,
                referenceTorqueNm = null
            )
            ts += 1100
        }
        val snapshot = analytics.snapshot.value
        assertNotNull(snapshot.measuredRpmPerKmh)
        assertNotNull(snapshot.sweetSpotKmh)
        assertTrue(snapshot.fuelVsSpeedModel.isNotEmpty())
        assertTrue(snapshot.factoryTorqueCurve.isNotEmpty())
    }

    // endregion
}
