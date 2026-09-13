package com.example

import com.example.analysis.TripFuelSummary
import com.example.model.DefaultPidDefinitions
import com.example.model.DecoderType
import com.example.model.ProfileDefinitions
import com.example.protocol.PidDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUEL-PID LINEAGE GUARD (QA/QC fuel audit 2026-09-13).
 *
 * Owner ask: "detailed QA & QC regarding fuel PIDs in Dashboard". The audit found the same
 * PID decoded with TWO different formulas across sources (019D: catalog g/s ÷10 vs profile
 * L/h ÷20) and 015E missing from the standard profile entirely. Root cause class: nothing
 * ever asserted that every definition source for one PID agrees, and that every fuel tile on
 * the dashboard has a live, enabled catalog entry behind it (the standing lineage checklist:
 * source PID -> poll/validation path -> stored-key format -> loud empty state).
 *
 * These tests fail loudly if any fuel PID ever diverges between catalog, profile or tiles,
 * or if a decoder formula drifts from the hand-computed J1979 value.
 */
class FuelPidLineageTest {

    private val catalog = DefaultPidDefinitions.getDefaults().associateBy { it.id }

    private fun decode(id: String, payload: List<Int>) =
        PidDecoder.decode(catalog.getValue(id), payload)

    // ── 1. Cross-source agreement: every profile request that shares an id with the
    //       catalog MUST use the same decoder. This is the guard that would have
    //       caught F-1 (019D ÷20 vs ÷10) automatically.
    @Test
    fun `profile requests never diverge from catalog decoders`() {
        val requests = ProfileDefinitions.standardObdRequests +
            ProfileDefinitions.vagExperimentalRequests
        for (req in requests) {
            val def = catalog[req.id] ?: continue
            assertEquals(
                "decoder mismatch for ${req.id} between ProfileDefinitions and DefaultPidDefinitions",
                def.decoderType, req.decoderType
            )
        }
    }

    // ── 2. Both fuel-rate PIDs exist in BOTH sources (F-5: 015E was missing from the
    //       standard profile, so the profile screen never tested the primary fuel PID).
    @Test
    fun `standard profile covers both fuel-rate PIDs with correct decoders`() {
        val byId = ProfileDefinitions.standardObdRequests.associateBy { it.id }
        assertEquals(DecoderType.FUEL_RATE_20, byId.getValue("015E").decoderType)
        assertEquals(DecoderType.FUEL_RATE_MASS_50, byId.getValue("019D").decoderType)
    }

    // ── 3. Dashboard fuel-tile coverage: every PID the fuel section of
    //       TelemetryDashboardContent renders must exist AND be enabled in the catalog,
    //       otherwise the progressive auto-probe can never resolve it and the tile is
    //       stuck on "probing..." forever (the 2026-09-12 empty-dashboard bug class).
    @Test
    fun `every dashboard fuel tile PID is catalogued and enabled`() {
        val fuelTilePids = listOf(
            "015E", "019D", "010A", "0123", "015D",
            "012F", "0151", "0152", "0103"
        )
        for (id in fuelTilePids) {
            val def = catalog[id]
            assertNotNull("$id rendered on dashboard but missing from catalog", def)
            assertTrue("$id rendered on dashboard but disabled - auto-probe will never resolve it", def!!.enabled)
        }
    }

    // ── 4. Decoder math locked to hand-computed J1979 values.
    @Test
    fun `fuel decoder math matches J1979 hand computations`() {
        // $5E engine fuel rate (volume): raw 34 -> 34/20 = 1.7 L/h
        assertEquals(1.7, decode("015E", listOf(0x41, 0x5E, 0x00, 0x22)).numericValue!!, 0.001)
        // $9D engine fuel rate (mass): raw 34 -> 34/50 = 0.68 g/s (F-6 real-car calibration)
        val mass = decode("019D", listOf(0x41, 0x9D, 0x00, 0x22, 0x00, 0x22))
        assertEquals(0.68, mass.numericValue!!, 0.001)
        assertEquals("g/s", mass.unit)
        // $0A fuel pressure: A=100 -> 300 kPa
        assertEquals(300.0, decode("010A", listOf(0x41, 0x0A, 0x64)).numericValue!!, 0.001)
        // $23 fuel rail gauge pressure: raw 10 -> 100 kPa
        assertEquals(100.0, decode("0123", listOf(0x41, 0x23, 0x00, 0x0A)).numericValue!!, 0.001)
        // $5D injection timing: raw 26880 -> (26880-26880)/128 = 0.0 deg (spec (256A+B)/128-210)
        assertEquals(0.0, decode("015D", listOf(0x41, 0x5D, 0x69, 0x00)).numericValue!!, 0.001)
        // $2F tank level: A=128 -> 50.2 %
        assertEquals(50.2, decode("012F", listOf(0x41, 0x2F, 0x80)).numericValue!!, 0.05)
        // $52 ethanol %: A=26 -> 10.2 %
        assertEquals(10.2, decode("0152", listOf(0x41, 0x52, 0x1A)).numericValue!!, 0.05)
        // $06 STFT: A=154 -> (154-128)*100/128 = +20.3 %
        assertEquals(20.3, decode("0106", listOf(0x41, 0x06, 0x9A)).numericValue!!, 0.05)
        // $03 fuel system status: A=2 -> closed loop
        assertEquals(
            "Closed loop (using O2 sensor)",
            decode("0103", listOf(0x41, 0x03, 0x02, 0x00)).displayValue
        )
        // $51 fuel type: A=1 -> Gasoline
        assertEquals("Gasoline", decode("0151", listOf(0x41, 0x51, 0x01)).displayValue)
    }

    // ── 5. Mass-only fuel series: when the ECU answers ONLY $9D (g/s), trip integration
    //       must convert at petrol density (0.745 kg/L) and produce real litres.
    //       Hand math: 3.725 g/s x 3600 / 745 = 18.0 L/h; 18 L/h over 60 s = 0.30 L.
    @Test
    fun `mass-only 019D series integrates to litres at petrol density`() {
        val samples = mutableListOf<TripFuelSummary.SamplePoint>()
        repeat(60) { i ->
            val ts = 1_000L * i
            samples.add(TripFuelSummary.SamplePoint("010D", ts, 50.0))
            samples.add(TripFuelSummary.SamplePoint("9D", ts, 3.725)) // stored 2-hex form
        }
        val s = TripFuelSummary.summarize(samples)
        assertTrue("hasFuelSeries must be true for mass-only trips", s.hasFuelSeries)
        assertEquals(0.30, s.fuelLiters, 0.02)
        assertNotNull("km/L must compute from mass-derived litres", s.kmPerLiter)
    }

    /**
     * F-6 guard: the calibrated 0x9D scale must agree with the stoichiometric air model
     * within 2x at the owner's captured idle state (MAP 37 kPa, 978 rpm, IAT 30 C),
     * while the old /10 scale disagreeed by 4.5x - this test locks the calibration.
     */
    @Test
    fun `019D calibration agrees with stoichiometric air model at owner idle`() {
        val airFuelGs = com.example.engine.PowertrainModel.airModelFuelGs(37.0, 978.0, 30.0)
        assert(airFuelGs in 0.14..0.20) { "air model idle fuel implausible: $airFuelGs" }
        val decoded = decode("019D", listOf(0x41, 0x9D, 0x00, 0x08)).numericValue!!
        assert(decoded / airFuelGs < 2.0) { "9D scale drifted from physics: ratio ${decoded / airFuelGs}" }
        assert(decoded * 5.0 / airFuelGs > 2.0) { "old /10 scale must stay rejected" }
}
