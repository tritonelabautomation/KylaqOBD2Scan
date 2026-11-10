package com.example

import com.example.analysis.DriveAnalytics
import com.example.data.MaintenanceCatalog
import com.example.engine.BatteryCrankProfiler
import com.example.engine.DfcoCoach
import com.example.engine.OctaneRoiAnalyzer
import com.example.engine.PowertrainModel
import com.example.engine.TransmissionSlipAnalyzer
import com.example.engine.TurboCooldownGuardian
import com.example.engine.ValetSurveillanceManager
import com.example.model.GapTripType
import com.example.model.composeGapTripNotes
import com.example.ui.components.ManualTripRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Full coverage for the Android-skills gap-fill dialog extras and the six powertrain /
 * telemetry engines (turbo cooldown, converter slip, DFCO rupee coach, E20/XP95 ROI,
 * crank-voltage health, valet audit).
 */
class AllFeaturesTestSuite {

    private fun closeTo(expected: Double, actual: Double?, tol: Double, label: String = "") {
        assertNotNull("$label was null", actual)
        assertTrue(
            "$label expected $expected ±$tol but was $actual",
            abs(expected - (actual ?: Double.NaN)) <= tol
        )
    }

    // region GapTripType / service inspection notes

    @Test
    fun `SERVICE_INSPECTION exists with pollen filter catalog id`() {
        assertEquals("cabin_filter", GapTripType.POLLEN_FILTER_ITEM_ID)
        assertEquals("Service / inspection", GapTripType.SERVICE_INSPECTION.label)
        assertEquals("Service Inspection", GapTripType.SERVICE_INSPECTION.defaultTitle)
        val ids = MaintenanceCatalog.KYLAQ_ITEMS.map { it.id }
        assertTrue(ids.contains(GapTripType.POLLEN_FILTER_ITEM_ID))
        assertEquals(
            "Cabin / pollen filter",
            MaintenanceCatalog.KYLAQ_ITEMS.first { it.id == "cabin_filter" }.label
        )
    }

    @Test
    fun `composeGapTripNotes folds pollen filter invoice and pdf`() {
        val notes = composeGapTripNotes(
            notes = "Workshop visit",
            type = GapTripType.SERVICE_INSPECTION,
            pollenFilterServiced = true,
            invoiceCost = 1850.0,
            invoicePdfUri = "content://invoices/pollen.pdf"
        )
        assertTrue(notes.contains("Workshop visit"))
        assertTrue(notes.contains("Service inspection"))
        assertTrue(notes.contains("pollen filter replaced"))
        assertTrue(notes.contains("invoice ₹1850"))
        assertTrue(notes.contains("invoicePdf=content://invoices/pollen.pdf"))
    }

    @Test
    fun `composeGapTripNotes is empty extras for missed drive`() {
        assertEquals("just a gap", composeGapTripNotes("just a gap", GapTripType.MISSED_DRIVE, true, 99.0, "x"))
        assertEquals("", composeGapTripNotes("  ", GapTripType.MISSED_DRIVE))
        assertEquals(
            "Service inspection; pollen filter replaced",
            composeGapTripNotes("", GapTripType.SERVICE_INSPECTION, pollenFilterServiced = true)
        )
    }

    @Test
    fun `ManualTripRequest defaults and service fields`() {
        val missed = ManualTripRequest(
            title = "Gap",
            startOdometerKm = 1000.0,
            endOdometerKm = 1012.5,
            startTimestampMs = 1_700_000_000_000L,
            durationSeconds = 1800L
        )
        assertEquals(GapTripType.MISSED_DRIVE, missed.gapTripType)
        assertFalse(missed.pollenFilterServiced)
        assertNull(missed.invoiceCost)
        assertNull(missed.invoicePdfUri)
        closeTo(12.5, missed.distanceKm, 0.001)

        val service = missed.copy(
            gapTripType = GapTripType.SERVICE_INSPECTION,
            pollenFilterServiced = true,
            invoiceCost = 2200.0,
            invoicePdfUri = "content://inv/1.pdf"
        )
        assertEquals(GapTripType.SERVICE_INSPECTION, service.gapTripType)
        assertTrue(service.pollenFilterServiced)
        closeTo(2200.0, service.invoiceCost, 0.01)
        assertEquals("content://inv/1.pdf", service.invoicePdfUri)
    }

    // endregion

    // region TurboCooldownGuardian

    @Test
    fun `turbo stays cold at idle with no boost`() {
        val g = TurboCooldownGuardian()
        var ts = 0L
        var snap = g.onSample(ts, 0.0, 800.0, 0.0, 12.0, 35.0, 85.0)
        ts += 1000
        snap = g.onSample(ts, 0.0, 800.0, 0.0, 12.0, 35.0, 85.0)
        assertEquals(TurboCooldownGuardian.State.COLD, snap.state)
        assertFalse(snap.heatSoakRisk)
        assertEquals(0, snap.remainingIdleSec)
    }

    @Test
    fun `high boost then stop recommends idle cooldown`() {
        val g = TurboCooldownGuardian()
        var ts = 0L
        var snap = g.onSample(ts, 100.0, 4000.0, 120.0, 85.0, 85.0, 98.0)
        repeat(24) {
            ts += 1000
            snap = g.onSample(ts, 100.0, 4000.0, 120.0, 85.0, 85.0, 98.0)
        }
        assertEquals(TurboCooldownGuardian.State.HOT, snap.state)
        assertTrue(snap.heatScore >= 40.0)
        assertTrue(snap.peakBoostKpa >= 120.0)

        ts += 1000
        snap = g.onSample(ts, 0.0, 850.0, 5.0, 15.0, 70.0, 98.0)
        assertEquals(TurboCooldownGuardian.State.COOLDOWN_RECOMMENDED, snap.state)
        assertTrue(snap.remainingIdleSec > 0)
        assertTrue(snap.recommendedIdleSec >= 20)

        ts += 1000
        snap = g.onSample(ts, 0.0, 850.0, 0.0, 12.0, 60.0, 96.0)
        assertEquals(TurboCooldownGuardian.State.COOLING, snap.state)
    }

    @Test
    fun `idling after boost eventually safe to shutdown`() {
        val g = TurboCooldownGuardian()
        var ts = 0L
        repeat(20) {
            g.onSample(ts, 110.0, 4200.0, 130.0, 90.0, 90.0, 100.0)
            ts += 1000
        }
        g.onSample(ts, 0.0, 850.0, 0.0, 10.0, 70.0, 99.0)
        ts += 1000
        var snap = g.onSample(ts, 0.0, 850.0, 0.0, 10.0, 55.0, 95.0)
        var guard = 0
        while (snap.state != TurboCooldownGuardian.State.SAFE_TO_SHUTDOWN && guard < 80) {
            ts += 1000
            snap = g.onSample(ts, 0.0, 850.0, 0.0, 10.0, 45.0, 92.0)
            guard++
        }
        assertEquals(TurboCooldownGuardian.State.SAFE_TO_SHUTDOWN, snap.state)
        assertEquals(0, snap.remainingIdleSec)
    }

    @Test
    fun `killing engine while turbo is hot flags heat soak`() {
        val g = TurboCooldownGuardian()
        var ts = 0L
        repeat(20) {
            g.onSample(ts, 100.0, 4000.0, 120.0, 80.0, 80.0, 99.0)
            ts += 1000
        }
        ts += 1000
        val snap = g.onSample(ts, 0.0, 0.0, 0.0, 0.0, 80.0, 99.0)
        assertEquals(TurboCooldownGuardian.State.HEAT_SOAK_RISK, snap.state)
        assertTrue(snap.heatSoakRisk)
    }

    @Test
    fun `turbo cooldown reset clears heat`() {
        val g = TurboCooldownGuardian()
        var ts = 0L
        repeat(15) {
            g.onSample(ts, 100.0, 4000.0, 120.0, 80.0)
            ts += 1000
        }
        g.reset()
        val snap = g.onSample(ts + 1000, 0.0, 800.0, 0.0)
        assertEquals(TurboCooldownGuardian.State.COLD, snap.state)
        closeTo(0.0, snap.heatScore, 0.01)
    }

    // endregion

    // region TransmissionSlipAnalyzer

    @Test
    fun `locked sixth gear reports near-zero converter slip`() {
        val a = TransmissionSlipAnalyzer()
        // Factory 6th: PRIOR_SCALE 26 * 0.686 = 17.836 rpm/km/h → 1784 rpm @ 100 km/h
        var ts = 0L
        var snap = a.onSample(ts, 100.0, 1784.0, 25.0)
        ts += 1000
        snap = a.onSample(ts, 100.0, 1784.0, 25.0)
        ts += 1000
        snap = a.onSample(ts, 100.0, 1784.0, 25.0)
        assertEquals(6, snap.estimatedGear)
        assertEquals(TransmissionSlipAnalyzer.Lockup.LOCKED, snap.lockup)
        assertNotNull(snap.slipRpm)
        assertTrue(abs(snap.slipRpm!!) < 40.0)
        assertTrue(abs(snap.slipPercent!!) < 2.5)
        assertEquals(TransmissionSlipAnalyzer.Stress.NONE, snap.atfStress)
    }

    @Test
    fun `launch rpm well above expected is converter open`() {
        val a = TransmissionSlipAnalyzer()
        var ts = 0L
        var snap = a.onSample(ts, 20.0, 2500.0, 70.0)
        ts += 1000
        snap = a.onSample(ts, 20.0, 2500.0, 70.0)
        assertEquals(TransmissionSlipAnalyzer.Lockup.OPEN, snap.lockup)
        assertTrue((snap.slipRpm ?: 0.0) > 100.0)
    }

    @Test
    fun `sustained slip plus hot ATF raises thermal stress`() {
        val a = TransmissionSlipAnalyzer()
        var ts = 0L
        var snap = a.onSample(ts, 30.0, 2800.0, 90.0, atfTempC = 135.0)
        repeat(40) {
            ts += 1000
            snap = a.onSample(ts, 30.0, 2800.0, 90.0, atfTempC = 135.0)
        }
        assertTrue(snap.cumulativeSlipEnergyKj > 0.0)
        assertEquals(TransmissionSlipAnalyzer.Stress.SEVERE, snap.atfStress)
        assertTrue(snap.atfStressScore >= 70.0)
        closeTo(135.0, snap.atfTempC, 0.01)
    }

    @Test
    fun `stationary idle is converter open not a slip event`() {
        val a = TransmissionSlipAnalyzer()
        val snap = a.onSample(1000L, 0.0, 800.0, 10.0)
        assertEquals(TransmissionSlipAnalyzer.Lockup.OPEN, snap.lockup)
        assertNull(snap.slipRpm)
    }

    // endregion

    // region DfcoCoach

    @Test
    fun `dfco glide accumulates idle-equivalent rupee savings`() {
        val coach = DfcoCoach(defaultPricePerLiterInr = 100.0)
        var ts = 0L
        var snap = coach.onSample(ts, 80.0, 2000.0, 0.0, 0.0, 0.0, false)
        repeat(60) {
            ts += 1000
            snap = coach.onSample(ts, 80.0, 2000.0, 0.0, 0.0, 0.0, false)
        }
        assertTrue(snap.isDfcoActive)
        assertEquals(1, snap.eventCount)
        closeTo(PowertrainModel.IDLE_FUEL_LH * 60.0 / 3600.0, snap.sessionSavedLiters, 0.0005)
        closeTo(PowertrainModel.IDLE_FUEL_LH * 60.0 / 3600.0 * 100.0, snap.sessionSavedRupees, 0.05)
        closeTo(PowertrainModel.IDLE_FUEL_LH / 60.0 * 100.0, snap.liveRupeesPerMinute, 0.01)
        assertTrue(snap.dfcoSeconds >= 59.0)
        assertTrue(snap.dfcoDistanceKm > 1.0)
        assertTrue(snap.message.contains("DFCO"))
    }

    @Test
    fun `throttle or brake cancels dfco`() {
        val coach = DfcoCoach()
        var ts = 0L
        coach.onSample(ts, 80.0, 2000.0, 0.0, 0.0, false)
        ts += 1000
        val powered = coach.onSample(ts, 80.0, 2000.0, 4.5, pedalPct = 20.0)
        assertFalse(powered.isDfcoActive)
        ts += 1000
        val braking = coach.onSample(ts, 80.0, 2000.0, 0.0, pedalPct = 0.0, brakeActive = true)
        assertFalse(braking.isDfcoActive)
    }

    @Test
    fun `dfco price update affects live rupee rate`() {
        val coach = DfcoCoach(defaultPricePerLiterInr = 100.0)
        coach.setPricePerLiterInr(120.0)
        var ts = 0L
        coach.onSample(ts, 70.0, 1800.0, 0.0, 0.0)
        val snap = coach.onSample(ts + 1000, 70.0, 1800.0, 0.0, 0.0)
        closeTo(PowertrainModel.IDLE_FUEL_LH / 60.0 * 120.0, snap.liveRupeesPerMinute, 0.01)
        assertEquals(120.0, snap.pricePerLiterInr, 0.0)
    }

    // endregion

    // region OctaneRoiAnalyzer

    @Test
    fun `parseGrade maps XP95 and E20 tags`() {
        val a = OctaneRoiAnalyzer()
        assertEquals(OctaneRoiAnalyzer.Grade.XP95, a.parseGrade("IOCL XP95"))
        assertEquals(OctaneRoiAnalyzer.Grade.XP95, a.parseGrade("Nayara X95 + mileX"))
        assertEquals(OctaneRoiAnalyzer.Grade.E20, a.parseGrade("REGULAR E20"))
        assertEquals(OctaneRoiAnalyzer.Grade.E20, a.parseGrade("MS"))
        assertEquals(OctaneRoiAnalyzer.Grade.UNKNOWN, a.parseGrade(null))
    }

    @Test
    fun `xp95 with real economy gain is worth the premium`() {
        val a = OctaneRoiAnalyzer()
        a.setPrices(100.0, 112.0)
        var ts = 0L
        a.setActiveGrade(OctaneRoiAnalyzer.Grade.E20)
        repeat(60) {
            a.onSample(ts, 80.0, 8.0, stftPct = 2.0, ltftPct = 4.0)
            ts += 1000
        }
        a.setActiveGrade(OctaneRoiAnalyzer.Grade.XP95)
        repeat(60) {
            a.onSample(ts, 80.0, 6.0, stftPct = 0.5, ltftPct = 1.0)
            ts += 1000
        }
        val v = a.verdict()
        assertTrue(v.worthIt)
        assertNotNull(v.e20?.kmPerL)
        assertNotNull(v.xp95?.kmPerL)
        assertTrue((v.economyDeltaPercent ?: 0.0) > v.breakEvenEconomyGainPct)
        assertTrue((v.rupeesSavedPer100km ?: 0.0) > 0.0)
        closeTo(12.0, v.extraCostPerLiterInr, 0.01)
        closeTo(12.0, v.breakEvenEconomyGainPct, 0.05)
        assertTrue((v.trimDeltaPct ?: 0.0) > 0.0)
        assertTrue(v.message.contains("XP95"))
    }

    @Test
    fun `xp95 with no economy gain is not worth it`() {
        val a = OctaneRoiAnalyzer()
        a.setPrices(115.0, 120.0)
        var ts = 0L
        a.setActiveGrade(OctaneRoiAnalyzer.Grade.E20)
        repeat(60) {
            a.onSample(ts, 80.0, 8.0)
            ts += 1000
        }
        a.setActiveGrade(OctaneRoiAnalyzer.Grade.XP95)
        repeat(60) {
            a.onSample(ts, 80.0, 8.0)
            ts += 1000
        }
        val v = a.verdict()
        assertFalse(v.worthIt)
        assertTrue((v.rupeesSavedPer100km ?: 0.0) < 0.0)
        assertTrue(v.message.contains("E20 is the economic fuel"))
    }

    @Test
    fun `octane roi needs both tanks before judging`() {
        val a = OctaneRoiAnalyzer()
        a.setActiveGrade(OctaneRoiAnalyzer.Grade.E20)
        var ts = 0L
        repeat(10) {
            a.onSample(ts, 80.0, 5.0, gradeTag = "E20")
            ts += 1000
        }
        val v = a.verdict()
        assertFalse(v.worthIt)
        assertNull(v.rupeesSavedPer100km)
        assertTrue(v.message.contains("Break-even"))
    }

    // endregion

    // region BatteryCrankProfiler

    @Test
    fun `healthy crank scores high and records dip`() {
        val p = BatteryCrankProfiler()
        p.onSample(0L, 0.0, 12.65)
        p.onSample(200L, 180.0, 11.4)
        p.onSample(450L, 300.0, 10.60)
        val snap = p.onSample(900L, 900.0, 13.90)
        assertEquals(BatteryCrankProfiler.Phase.RUNNING, snap.phase)
        assertEquals(1, snap.eventCount)
        val ev = snap.lastEvent
        assertNotNull(ev)
        closeTo(10.60, ev!!.minCrankVoltageV, 0.01)
        closeTo(12.65, ev.restVoltageV, 0.05)
        closeTo(13.90, ev.recoverVoltageV, 0.05)
        assertTrue(snap.healthScore >= 85)
        assertEquals("HEALTHY", snap.verdict)
        assertTrue(ev.crankDurationMs in 500L..900L)
    }

    @Test
    fun `weak crank dip scores replace soon`() {
        val p = BatteryCrankProfiler()
        p.onSample(0L, 0.0, 12.05)
        p.onSample(200L, 200.0, 9.2)
        p.onSample(400L, 280.0, 8.8)
        val snap = p.onSample(800L, 750.0, 13.0)
        assertTrue(snap.healthScore < 40)
        assertEquals("REPLACE SOON", snap.verdict)
        closeTo(8.8, snap.lastEvent!!.minCrankVoltageV, 0.01)
    }

    @Test
    fun `scoreOf rest crank recover bands`() {
        val p = BatteryCrankProfiler()
        assertEquals(100, p.scoreOf(12.7, 10.6, 13.8))
        assertEquals(40 + 20 + 10, p.scoreOf(12.6, 9.7, 13.7))
        assertTrue(p.scoreOf(11.8, 8.5, 12.8) < 20)
    }

    @Test
    fun `battery profiler reset clears events`() {
        val p = BatteryCrankProfiler()
        p.onSample(0L, 0.0, 12.6)
        p.onSample(200L, 200.0, 10.5)
        p.onSample(800L, 900.0, 13.8)
        assertEquals(1, p.recentEvents().size)
        p.reset()
        assertTrue(p.recentEvents().isEmpty())
        assertEquals(BatteryCrankProfiler.Phase.RESTING, p.onSample(0L, 0.0, 12.5).phase)
    }

    // endregion

    // region ValetSurveillanceManager

    @Test
    fun `valet cold rev and overspeed are audited once per debounce`() {
        val v = ValetSurveillanceManager()
        v.arm(0L)
        var ts = 1000L
        var snap = v.onSample(ts, 30.0, 3500.0, 40.0)
        ts += 200
        snap = v.onSample(ts, 30.0, 3600.0, 40.0)
        assertEquals(1, snap.coldRevCount)
        assertEquals(ValetSurveillanceManager.Kind.COLD_REV, snap.events.first().kind)

        ts += 2500
        snap = v.onSample(ts, 125.0, 2200.0, 90.0)
        ts += 300
        snap = v.onSample(ts, 128.0, 2300.0, 90.0)
        assertEquals(1, snap.overspeedCount)
        assertEquals(128.0, snap.maxSpeedKmh, 0.0)
        assertTrue(snap.maxRpm >= 3500.0)
        assertFalse(snap.clean)
        assertTrue(snap.armed)

        val done = v.disarm()
        assertFalse(done.armed)
        assertEquals(1, done.coldRevCount)
        assertEquals(1, done.overspeedCount)
        assertTrue(done.message.contains("Valet audit"))
    }

    @Test
    fun `valet clean session when limits respected`() {
        val v = ValetSurveillanceManager()
        v.arm(0L)
        var ts = 0L
        var snap = v.onSample(ts, 40.0, 1800.0, 90.0)
        repeat(5) {
            ts += 1000
            snap = v.onSample(ts, 55.0, 2000.0, 92.0)
        }
        assertTrue(snap.clean)
        assertEquals(0, snap.coldRevCount)
        assertEquals(0, snap.overspeedCount)
        val done = v.disarm()
        assertTrue(done.clean)
        assertTrue(done.message.contains("clean"))
    }

    @Test
    fun `valet ignores samples while disarmed`() {
        val v = ValetSurveillanceManager()
        val snap = v.onSample(1000L, 140.0, 5000.0, 30.0)
        assertFalse(snap.armed)
        assertEquals(0, snap.overspeedCount)
        assertTrue(snap.events.isEmpty())
    }

    @Test
    fun `warm high rpm is a separate audit kind`() {
        val v = ValetSurveillanceManager()
        v.arm(0L)
        val snap = v.onSample(1000L, 60.0, 4800.0, 92.0)
        assertEquals(1, snap.highRpmCount)
        assertEquals(0, snap.coldRevCount)
        assertEquals(ValetSurveillanceManager.Kind.HIGH_RPM, snap.events.first().kind)
    }

    // endregion

    // region DriveAnalytics wiring

    @Test
    fun `drive analytics snapshot exposes the six new engines`() {
        val analytics = DriveAnalytics()
        analytics.armValet(0L)
        var ts = 0L
        repeat(3) {
            analytics.onSignals(
                timestampMonotonicMs = ts,
                speedKmh = 80.0,
                rpm = 2200.0,
                throttlePct = 0.0,
                pedalPct = 0.0,
                fuelRateLh = 0.0,
                mapKpa = 150.0,
                baroKpa = 100.0,
                timingDeg = 25.0,
                stftPct = 0.0,
                ltftPct = 1.0,
                loadPct = 20.0,
                fuelLevelPct = 50.0,
                chargeTempC = 55.0,
                wastegatePct = null,
                brakeActive = false,
                actualTorquePct = 30.0,
                demandTorquePct = 30.0,
                referenceTorqueNm = 178.0,
                voltageV = 13.8,
                coolantC = 92.0,
                estimatedGear = 6
            )
            ts += 1100
        }
        val snap = analytics.snapshot.value
        assertNotNull(snap.turboCooldown)
        assertNotNull(snap.transmissionSlip)
        assertNotNull(snap.dfco)
        assertTrue(snap.dfco!!.isDfcoActive)
        assertNotNull(snap.octaneRoi)
        assertNotNull(snap.batteryCrank)
        assertNotNull(snap.valet)
        assertTrue(snap.valet!!.armed)
        analytics.disarmValet()
        analytics.reset()
        assertTrue(analytics.snapshot.value.trend.isEmpty())
    }

    // endregion
}
