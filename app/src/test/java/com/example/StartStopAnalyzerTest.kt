package com.example

import com.example.analysis.StartStopAnalyzer
import com.example.analysis.StartStopAnalyzer.Baseline
import com.example.analysis.StartStopAnalyzer.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Idle start-stop accounting (owner 2026-09-15: "did you add fuel saved with auto start stop
 * function and also fuel spikes any during the auto start stop?").
 *
 * The Kylaq 1.0 TSI stalls the engine at lights while the ECU keeps answering on CAN. These
 * tests pin the honest behaviour:
 *  - a stall is only real after MIN_STOP_SECONDS of engine-off evidence (flickers don't count);
 *  - link silence never becomes claimed engine-off time;
 *  - the saved-fuel baseline is MEASURED from the trip's own warm idle when there is enough
 *    evidence, otherwise the labelled MODEL figure - and nothing is claimed without events;
 *  - measured fuel during a stall (EVAP purge etc.) REDUCES the claimed saving;
 *  - the restart enrichment spike is captured and kept out of the idle baseline;
 *  - fuel-cut coasting (rpm > 0, speed > 0, fuel 0) is never mistaken for a stall.
 */
class StartStopAnalyzerTest {

    private fun obs(
        tsSec: Long,
        rpm: Double?,
        speed: Double?,
        fuel: Double?
    ) = Observation(tsMs = tsSec * 1000L, rpm = rpm, speedKmh = speed, fuelLh = fuel)

    /** Driving block: rpm 2000, 50 km/h, 6 L/h. */
    private fun driving(fromSec: Long, toSec: Long, fuel: Double = 6.0) =
        (fromSec..toSec).map { obs(it, 2000.0, 50.0, fuel) }

    /** Warm idle block: rpm 800, standing still, given L/h. */
    private fun idling(fromSec: Long, toSec: Long, fuel: Double = 1.0) =
        (fromSec..toSec).map { obs(it, 800.0, 0.0, fuel) }

    /** Start-stop stall: engine off (rpm 0), standing still, given measured fuel rate. */
    private fun stalled(fromSec: Long, toSec: Long, fuel: Double = 0.0) =
        (fromSec..toSec).map { obs(it, 0.0, 0.0, fuel) }

    @Test
    fun `stall is detected and saving uses the trip's own measured idle rate`() {
        val timeline =
            driving(0, 19) +
                idling(20, 59, fuel = 1.0) +          // 40 s warm idle -> MEASURED baseline
                stalled(60, 104) +                     // 45 s engine off
                listOf(                                // restart with cranking enrichment
                    obs(105, 2500.0, 0.0, 12.0),
                    obs(106, 2600.0, 0.0, 12.0),
                    obs(107, 2700.0, 5.0, 12.0),
                    obs(108, 2400.0, 15.0, 6.0),
                    obs(109, 2300.0, 25.0, 6.0),
                    obs(110, 2200.0, 35.0, 6.0)
                ) +
                driving(111, 130)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(1, s.stopEvents)
        assertEquals(1, s.restartCount)
        assertTrue("engine-off seconds ${s.engineOffSeconds}", s.engineOffSeconds in 43.0..46.0)
        assertEquals(Baseline.MEASURED, s.baselineSource)
        assertTrue("measured idle L/h ${s.warmIdleLh}", (s.warmIdleLh ?: -1.0) in 0.95..1.05)
        // 45 s x 1.0 L/h ~ 0.0125 L saved, minus ~0 burned while stopped.
        assertTrue("saved ${s.estimatedFuelSavedL}", s.estimatedFuelSavedL in 0.011..0.014)
        assertTrue("stall fuel ${s.fuelBurnedWhileStoppedL}", s.fuelBurnedWhileStoppedL < 0.001)
        // Cranking spike captured...
        assertTrue("peak ${s.restartPeakFuelLh}", (s.restartPeakFuelLh ?: 0.0) in 11.5..12.5)
        assertTrue("spike fuel ${s.restartSpikeFuelL}", s.restartSpikeFuelL > 0.005)
        // ...and kept OUT of the idle baseline (a 12 L/h window must not lift 1.0 L/h idle).
        assertTrue("warm idle seconds ${s.warmIdleSeconds}", s.warmIdleSeconds in 38.0..42.0)
    }

    @Test
    fun `a sub-threshold rpm flicker is not a stall`() {
        val timeline =
            driving(0, 19) +
                stalled(20, 21) +        // 2 s - sensor dropout / restart flicker
                driving(22, 40)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(0, s.stopEvents)
        assertEquals(0.0, s.engineOffSeconds, 1e-9)
        assertEquals(Baseline.NONE, s.baselineSource)
        assertEquals(0.0, s.estimatedFuelSavedL, 1e-9)
    }

    @Test
    fun `link silence closes the stall and is never claimed as engine-off time`() {
        val timeline =
            driving(0, 9) +
                stalled(10, 19) +                                   // 10 s of real evidence
                listOf(obs(80, 2000.0, 50.0, 6.0)) +                // 60 s of silence, then data again
                driving(81, 90)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(1, s.stopEvents)
        assertTrue("only observed seconds count: ${s.engineOffSeconds}", s.engineOffSeconds in 8.0..11.0)
        assertEquals("silence is not a restart", 0, s.restartCount)
    }

    @Test
    fun `without idle evidence the model baseline is used and labelled`() {
        val timeline =
            driving(0, 19) +
                stalled(20, 49) +        // 30 s stall, no warm idle anywhere in the trip
                listOf(obs(50, 2500.0, 10.0, 8.0)) +
                driving(51, 70)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(1, s.stopEvents)
        assertEquals(Baseline.MODEL, s.baselineSource)
        assertEquals(StartStopAnalyzer.MODEL_IDLE_LH, s.baselineIdleLh!!, 1e-9)
        // 30 s x 1.05 L/h = 0.00875 L
        assertTrue("saved ${s.estimatedFuelSavedL}", s.estimatedFuelSavedL in 0.0075..0.0100)
    }

    @Test
    fun `fuel actually burned during a stall reduces the claimed saving`() {
        val timeline =
            driving(0, 9) +
                idling(10, 49, fuel = 1.0) +          // measured baseline 1.0 L/h
                stalled(50, 109, fuel = 0.3) +        // 60 s stall that keeps burning 0.3 L/h
                listOf(obs(110, 2500.0, 10.0, 8.0)) +
                driving(111, 120)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(1, s.stopEvents)
        // 60 s x 0.3 L/h = 0.005 L really burned while "off"
        assertTrue("burned ${s.fuelBurnedWhileStoppedL}", s.fuelBurnedWhileStoppedL in 0.004..0.006)
        // saving = (1.0 - 0.3) x 60/3600 = 0.01166 L, NOT the full idle baseline
        assertTrue("saved ${s.estimatedFuelSavedL}", s.estimatedFuelSavedL in 0.010..0.013)
    }

    @Test
    fun `fuel-cut coasting is not mistaken for a stall`() {
        val timeline =
            driving(0, 9) +
                (10..40).map { obs(it, 1500.0, 80.0 - (it - 10), 0.0) } +  // DFCO: fuel 0, moving
                driving(41, 60)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(0, s.stopEvents)
        assertEquals(0.0, s.engineOffSeconds, 1e-9)
    }

    @Test
    fun `a normal trip claims nothing at all`() {
        val timeline = driving(0, 120)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(0, s.stopEvents)
        assertEquals(Baseline.NONE, s.baselineSource)
        assertNull(s.baselineIdleLh)
        assertEquals(0.0, s.estimatedFuelSavedL, 1e-9)
        assertNull(s.restartPeakFuelLh)
        assertTrue(!s.hasStartStopActivity)
    }

    @Test
    fun `a trip that ends while still stalled commits the observed seconds`() {
        val timeline =
            driving(0, 9) +
                idling(10, 39, fuel = 0.9) +
                stalled(40, 70)          // recording ends mid-stall (parked, watchdog stops)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(1, s.stopEvents)
        assertTrue("engine-off ${s.engineOffSeconds}", s.engineOffSeconds in 28.0..32.0)
        assertEquals(Baseline.MEASURED, s.baselineSource)
        assertTrue(s.estimatedFuelSavedL > 0.005)
    }

    @Test
    fun `two stalls in one trip are counted separately`() {
        val timeline =
            driving(0, 9) +
                idling(10, 44, fuel = 1.0) +
                stalled(45, 64) +                                    // stall 1: 20 s
                listOf(obs(65, 900.0, 0.0, 1.0)) +                   // brief idle restart
                idling(66, 69, fuel = 1.0) +
                driving(70, 89) +
                stalled(90, 119) +                                   // stall 2: 30 s
                driving(120, 130)

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(2, s.stopEvents)
        assertTrue("total engine-off ${s.engineOffSeconds}", s.engineOffSeconds in 46.0..52.0)
        assertTrue(s.restartCount >= 1)
    }

    @Test
    fun `rpm null everywhere never fabricates stalls`() {
        // Legacy trips / ECU that never answered 010C: no rpm evidence -> no claims.
        val timeline = (0..60).map { obs(it, null, 0.0, 0.0) }

        val s = StartStopAnalyzer.analyze(timeline)

        assertEquals(0, s.stopEvents)
        assertEquals(0.0, s.engineOffSeconds, 1e-9)
        assertEquals(Baseline.NONE, s.baselineSource)
    }
}
