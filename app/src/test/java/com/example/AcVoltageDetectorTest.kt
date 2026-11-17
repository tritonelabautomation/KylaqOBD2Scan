package com.example

import com.example.analysis.AcVoltageDetector
import com.example.analysis.AcVoltageDetector.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-state detection from battery-voltage fluctuation (owner 2026-09-16, with a labelled
 * drive: AC off for the first ~10 km, switched on during a 90-100 s signal halt).
 *
 * These tests pin the detector's honesty rules on synthetic electrical signatures:
 *  - a quiet alternator band stays OFF;
 *  - a switch into clutch/blower ripple is caught within a couple of analysis windows of
 *    the true switch, and the ON duration is honest about the detection lag;
 *  - cranking dips and key-off decay are excluded by the rpm gate and the crank mask;
 *  - single coast-alternator-boost spikes cannot flip the state (MAD, not std-dev);
 *  - a trip that never shows a quiet regime cannot be split - and says so via confidence
 *    instead of inventing an OFF segment;
 *  - thin evidence returns EMPTY rather than a guess.
 */
class AcVoltageDetectorTest {

    private val TWO_S = 2_000L

    /** Quiet alternator regulation: 14.4 V with +-0.04 V deterministic ripple. */
    private fun quiet(from: Int, to: Int, t0: Long, rpm: Double = 1500.0) =
        (from until to).map { i ->
            Sample(
                tsMs = t0 + i * TWO_S,
                voltageV = 14.4 + 0.04 * (((i % 5) - 2) / 2.0),
                rpm = rpm
            )
        }

    /** AC ON: lower mean, clutch/blower cycling - 0.45 V triangle, 40 s period. */
    private fun rippling(from: Int, to: Int, t0: Long, rpm: Double = 1500.0) =
        (from until to).map { i ->
            Sample(
                tsMs = t0 + i * TWO_S,
                voltageV = 13.3 + 0.45 * (((i % 20) - 10) / 10.0),
                rpm = rpm
            )
        }

    @Test
    fun `a quiet alternator band stays off`() {
        val samples = quiet(0, 600, 0L) // 20 min

        val r = AcVoltageDetector.detect(samples)

        assertTrue(r.hasEvidence)
        assertEquals(0.0, r.acOnSeconds, 1e-9)
        assertTrue(r.switchEvents.isEmpty())
        assertTrue("quiet MAD ${r.quietMadV}", (r.quietMadV ?: 9.0) < 0.06)
        assertTrue(r.segments.all { !it.acOn })
    }

    @Test
    fun `switching into clutch ripple is detected near the true switch`() {
        val switchIndex = 300             // 10 min quiet...
        val samples = quiet(0, switchIndex, 0L) + rippling(switchIndex, 900, 0L) // ...then 20 min AC
        val trueSwitchMs = switchIndex * TWO_S

        val r = AcVoltageDetector.detect(samples)

        assertTrue(r.hasEvidence)
        val firstOn = r.switchEvents.firstOrNull { it.second }
        assertTrue("an ON switch must be reported", firstOn != null)
        val lag = firstOn!!.first - trueSwitchMs
        assertTrue("detection lag ${lag}ms", lag in -60_000L..240_000L)
        // ON until the end, minus the lag: 20 min window, expect 15-21 min
        assertTrue("acOnSeconds ${r.acOnSeconds / 60.0} min", r.acOnSeconds / 60.0 in 15.0..21.0)
        assertTrue("ON windows are the rippling ones", (r.onMadV ?: 0.0) > 0.15)
        assertTrue(r.confidence > 2.0)
    }

    @Test
    fun `cranking dips and stalls are excluded, not read as ac`() {
        val samples =
            quiet(0, 200, 0L) +
                // 12 s stall: rpm 0 with cranking-grade voltage - must be ignored entirely
                (200 until 206).map { Sample(400_000L + (it - 200) * TWO_S, 11.9, rpm = 0.0) } +
                quiet(206, 600, 0L)

        val r = AcVoltageDetector.detect(samples)

        assertTrue(r.hasEvidence)
        assertEquals(0.0, r.acOnSeconds, 1e-9)
        assertTrue(r.switchEvents.isEmpty())
    }

    @Test
    fun `single coast alternator boost spikes cannot flip the state`() {
        val base = quiet(0, 600, 0L).toMutableList()
        // one +0.9 V alternator-boost spike every 90 s (decel charging), as on real drives
        for (i in 45 until 600 step 45) {
            base[i] = base[i].copy(voltageV = base[i].voltageV + 0.9)
        }

        val r = AcVoltageDetector.detect(base)

        assertTrue(r.hasEvidence)
        assertEquals("spikes must not create ON time", 0.0, r.acOnSeconds, 1e-9)
        assertTrue(r.switchEvents.isEmpty())
    }

    @Test
    fun `a trip with no quiet regime is not split and flags weak confidence`() {
        // Whole drive with AC on: nothing to compare against - the honest answer is
        // "single regime, cannot tell", not a fabricated OFF half.
        val samples = rippling(0, 600, 0L)

        val r = AcVoltageDetector.detect(samples)

        assertTrue(r.hasEvidence)
        assertEquals(0.0, r.acOnSeconds, 1e-9)
        assertTrue("confidence ${r.confidence} must expose the missing separation", r.confidence < 1.5)
    }

    @Test
    fun `thin evidence returns empty instead of guessing`() {
        val r = AcVoltageDetector.detect(quiet(0, 8, 0L))

        assertEquals(AcVoltageDetector.Result.EMPTY, r)
        assertTrue(!r.hasEvidence)
    }

    @Test
    fun `engine-off samples below the crank mask never count even with rpm present`() {
        val samples = quiet(0, 300, 0L).toMutableList()
        // sensor garbage: running rpm but key-off-grade voltage - masked out
        for (i in 100 until 110) {
            samples[i] = samples[i].copy(voltageV = 11.5)
        }

        val r = AcVoltageDetector.detect(samples)

        assertEquals(0.0, r.acOnSeconds, 1e-9)
        assertTrue(r.switchEvents.isEmpty())
    }
}
