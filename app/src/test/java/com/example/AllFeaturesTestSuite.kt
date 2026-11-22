package com.example

import com.example.analysis.BatteryCrankProfiler
import com.example.analysis.DfcoCoach
import com.example.analysis.OctaneRoiAnalyzer
import com.example.analysis.OctaneRoiAnalyzer.Fuel
import com.example.analysis.OctaneRoiAnalyzer.FuelLog
import com.example.analysis.ValetSurveillanceManager
import com.example.engine.AccelerationPerformanceMeter
import com.example.engine.TransmissionSlipAnalyzer
import com.example.engine.TurboCooldownGuardian
import com.example.ui.components.FillTripDraft
import com.example.ui.components.PollenFilterTask
import com.example.ui.components.ServiceInspectionRules
import com.example.ui.components.TripLogTab
import org.junit.Assert.*
import org.junit.Test

/** Deterministic telemetry traces: no Android, wall clock, sleeps or vehicle required. */
class AllFeaturesTestSuite {
    @Test fun turboStrictThresholds() {
        val g = TurboCooldownGuardian()
        assertEquals(0, g.update(0, 0.5, 3000.0, 100.0).cooldownSeconds)
        assertEquals(0, g.update(1, 0.6, 2500.0, 100.0).cooldownSeconds)
        assertEquals(0, g.update(2, 0.6, 3000.0, 95.0).cooldownSeconds)
        assertEquals(15, g.update(3, 0.6, 3000.0, 96.0).cooldownSeconds)
    }
    @Test fun turboLoadIsDynamicAndBounded() {
        val g = TurboCooldownGuardian()
        for (t in 0L..90_000L step 1000) g.update(t, 0.8, 3200.0, 101.0)
        assertEquals(60, g.update(91_000, 0.8, 3200.0, 101.0).cooldownSeconds)
        g.reset()
        g.update(0, 0.8, 3200.0, 101.0)
        assertEquals(20, g.update(5000, 0.8, 3200.0, 101.0).cooldownSeconds)
    }
    @Test fun turboIdleDischargesButShutdownDoesNot() {
        val g = TurboCooldownGuardian()
        g.update(0, 0.8, 3000.0, 100.0)
        for (t in 1000L..20_000L step 1000) g.update(t, 0.0, 0.0, 100.0)
        assertEquals(15, g.update(21_000, 0.0, 800.0, 100.0).cooldownSeconds)
        for (t in 22_000L..36_000L step 1000) g.update(t, 0.0, 800.0, 100.0)
        assertEquals(0, g.update(37_000, 0.0, 800.0, 100.0).cooldownSeconds)
    }
    @Test fun turboMissingAndGapCannotCool() {
        val g = TurboCooldownGuardian()
        g.update(0, 0.8, 3000.0, 100.0)
        g.update(1000, null, 800.0, 100.0)
        assertEquals(15, g.update(2000, 0.0, 800.0, 100.0).cooldownSeconds)
        assertEquals(15, g.update(20_000, 0.0, 800.0, 100.0).cooldownSeconds)
    }
    @Test fun turboInvalidAndStaleSamples() {
        val g = TurboCooldownGuardian()
        assertFalse(g.update(0, Double.NaN, 3000.0, 100.0).highLoad)
        assertFalse(g.update(1, 0.8, Double.POSITIVE_INFINITY, 100.0).highLoad)
        val hot = g.update(100, 0.8, 3000.0, 100.0)
        assertEquals(hot, g.update(100, 0.0, 800.0, 90.0))
        assertEquals(hot, g.update(-1, 0.0, 800.0, 90.0))
        g.reset()
        assertEquals(0, g.update(0, 0.0, 800.0, 90.0).cooldownSeconds)
    }

    private fun expected(speed: Double, gear: Int, circumference: Double = 2.0) =
        speed / 3.6 / circumference * 60.0 * 3.867 * TransmissionSlipAnalyzer.GEAR_RATIOS[gear - 1]
    @Test fun transmissionAllSixGearRatios() {
        assertEquals(4.044, TransmissionSlipAnalyzer.GEAR_RATIOS.first(), 0.0)
        assertEquals(0.672, TransmissionSlipAnalyzer.GEAR_RATIOS.last(), 0.0)
        for (gear in 1..6) {
            val a = TransmissionSlipAnalyzer(2.0)
            val rpm = expected(30.0, gear)
            val r = a.update(0, rpm, 30.0, gear, converterLocked = true)
            assertEquals(rpm, r.expectedRpm!!, 1e-9)
            assertEquals(0.0, r.slipPercent!!, 1e-9)
            assertFalse(r.excessiveLockedSlip)
        }
    }
    @Test fun transmissionMeasuresSignedSlip() {
        val a = TransmissionSlipAnalyzer(2.0)
        val rpm = expected(60.0, 4)
        val r = a.update(0, rpm * 1.2, 60.0, 4, converterLocked = true)
        assertEquals(20.0, r.slipPercent!!, 1e-8)
        assertEquals(rpm * 0.2, r.slipRpm!!, 1e-8)
        assertTrue(r.excessiveLockedSlip)
        assertEquals(-20.0, a.update(100, rpm * 0.8, 60.0, 4).slipPercent!!, 1e-8)
    }
    @Test fun normalUnlockedConverterIsNotFaulted() {
        val a = TransmissionSlipAnalyzer(2.0)
        assertFalse(a.update(0, 3000.0, 20.0, 2).excessiveLockedSlip)
    }
    @Test fun transmissionRequiresTcuGearAndRoadSpeed() {
        val a = TransmissionSlipAnalyzer(2.0)
        assertNull(a.update(0, 2000.0, 50.0, 4, ecu = "7E8").slipRpm)
        assertNull(a.update(1, 2000.0, 50.0, null).slipRpm)
        assertNull(a.update(2, 2000.0, 50.0, 7).slipRpm)
        assertNull(a.update(3, 2000.0, 0.0, 1).slipRpm)
        assertNull(a.update(4, Double.NaN, 50.0, 4).slipRpm)
    }
    @Test fun transmissionThermalStressIntegratesAtStops() {
        val a = TransmissionSlipAnalyzer(2.0)
        a.update(0, 800.0, 0.0, 1, atfTemperatureC = 110.0)
        assertEquals(15.0, a.update(1000, 800.0, 0.0, 1, atfTemperatureC = 120.0).thermalStressDegreeSeconds, 1e-8)
        assertEquals(25.0, a.update(2000, 800.0, 0.0, 1, atfTemperatureC = 80.0).thermalStressDegreeSeconds, 1e-8)
    }
    @Test fun transmissionNoInventedAtfStress() {
        val a = TransmissionSlipAnalyzer(2.0)
        a.update(0, 2000.0, 40.0, 3, atfTemperatureC = 120.0)
        a.update(1000, 2000.0, 40.0, 3)
        assertEquals(0.0, a.update(2000, 2000.0, 40.0, 3, atfTemperatureC = 120.0).thermalStressDegreeSeconds, 0.0)
        assertEquals(0.0, a.update(20_000, 2000.0, 40.0, 3, atfTemperatureC = 120.0).thermalStressDegreeSeconds, 0.0)
        assertEquals(0.0, a.update(21_000, 2000.0, 40.0, 3, ecu = "7E8", atfTemperatureC = 120.0).thermalStressDegreeSeconds, 0.0)
    }
    @Test fun transmissionStaleAndReset() {
        val a = TransmissionSlipAnalyzer(2.0)
        val r = a.update(100, 2000.0, 60.0, 4)
        assertEquals(r, a.update(99, null, null, null))
        assertEquals(r, a.update(100, null, null, null))
        a.reset()
        assertEquals(0.0, a.update(0, null, null, null).thermalStressDegreeSeconds, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun transmissionRejectsBadTyre() { TransmissionSlipAnalyzer(Double.NaN) }
    @Test(expected = IllegalArgumentException::class) fun transmissionRejectsZeroTyre() { TransmissionSlipAnalyzer(0.0) }

    @Test fun dfcoExactZeroAndStrictConditions() {
        val c = DfcoCoach(100.0)
        assertFalse(c.update(0, 0.0, 1200.0, 0.0, true).active)
        assertFalse(c.update(1, 0.001, 1500.0, 0.0, true).active)
        assertFalse(c.update(2, 0.0, 1500.0, 0.1, true).active)
        assertFalse(c.update(3, 0.0, 1500.0, 0.0, false).active)
        assertTrue(c.update(4, 0.0, 1500.0, 0.0, true).active)
    }
    @Test fun dfcoLiveRupeesAndAccumulatedSavings() {
        val c = DfcoCoach(108.0, 0.6)
        c.update(0, 0.0, 2000.0, 0.0, true)
        var r = c.update(1000, 0.0, 2000.0, 0.0, true)
        for (t in 2000L..60_000L step 1000) r = c.update(t, 0.0, 2000.0, 0.0, true)
        assertEquals(60.0, r.glideSeconds, 0.0)
        assertEquals(0.01, r.estimatedSavedLitres, 1e-9)
        assertEquals(1.08, r.estimatedSavedRupees, 1e-9)
        assertEquals(1.08, r.rupeesPerMinute, 1e-9)
        assertEquals(0.0, c.update(61_000, 0.2, 2000.0, 5.0, true).rupeesPerMinute, 0.0)
    }
    @Test fun dfcoNoCreditAcrossEntryExitOrGap() {
        val c = DfcoCoach(100.0)
        c.update(0, 1.0, 2000.0, 0.0, true)
        assertEquals(0.0, c.update(1000, 0.0, 2000.0, 0.0, true).glideSeconds, 0.0)
        assertEquals(1.0, c.update(2000, 0.0, 2000.0, 0.0, true).glideSeconds, 0.0)
        assertEquals(1.0, c.update(20_000, 0.0, 2000.0, 0.0, true).glideSeconds, 0.0)
        assertEquals(1.0, c.update(21_000, null, 2000.0, 0.0, true).glideSeconds, 0.0)
        assertEquals(1.0, c.update(22_000, 0.0, 2000.0, 0.0, true).glideSeconds, 0.0)
    }
    @Test fun dfcoMissingAndNonfiniteNotFuelCut() {
        val c = DfcoCoach(100.0)
        assertFalse(c.update(0, Double.NaN, 2000.0, 0.0, true).active)
        assertFalse(c.update(1, -1.0, 2000.0, 0.0, true).active)
        assertFalse(c.update(2, 0.0, Double.NaN, 0.0, true).active)
        assertFalse(c.update(3, 0.0, 2000.0, null, true).active)
    }
    @Test fun dfcoStaleAndReset() {
        val c = DfcoCoach(100.0)
        val r = c.update(100, 0.0, 2000.0, 0.0, true)
        assertEquals(r, c.update(99, null, null, null, false))
        assertEquals(r, c.update(100, null, null, null, false))
        c.reset()
        assertEquals(0.0, c.update(0, 0.0, 2000.0, 0.0, true).glideSeconds, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun dfcoRejectsPrice() { DfcoCoach(-1.0) }
    @Test(expected = IllegalArgumentException::class) fun dfcoRejectsBaseline() { DfcoCoach(100.0, Double.POSITIVE_INFINITY) }

    @Test fun octanePositiveRoiAndBreakEven() {
        val r = OctaneRoiAnalyzer().analyze(listOf(
            FuelLog(Fuel.E20, 150.0, 10.0, 100.0, 5.0, 3.0),
            FuelLog(Fuel.XP95, 180.0, 10.0, 110.0, 8.0, 1.0)))
        assertEquals(15.0, r.regular!!.kmPerLitre, 0.0)
        assertEquals(18.0, r.premium!!.kmPerLitre, 0.0)
        assertEquals(1000.0 / 150.0 - 1100.0 / 180.0, r.premiumSavingsRupeesPerKm!!, 1e-9)
        assertEquals(120.0, r.premiumBreakEvenPricePerLitre!!, 1e-9)
        assertEquals(8.0, r.premium.meanTimingAdvanceDegrees!!, 0.0)
        assertEquals(1.0, r.premium.meanFuelTrimPercent!!, 0.0)
    }
    @Test fun octanePremiumMayCostMore() {
        val r = OctaneRoiAnalyzer().analyze(listOf(FuelLog(Fuel.E20, 100.0, 10.0, 100.0), FuelLog(Fuel.XP95, 100.0, 10.0, 110.0)))
        assertEquals(-1.0, r.premiumSavingsRupeesPerKm!!, 0.0)
    }
    @Test fun octaneWeightsFuelAndDistanceNotAverageMileage() {
        val r = OctaneRoiAnalyzer().analyze(listOf(
            FuelLog(Fuel.E20, 100.0, 10.0, 100.0, 0.0, -5.0),
            FuelLog(Fuel.E20, 300.0, 10.0, 120.0, 8.0, 3.0)))
        assertEquals(20.0, r.regular!!.kmPerLitre, 0.0)
        assertEquals(5.5, r.regular.rupeesPerKm, 0.0)
        assertEquals(6.0, r.regular.meanTimingAdvanceDegrees!!, 0.0)
        assertEquals(1.0, r.regular.meanFuelTrimPercent!!, 0.0)
        assertEquals(2, r.regular.logCount)
    }
    @Test fun octaneMissingFuelHasNoRoi() {
        val a = OctaneRoiAnalyzer()
        assertNull(a.analyze(emptyList()).premiumSavingsRupeesPerKm)
        assertNull(a.analyze(listOf(FuelLog(Fuel.E20, 100.0, 10.0, 100.0))).premiumSavingsRupeesPerKm)
    }
    @Test fun octaneInvalidLogsIgnored() {
        val r = OctaneRoiAnalyzer().analyze(listOf(
            FuelLog(Fuel.E20, 0.0, 10.0, 100.0), FuelLog(Fuel.E20, 100.0, 0.0, 100.0),
            FuelLog(Fuel.E20, Double.NaN, 10.0, 100.0), FuelLog(Fuel.E20, 100.0, 10.0, -1.0),
            FuelLog(Fuel.E20, 100.0, Double.POSITIVE_INFINITY, 100.0)))
        assertNull(r.regular)
    }
    @Test fun octaneUnknownTimingNotZero() {
        val r = OctaneRoiAnalyzer().analyze(listOf(FuelLog(Fuel.XP95, 100.0, 5.0, 110.0, Double.NaN)))
        assertNull(r.premium!!.meanTimingAdvanceDegrees)
        assertNull(r.premium.meanFuelTrimPercent)
    }

    @Test fun accelerationAutoLaunchAndInterpolatedSplits() {
        val a = AccelerationPerformanceMeter()
        assertEquals(AccelerationPerformanceMeter.State.ARMED, a.update(0, 0.0).state)
        a.update(500, 20.0, 0.4)
        a.update(1000, 50.0, 0.9)
        val sixty = a.update(1500, 70.0, 0.7)
        assertEquals(1.25, sixty.zeroTo60Seconds!!, 1e-9)
        a.update(2000, 90.0, 0.8)
        val r = a.update(2500, 110.0, 0.6)
        assertEquals(2.25, r.zeroTo100Seconds!!, 1e-9)
        assertEquals(0.9, r.peakBoostBar!!, 0.0)
        assertEquals(AccelerationPerformanceMeter.State.COMPLETE, r.state)
    }
    @Test fun accelerationCannotLaunchAlreadyMoving() {
        val a = AccelerationPerformanceMeter()
        assertEquals(AccelerationPerformanceMeter.State.IDLE, a.update(0, 30.0).state)
        assertEquals(AccelerationPerformanceMeter.State.IDLE, a.update(100, 100.0).state)
    }
    @Test fun accelerationBothSplitsWithinSameSample() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0)
        val r = a.update(1000, 120.0)
        assertEquals(0.5, r.zeroTo60Seconds!!, 1e-9)
        assertEquals(100.0 / 120.0, r.zeroTo100Seconds!!, 1e-9)
    }
    @Test fun accelerationGapAborts() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0)
        assertEquals(AccelerationPerformanceMeter.State.ABORTED, a.update(1001, 30.0).state)
        assertNull(a.update(2000, 100.0).zeroTo100Seconds)
    }
    @Test fun accelerationStopAborts() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0); a.update(100, 10.0)
        assertEquals(AccelerationPerformanceMeter.State.ABORTED, a.update(200, 0.0).state)
    }
    @Test fun accelerationInvalidSpeedAborts() {
        for (bad in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val a = AccelerationPerformanceMeter()
            a.update(0, 0.0)
            assertEquals(AccelerationPerformanceMeter.State.ABORTED, a.update(100, bad).state)
        }
    }
    @Test fun accelerationStaleIgnoredAndExplicitReset() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0)
        val r = a.update(100, 30.0)
        assertEquals(r, a.update(99, 100.0))
        assertEquals(r, a.update(100, 100.0))
        a.reset()
        assertEquals(AccelerationPerformanceMeter.State.ARMED, a.update(0, 0.0).state)
    }
    @Test fun accelerationCompletedRunIsPreserved() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0)
        val r = a.update(1000, 100.0, 0.8)
        assertEquals(r, a.update(2000, 0.0, 2.0))
    }
    @Test fun accelerationMissingBoostIsUnknown() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0)
        assertNull(a.update(100, 20.0, Double.NaN).peakBoostBar)
    }
    @Test fun accelerationTimeoutAborts() {
        val a = AccelerationPerformanceMeter()
        a.update(0, 0.0)
        for (t in 1000L..120_000L step 1000) a.update(t, 10.0)
        assertEquals(AccelerationPerformanceMeter.State.ABORTED, a.update(121_000, 10.0).state)
    }

    private fun crank(min: Double): BatteryCrankProfiler.Result {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0)
        b.update(100, min, 200.0)
        return b.update(200, 14.4, 800.0)
    }
    @Test fun batteryRequestedWeakCrankTrace() {
        val r = crank(9.4)
        assertEquals(BatteryCrankProfiler.Phase.COMPLETE, r.phase)
        assertEquals(12.6, r.restingVoltage!!, 0.0)
        assertEquals(9.4, r.minimumCrankVoltage!!, 0.0)
        assertEquals(14.4, r.chargingVoltage!!, 0.0)
        assertEquals(0, r.healthPercent!!)
        assertTrue(r.weakBatteryWarning)
    }
    @Test fun batteryScoreBoundedAndMonotonic() {
        assertEquals(0, crank(8.0).healthPercent!!)
        assertEquals(100, crank(11.5).healthPercent!!)
        assertTrue(crank(10.5).healthPercent!! > crank(10.0).healthPercent!!)
        assertFalse(crank(11.0).weakBatteryWarning)
    }
    @Test fun batteryTracksMinimumAcrossCrank() {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0); b.update(100, 10.8, 200.0)
        b.update(200, 9.8, 250.0); b.update(300, 10.0, 350.0)
        assertEquals(9.8, b.update(400, 14.4, 800.0).minimumCrankVoltage!!, 0.0)
    }
    @Test fun batteryAccessorySagAndAlreadyRunningNotCrank() {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0); b.update(100, 9.4, 0.0)
        assertNull(b.update(200, 14.4, 800.0).healthPercent)
        b.reset()
        assertNull(b.update(0, 14.4, 800.0).healthPercent)
    }
    @Test fun batteryMissingPrecrankIsUnknown() {
        val b = BatteryCrankProfiler()
        b.update(0, 9.4, 200.0)
        assertNull(b.update(100, 14.4, 800.0).healthPercent)
    }
    @Test fun batteryGapInvalidatesEvidence() {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0); b.update(100, 9.4, 200.0)
        assertNull(b.update(3000, 14.4, 800.0).healthPercent)
    }
    @Test fun batteryNonfiniteBreaksCrank() {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0); b.update(100, 9.4, 200.0)
        b.update(200, Double.NaN, 300.0)
        assertNull(b.update(300, 14.4, 800.0).healthPercent)
        b.reset()
        assertEquals(BatteryCrankProfiler.Phase.WAITING, b.update(0, 12.6, Double.NaN).phase)
    }
    @Test fun batteryFailedCrankDoesNotScore() {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0); b.update(100, 9.4, 200.0)
        assertNull(b.update(200, 12.0, 0.0).healthPercent)
    }
    @Test fun batteryStaleAndReset() {
        val b = BatteryCrankProfiler()
        val r = b.update(100, 12.6, 0.0)
        assertEquals(r, b.update(100, 9.4, 200.0))
        assertEquals(r, b.update(99, 9.4, 200.0))
        b.reset()
        assertNull(b.update(0, 14.4, 800.0).healthPercent)
    }
    @Test fun batteryCompleteProfileIsStable() {
        val b = BatteryCrankProfiler()
        b.update(0, 12.6, 0.0); b.update(100, 10.5, 200.0)
        val r = b.update(200, 14.4, 800.0)
        assertEquals(r, b.update(300, 12.0, 0.0))
    }

    @Test fun valetOptInAndStrictThresholds() {
        val v = ValetSurveillanceManager()
        assertTrue(v.update(0, 4000.0, 50.0, 80.0).isEmpty())
        v.start()
        assertTrue(v.update(0, 3200.0, 60.0, 55.0).isEmpty())
        assertTrue(v.update(1, 4000.0, 70.0, 55.0).isEmpty())
        assertEquals(2, v.update(2, 3201.0, 69.9, 55.1).size)
    }
    @Test fun valetEpisodesDeduplicateAndCapturePeak() {
        val v = ValetSurveillanceManager(); v.start()
        v.update(0, 3300.0, 60.0, 60.0)
        val r = v.update(1000, 4000.0, 65.0, 80.0)
        assertEquals(2, r.size)
        assertEquals(4000.0, r.first().peakValue, 0.0)
        assertEquals(1000L, r.first().endMs)
        assertEquals(80.0, r.last().peakValue, 0.0)
    }
    @Test fun valetComplianceEndsEpisode() {
        val v = ValetSurveillanceManager(); v.start()
        v.update(0, 4000.0, 60.0, 80.0)
        v.update(1000, 1000.0, 80.0, 30.0)
        assertEquals(4, v.update(2000, 4000.0, 60.0, 80.0).size)
    }
    @Test fun valetHotRevvingNotColdAbuse() {
        val v = ValetSurveillanceManager(); v.start()
        assertTrue(v.update(0, 5000.0, 90.0, 50.0).isEmpty())
    }
    @Test fun valetUnknownChannelsIndependent() {
        val v = ValetSurveillanceManager(); v.start()
        val r = v.update(0, Double.NaN, null, 70.0)
        assertEquals(1, r.size)
        assertEquals(ValetSurveillanceManager.Type.SPEEDING, r.first().type)
        assertEquals(1, v.update(100, null, null, Double.POSITIVE_INFINITY).size)
        assertEquals(2, v.update(200, null, null, 70.0).size)
    }
    @Test fun valetReconnectCreatesNewEpisode() {
        val v = ValetSurveillanceManager(); v.start()
        v.update(0, 4000.0, 60.0, 80.0)
        assertEquals(4, v.update(6000, 4000.0, 60.0, 80.0).size)
    }
    @Test fun valetStaleAndStoppedDoNotMutateAudit() {
        val v = ValetSurveillanceManager(); v.start()
        val r = v.update(100, 4000.0, 60.0, 80.0)
        assertEquals(r, v.update(99, 5000.0, 60.0, 100.0))
        assertEquals(r, v.update(100, 5000.0, 60.0, 100.0))
        assertEquals(r, v.stop())
        assertEquals(r, v.update(200, 5000.0, 60.0, 100.0))
    }
    @Test fun valetBoundedAuditAndSnapshotIsolation() {
        val v = ValetSurveillanceManager(2); v.start()
        val snapshot = v.update(0, 4000.0, 60.0, 80.0)
        v.update(100, 1000.0, 80.0, 20.0)
        val r = v.update(200, 4500.0, 60.0, 90.0)
        assertEquals(2, r.size)
        assertEquals(200L, r.first().startMs)
        assertEquals(0L, snapshot.first().startMs)
        v.start()
        assertTrue(v.events().isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun valetRejectsZeroCapacity() { ValetSurveillanceManager(0) }

    // ── FillMissingTripDialog: Service Inspection tab, pollen tagging, invoice ──────────
    // The dialog's rules live in `ui/components/ManualTrip.kt` precisely so they are testable
    // here: no Android, no Compose, no Robolectric, deterministic clock.

    private val dayMs = 24L * 60 * 60 * 1000
    private val nowMs = 1_792_000_000_000L // fixed "today"

    private fun serviceDraft(
        itemId: String? = PollenFilterTask.ITEM_ID,
        odometer: Double? = 41_250.0,
        cost: Double? = 1250.5,
        uri: String? = "content://com.android.providers.downloads.documents/document/42",
        name: String? = "kylaq-service-invoice.pdf",
        notes: String = "Cabin filter replaced at workshop",
        pollen: Boolean = false,
        dateMs: Long = nowMs - 3 * dayMs
    ) = FillTripDraft(
        notes = notes,
        endOdoKm = odometer ?: 0.0,
        serviceItemId = itemId,
        serviceDateMs = dateMs,
        invoiceCostRupees = cost,
        invoicePdfUri = uri,
        invoicePdfName = name,
        pollenTaskTagged = pollen
    )

    @Test fun dialogOffersExactlyTheTwoLogTabs() {
        assertEquals(2, TripLogTab.values().size)
        assertEquals("Trip", TripLogTab.TRIP.label)
        assertEquals("Service Inspection", TripLogTab.SERVICE.label)
    }

    @Test fun serviceTabWritesOneLedgerEntry() {
        val request = serviceDraft().toServiceRequest(nowMs)!!
        assertEquals(1, request.entries.size)
        val entry = request.entries.single()
        assertEquals(PollenFilterTask.ITEM_ID, entry.itemId)
        assertEquals(41_250.0, entry.odometerKm!!, 0.0)
        assertEquals(1250.5, entry.invoiceCostRupees!!, 0.0)
        assertEquals(1250.5, request.totalInvoiceCostRupees, 0.0)
        assertTrue(request.hasInvoice)
        // Invoice reference survives into the ledger note without the raw file path noise.
        assertTrue(entry.toServiceNotes().contains("invoice: kylaq-service-invoice.pdf"))
        assertTrue(entry.toServiceNotes().contains("1250.50"))
    }

    @Test fun serviceTabNeverFabricatesADrive() {
        // Only the trip payload can create a drive; a service inspection produces none and
        // with no odometer typed there is not even a distance to speak of.
        val draft = serviceDraft(odometer = null)
        assertEquals(0.0, draft.distanceKm, 0.0)
        assertNotNull(draft.toServiceRequest(nowMs))
        // A pure service entry with no odometer typed must not invent one (and must not
        // overwrite the real reading with zero).
        val noOdo = serviceDraft(odometer = null).toServiceRequest(nowMs)!!
        assertNull(noOdo.odometerKm)
        assertNull(noOdo.entries.single().odometerKm)
    }

    @Test fun serviceTabRequiresATask() {
        assertNull(serviceDraft(itemId = null).toServiceEntry(nowMs))
        assertNull(serviceDraft(itemId = "").toServiceRequest(nowMs))
        val invalid = serviceDraft(itemId = null).validateService(nowMs)
        assertFalse(invalid.ok)
        assertTrue(invalid.message.contains("task", ignoreCase = true))
    }

    @Test fun pollenFilterTaskTagIsScopedAndIdempotent() {
        assertTrue(PollenFilterTask.appliesTo("cabin_filter"))
        assertFalse(PollenFilterTask.appliesTo("engine_oil"))

        // Tagging only shows for, and only sticks to, the cabin/pollen filter task.
        assertTrue(serviceDraft(itemId = "cabin_filter").pollenTagAvailable)
        assertFalse(serviceDraft(itemId = "engine_oil").pollenTagAvailable)
        val wrongTask = serviceDraft(itemId = "engine_oil", pollen = true).toServiceEntry(nowMs)!!
        assertFalse(PollenFilterTask.isTagged(wrongTask.notes))
        assertFalse(wrongTask.notes.contains(PollenFilterTask.NOTE_TAG))

        val tagged = serviceDraft(pollen = true).toServiceEntry(nowMs)!!
        assertTrue(PollenFilterTask.isTagged(tagged.notes))
        assertTrue(tagged.notes.startsWith("Cabin filter replaced at workshop"))
        assertEquals(1, tagged.notes.split(PollenFilterTask.NOTE_TAG).size - 1)

        // Re-saving an already tagged note must not stack markers.
        val twice = PollenFilterTask.tagNote(tagged.notes, true)
        assertEquals(tagged.notes, twice)
        assertEquals(1, twice.split(PollenFilterTask.NOTE_TAG).size - 1)
    }

    @Test fun pollenTagCanBeRemovedWithoutLosingTheNote() {
        val clean = PollenFilterTask.untagNote("Cabin filter replaced" + " " + PollenFilterTask.NOTE_TAG)
        assertEquals("Cabin filter replaced", clean)
        assertEquals("", PollenFilterTask.untagNote(null))
        assertEquals(PollenFilterTask.NOTE_TAG, PollenFilterTask.tagNote(null, true))
        assertEquals("", PollenFilterTask.tagNote("", false))
    }

    @Test fun invoiceCostParserAcceptsRealInvoices() {
        assertEquals(1250.5, ServiceInspectionRules.parseInvoiceCost("1250.50")!!, 1e-9)
        assertEquals(1250.5, ServiceInspectionRules.parseInvoiceCost("\u20b91,250.50")!!, 1e-9)
        assertEquals(1250.0, ServiceInspectionRules.parseInvoiceCost("Rs. 1250")!!, 1e-9)
        assertEquals(1250.0, ServiceInspectionRules.parseInvoiceCost("INR 1 250")!!, 1e-9)
        assertEquals(0.0, ServiceInspectionRules.parseInvoiceCost("0")!!, 0.0)
    }

    @Test fun invoiceCostParserRefusesToInventAnAmount() {
        // Blank/unknown stays unknown - a wrong 0.0 would silently poison cost per km.
        for (bad in listOf(null, "", "   ", "abc", "-1", "12.3.4", "\u20b9")) {
            assertNull("expected null for '$bad'", ServiceInspectionRules.parseInvoiceCost(bad))
        }
        assertNull(ServiceInspectionRules.parseInvoiceCost("NaN"))
        assertNull(ServiceInspectionRules.parseInvoiceCost("Infinity"))
        assertNull(ServiceInspectionRules.parseInvoiceCost((ServiceInspectionRules.MAX_INVOICE_RUPEES + 1.0).toString()))
    }

    @Test fun invoiceAttachmentMustBeAPdfDocument() {
        assertEquals("application/pdf", ServiceInspectionRules.PDF_MIME)
        assertTrue(ServiceInspectionRules.PDF_MIME_TYPES.contains("application/pdf"))
        assertEquals(
            "content://docs/1",
            ServiceInspectionRules.normalizePdfUri(" content://docs/1 ")
        )
        assertEquals("file:///sdcard/invoice.pdf", ServiceInspectionRules.normalizePdfUri("file:///sdcard/invoice.pdf"))
        for (bad in listOf(null, "", "   ", "/sdcard/invoice.pdf", "https://example.com/a.pdf")) {
            assertNull("expected null for '$bad'", ServiceInspectionRules.normalizePdfUri(bad))
        }
    }

    @Test fun invoiceNameFallsBackToTheUri() {
        assertEquals("My Bill", ServiceInspectionRules.pdfDisplayName(" ", "content://p/My%20Bill"))
        assertEquals("My Bill", ServiceInspectionRules.pdfDisplayName(null, "content://p/My%20Bill"))
        assertEquals("bill-2026.pdf", ServiceInspectionRules.pdfDisplayName("bill-2026.pdf", "content://p/ignored"))
        assertNull(ServiceInspectionRules.pdfDisplayName(null, null))
        assertEquals("invoice.pdf \u00b7 \u20b91250.50", ServiceInspectionRules.invoiceSummary(null, 1250.5))
        assertEquals("invoice.pdf", ServiceInspectionRules.invoiceSummary(null, null))
        assertEquals("invoice.pdf \u00b7 \u20b91250.50", ServiceInspectionRules.invoiceSummary("invoice.pdf", 1250.5))
    }

    @Test fun invoiceAttachmentIsOptionalAndValidatedWhenPresent() {
        val noInvoice = serviceDraft(cost = null, uri = null, name = null).toServiceRequest(nowMs)!!
        assertFalse(noInvoice.hasInvoice)
        assertFalse(noInvoice.entries.single().hasInvoice)
        assertFalse(noInvoice.entries.single().toServiceNotes().contains("invoice:"))

        val bad = serviceDraft(uri = "https://example.com/bill.pdf")
        assertFalse(bad.validateService(nowMs).ok)
        assertTrue(bad.validateService(nowMs).message.contains("PDF"))

        val tooFarFuture = serviceDraft(dateMs = nowMs + 5 * dayMs)
        assertFalse(tooFarFuture.validateService(nowMs).ok)
        assertTrue(tooFarFuture.validateService(nowMs).message.contains("future"))
    }

    @Test fun invoiceRidesAlongWithATripWithoutChangingItsMaths() {
        val draft = FillTripDraft(
            title = "Missed Drive",
            startOdoKm = 41_000.0,
            endOdoKm = 41_012.0,
            startMs = nowMs,
            durationSeconds = 1800,
            notes = "workshop run",
            invoiceCostRupees = 950.0,
            invoicePdfUri = "content://docs/9",
            invoicePdfName = "bill.pdf"
        )
        val trip = draft.toManualTripRequest()
        assertEquals(12.0, trip.distanceKm, 1e-9)
        assertTrue(trip.notes.contains("workshop run"))
        assertTrue(trip.notes.contains("Service invoice"))
        assertTrue(trip.notes.contains("bill.pdf"))
        // The invoice is annotation only: it must not become a "service" record on the trip tab.
        assertFalse(trip.notes.contains(PollenFilterTask.NOTE_TAG))
    }

    @Test fun serviceNoteIsCappedAtTheLedgerLimit() {
        val long = "x".repeat(400)
        val entry = serviceDraft(notes = long).toServiceEntry(nowMs)!!
        assertEquals(ServiceInspectionRules.MAX_NOTE_CHARS, entry.toServiceNotes().length)
        assertEquals(140, ServiceInspectionRules.MAX_NOTE_CHARS)
    }

    @Test fun serviceDateIsRequiredButToleratesOneDaySkew() {
        assertTrue(serviceDraft(dateMs = nowMs + dayMs / 2).validateService(nowMs).ok)
        assertFalse(serviceDraft(dateMs = 0L).validateService(nowMs).ok)
    }
}
