package com.example

import com.example.analysis.RideBehaviorRecorder
import com.example.analysis.RideCodec
import com.example.model.DecoderType
import com.example.model.PidDefinition
import com.example.protocol.PidDecoder
import com.example.ui.screens.numericWithStaleFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-15 owner screenshot audit, two regressions locked:
 *
 * 1. "why gal/s whereas all other units are Liters?" - PID 019D is the J1979 MASS fuel
 *    rate (g/s); the volume PID 015E is refused by the Kylaq ECU, so every litre figure
 *    in the app derives from this mass rate. The row now shows the litre conversion
 *    inline (745 g/L) so it can never be misread as a gallon unit again.
 * 2. The Ride X-ray showed the SAME drive twice (auto-stop + manual stop both persisted
 *    it; the ride log had no dedup key). dedupKey/deduped + the one-persist guard fix it;
 *    deduped() also repairs logs written before the guard.
 */
class FuelUnitAndRideDedupTest {

    private val fuelRatePid = PidDefinition(
        id = "019D", service = "01", pid = "9D",
        name = "Engine Fuel Rate (Mass)",
        shortName = "FuelRateMass", unit = "g/s",
        decoderType = DecoderType.FUEL_RATE_MASS_50, isResearch = false,
        defaultIntervalMs = 500L
    )

    @Test
    fun `019D decodes mass rate WITH litre equivalent so no gallon reading is possible`() {
        // raw 0x000A = 10 counts * 0.02 g/s = 0.20 g/s -> 0.20*3600/745 = 0.97 L/h
        val r = PidDecoder.decode(fuelRatePid, listOf(0x41, 0x9D, 0x00, 0x0A))
        assertEquals(0.20, r.numericValue!!, 1e-9)
        assertEquals("0.20 g/s ≈ 0.97 L/h", r.displayValue)
        assertEquals("", r.unit) // store joins displayValue+unit; no double suffix
        assertTrue("no gallon token anywhere", !r.displayValue.contains("gal"))
    }

    @Test
    fun `019D idle counts land in the physically sane litre band`() {
        // Real-car calibrated idle window: raw counts 8-14 -> 0.77-1.35 L/h (F-6).
        val lo = PidDecoder.decode(fuelRatePid, listOf(0x41, 0x9D, 0x00, 0x08))
        val hi = PidDecoder.decode(fuelRatePid, listOf(0x41, 0x9D, 0x00, 0x0E))
        assertEquals("0.16 g/s ≈ 0.77 L/h", lo.displayValue)
        assertEquals("0.28 g/s ≈ 1.35 L/h", hi.displayValue)
    }

    @Test
    fun `stale-suffixed mass rate still feeds derived cards numerically`() {
        // numericWithStaleFallback parses the leading number of "0.20 g/s ≈ 0.97 L/h (stale)".
        val v = numericWithStaleFallback(null, "0.20 g/s ≈ 0.97 L/h (stale)")
        assertEquals(0.20, v!!, 1e-9)
    }

    private fun ride(km: Double, secs: Double = 4200.0, tag: String = "D") = RideBehaviorRecorder.RideSummary(
        dateUtc = "2026-09-15T03:00:00Z",
        durationSec = secs,
        distanceKm = km,
        stateSeconds = mapOf("CRUISING" to 2000.0, "ACCELERATING" to 1900.0, "STOPPED" to 300.0),
        gearSeconds = listOf(0.0, 805.0, 1165.0, 1479.0, 754.0, 7.0, 0.0),
        elevationGainM = 59.0,
        elevationLossM = 40.0,
        shiftCount = 349,
        avgUpshiftRpm = 1622.0,
        maxUpshiftRpm = 2400.0,
        avgShiftDurationMs = 1600L,
        modeTag = tag
    )

    @Test
    fun `identical rides share a dedup key and deduped keeps one`() {
        val a = ride(30.5)
        val b = ride(30.5).copy(dateUtc = "2026-09-15T03:00:04Z") // second stop, same drive
        assertEquals(RideCodec.dedupKey(a), RideCodec.dedupKey(b))
        val out = RideCodec.deduped(listOf(a, b, ride(30.5)))
        assertEquals(1, out.size)
        assertEquals(a.dateUtc, out[0].dateUtc) // first occurrence wins
    }

    @Test
    fun `genuinely different rides survive dedup`() {
        val list = listOf(ride(30.5), ride(12.25), ride(30.5, tag = "S"), ride(30.5, secs = 3600.0))
        assertEquals(4, RideCodec.deduped(list).size)
        assertEquals(4, list.map { RideCodec.dedupKey(it) }.distinct().size)
    }
}
