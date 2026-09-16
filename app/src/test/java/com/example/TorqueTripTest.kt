package com.example

import com.example.analysis.TripFuelSummary
import com.example.engine.PowertrainModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-trip engine torque calculations (owner pipeline task 6, 2026-09-16). PID 0162
 * percent-of-reference -> Nm with the ECU's own 0164 reference when answered, else the
 * factory 178 Nm plateau; engine-running samples only; silence stays silence.
 */
class TorqueTripTest {

    private fun point(pid: String, ts: Long, value: Double?) =
        TripFuelSummary.SamplePoint(pid, ts, value)

    /** 60 s of running engine: rpm ~1500, torque percentages 20/40/60 cycling. */
    private fun drive(with0164: Double? = null): List<TripFuelSummary.SamplePoint> {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 1_000_000L
        with0164?.let { samples.add(point("0164", ts, it)) }
        repeat(60) { i ->
            samples.add(point("010C", ts, 1500.0))
            samples.add(point("0162", ts, listOf(20.0, 40.0, 60.0)[i % 3]))
            samples.add(point("0D", ts, 50.0)) // speed so the trip is a real drive
            ts += 1000
        }
        return samples
    }

    @Test
    fun `torque percentages convert with the factory reference when 0164 is silent`() {
        val s = TripFuelSummary.summarize(drive())
        // mean of 20/40/60 % = 40 % of 178 Nm = 71.2 Nm; peak = 60 % = 106.8 Nm.
        assertEquals(40.0 / 100.0 * PowertrainModel.PEAK_TORQUE_NM, s.meanTorqueNm!!, 1e-6)
        assertEquals(60.0 / 100.0 * PowertrainModel.PEAK_TORQUE_NM, s.peakTorqueNm!!, 1e-6)
        assertEquals(PowertrainModel.PEAK_TORQUE_NM, s.torqueReferenceNm!!, 1e-9)
    }

    @Test
    fun `a 0164 reference from the ECU replaces the factory figure`() {
        val s = TripFuelSummary.summarize(drive(with0164 = 200.0))
        assertEquals(0.4 * 200.0, s.meanTorqueNm!!, 1e-6)
        assertEquals(0.6 * 200.0, s.peakTorqueNm!!, 1e-6)
        assertEquals(200.0, s.torqueReferenceNm!!, 1e-9)
    }

    @Test
    fun `no 0162 answers means no torque claim - never fabricated`() {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 1_000_000L
        repeat(60) {
            samples.add(point("010C", ts, 1500.0))
            samples.add(point("0D", ts, 50.0))
            ts += 1000
        }
        val s = TripFuelSummary.summarize(samples)
        assertNull(s.meanTorqueNm)
        assertNull(s.peakTorqueNm)
        assertNull("reference stays null when no torque was measured", s.torqueReferenceNm)
    }

    @Test
    fun `samples during engine-off time never enter the torque stats`() {
        val samples = drive().toMutableList()
        // Parked with start-stop: rpm 0, but 0162 somehow still reports 90 % - excluded.
        val parkedTs = samples.maxOf { it.timestampMs } + 60_000L
        samples.add(point("010C", parkedTs, 0.0))
        samples.add(point("0162", parkedTs, 90.0))
        val s = TripFuelSummary.summarize(samples)
        assertTrue("parked 90% reading must not lift the peak above the running 60%",
            s.peakTorqueNm!! <= 0.6 * PowertrainModel.PEAK_TORQUE_NM + 1e-6)
    }

    @Test
    fun `2-hex stored pids work like everywhere else in the summary`() {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        var ts = 1_000_000L
        repeat(30) {
            samples.add(point("0C", ts, 2000.0))
            samples.add(point("62", ts, 50.0))
            ts += 1000
        }
        val s = TripFuelSummary.summarize(samples)
        assertEquals(0.5 * PowertrainModel.PEAK_TORQUE_NM, s.meanTorqueNm!!, 1e-6)
    }
}
