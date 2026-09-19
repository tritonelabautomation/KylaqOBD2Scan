package com.example

import com.example.analysis.AcLoadAnalyzer
import com.example.analysis.AcVoltageDetector
import com.example.analysis.StartStopAnalyzer
import com.example.analysis.StopBatteryAnalyzer
import com.example.analysis.TripAnalysisReport
import com.example.analysis.TripFuelSummary
import com.example.analysis.VoltageExtremes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis report that travels inside shared trip bundles (owner pipeline task 7:
 * "Even with trends shared you didn't show me with & without AC load analysis etc").
 * Sections must appear WITH their measured numbers when evidence exists, and vanish
 * entirely when it does not - a shared report must never guess.
 */
class TripAnalysisReportTest {

    private fun baseSummary() = TripFuelSummary.Summary(
        fuelLiters = 2.42,
        distanceKm = 30.8,
        kmPerLiter = 12.7,
        durationSeconds = 4200,
        averageSpeedKmh = 26.4,
        movingAverageSpeedKmh = 34.1,
        maxSpeedKmh = 56.0,
        coastSeconds = 120.0,
        idleSeconds = 600.0,
        speedHistogram = listOf(0 to 300.0, 20 to 900.0),
        sampleCount = 1252
    )

    private fun acResult() = AcVoltageDetector.Result(
        segments = listOf(
            AcVoltageDetector.Segment(0L, 1_500_000L, acOn = false),
            AcVoltageDetector.Segment(1_500_000L, 4_200_000L, acOn = true)
        ),
        switchEvents = listOf(1_500_000L to true),
        acOnSeconds = 2700.0,
        quietMadV = 0.031,
        onMadV = 0.12,
        confidence = 4.2,
        windows = 80
    )

    private fun comparison() = AcLoadAnalyzer.Comparison(
        acOn = AcLoadAnalyzer.RegimeStats(seconds = 2700.0, loadSamples = 400, meanLoadPct = 28.4, meanRpm = 1490.0, meanFuelLh = 1.32),
        acOff = AcLoadAnalyzer.RegimeStats(seconds = 1500.0, loadSamples = 220, meanLoadPct = 24.1, meanRpm = 1455.0, meanFuelLh = 1.18),
        loadDeltaPct = 4.3,
        rpmDelta = 35.0,
        fuelDeltaLh = 0.14
    )

    @Test
    fun `a bare summary reports totals and omits every unevidenced section`() {
        val report = TripAnalysisReport.build("Kylaq Run", baseSummary())
        assertTrue(report.contains("Trip analysis - Kylaq Run"))
        assertTrue(report.contains("30.80 km"))
        assertTrue(report.contains("12.7 km/L"))
        assertFalse(report.contains("## AC"))
        assertFalse(report.contains("## Battery extremes"))
        assertFalse(report.contains("## Idle start-stop"))
        assertFalse(report.contains("## Engine torque"))
    }

    @Test
    fun `measured AC evidence produces the WITH vs WITHOUT load analysis the owner asked for`() {
        val summary = baseSummary().copy(ac = acResult(), acLoad = comparison())
        val report = TripAnalysisReport.build("Kylaq Run", summary)
        assertTrue(report.contains("AC - MEASURED from battery-voltage ripple"))
        assertTrue(report.contains("AC on: 45 min"))
        assertTrue(report.contains("First state flip: ON at"))
        assertTrue(report.contains("Engine load WITH vs WITHOUT AC"))
        assertTrue(report.contains("28.4 % with AC vs 24.1 % without"))
        assertTrue(report.contains("+4.3 pts"))
        assertTrue(report.contains("+0.14 L/h with AC"))
        assertTrue(report.contains("+35 with AC"))
    }

    @Test
    fun `weak AC separation stays labelled and a thin comparison stays honest`() {
        val weak = acResult().copy(confidence = 1.4)
        val summary = baseSummary().copy(ac = weak, acLoad = AcLoadAnalyzer.Comparison())
        val report = TripAnalysisReport.build("Run", summary)
        assertTrue(report.contains("WEAK - treat as a hint"))
        assertTrue(report.contains("NOT COMPARABLE"))
        assertFalse(report.contains("pts"))
    }

    @Test
    fun `voltage extremes, stall battery and torque sections carry their measured numbers`() {
        val summary = baseSummary().copy(
            voltageExtremes = VoltageExtremes(minV = 11.9, minTs = 1_757_900_000_000L, maxV = 14.9, maxTs = 1_757_903_000_000L),
            startStop = StartStopAnalyzer.Summary(
                stopEvents = 6, engineOffSeconds = 210.0, restartCount = 6,
                estimatedFuelSavedL = 0.058, baselineSource = StartStopAnalyzer.Baseline.MEASURED,
                baselineIdleLh = 1.0
            ),
            stopBattery = StopBatteryAnalyzer.Result(
                stopsWithVoltage = 5, samplesInStops = 40, meanVInStops = 12.6,
                minVInStops = 12.1, meanVRunning = 14.2, acOnStops = 2, acOnStopsTotal = 6
            ),
            meanTorqueNm = 71.2, peakTorqueNm = 106.8, torqueReferenceNm = 178.0
        )
        val report = TripAnalysisReport.build("Run", summary)
        assertTrue(report.contains("## Battery extremes (recorded)"))
        assertTrue(report.contains("11.9 V"))
        assertTrue(report.contains("## Idle start-stop"))
        assertTrue(report.contains("Stalls: 6"))
        assertTrue(report.contains("Battery during 5 stall(s): mean 12.6 V"))
        assertTrue(report.contains("Sag under stall loads: 1.6 V")) // 14.2 - 12.6
        assertTrue(report.contains("2/6 stall(s) overlapped measured AC-on"))
        assertTrue(report.contains("## Engine torque (measured, PID 0162)"))
        assertTrue(report.contains("Mean: 71 Nm"))
        assertTrue(report.contains("Peak: 107 Nm"))
        assertTrue(report.contains("Reference torque used: 178 Nm"))
    }

    @Test
    fun `no AC evidence means no AC section even if other data exists`() {
        val summary = baseSummary().copy(meanTorqueNm = 50.0, peakTorqueNm = 90.0, torqueReferenceNm = 178.0)
        val report = TripAnalysisReport.build("Run", summary)
        assertFalse(report.contains("WITH vs WITHOUT"))
        assertTrue(report.contains("## Engine torque"))
    }
}
