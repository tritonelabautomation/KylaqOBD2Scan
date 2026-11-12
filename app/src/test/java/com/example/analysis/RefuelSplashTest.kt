package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-12 (trip screenshots):
 *
 *  - "you're showing fuel refill percentage change in all trips why? It should be shown only
 *    in the trip where we have refilled right?" → 2026-11-10 14:23 (40.7 km) and 2026-11-12
 *    16:10 (35.2 km) both showed "IndianOil COCO BEGUMPET · Fill 17.6 % → 93.7 % · PID 34.25 L
 *    vs pump 40.27 L" although neither trip went anywhere near the pump. The receipt is only
 *    the BATCH in the tank; a fill must be this trip's own 012F rise.
 *  - "still wrong calculations of fuel full percentage" → those litres came from a 45 L tank
 *    (`34.25 L`) while trip cards converted the same % with something near 50 L. One tank now.
 */
class RefuelSplashTest {

    /** 012F samples at 30 s spacing, wobbling the way the real float does (±0.5 pt). */
    private fun series(
        fromPct: Double,
        toPct: Double,
        count: Int,
        startMs: Long = 0L,
        spanMs: Long = 30L * 60_000L
    ): List<Pair<Long, Double>> = (0 until count).map { i ->
        val frac = i.toDouble() / (count - 1)
        val wobble = if (i % 3 == 0) 0.4 else if (i % 4 == 0) -0.4 else 0.0
        val ts = startMs + spanMs * i / (count - 1)
        ts to (fromPct + (toPct - fromPct) * frac + wobble)
    }

    @Test
    fun aTripThatOnlyBurnsFuelHasNoFill() {
        // 2026-11-12 16:10 run: 91.8 % → 83.9 %, 35.2 km, no pump anywhere in the trip.
        val trip = series(91.8, 83.9, 40)
        assertNull(RefuelSplash.detect(trip))

        val resolved = RefuelSplash.resolve(
            levelSeries = trip,
            fillBeforePct = 17.6,
            fillAfterPct = 93.7,
            pumpLitres = 40.27,
            linkedReceiptOnTrip = false
        )
        assertFalse(resolved.happenedOnThisTrip)
        assertNull(resolved.pumpLitres)
        assertEquals(RefuelSplash.Evidence.NONE, resolved.evidence)
    }

    @Test
    fun linkedReceiptIsNotAFillWhenTheLevelOnlyFalls() {
        // Same, but the linker reported the receipt as "on this trip" (odometer overlap).
        // The tank samples still say the level never rose → no fill card here.
        val trip = series(91.8, 83.9, 40)
        val resolved = RefuelSplash.resolve(
            levelSeries = trip,
            fillBeforePct = 17.6,
            fillAfterPct = 93.7,
            pumpLitres = 40.27,
            linkedReceiptOnTrip = true
        )
        assertFalse(resolved.happenedOnThisTrip)
        assertNull(resolved.pumpLitres)
        assertNull(resolved.fillBeforePct)
        assertNull(resolved.fillAfterPct)
        assertEquals(RefuelSplash.Evidence.NONE, resolved.evidence)
    }

    @Test
    fun theTripThatContainsThePumpShowsTheFill() {
        // 2026-11-11 recovered run: burn to 17.6 %, pump to 93.7 %, then drive off.
        val trip = buildList {
            addAll(series(22.0, 17.6, 10, startMs = 0L, spanMs = 10 * 60_000L))
            addAll(series(17.6, 93.7, 8, startMs = 11 * 60_000L, spanMs = 4 * 60_000L))
            addAll(series(93.7, 92.4, 20, startMs = 20 * 60_000L, spanMs = 40 * 60_000L))
        }
        val splash = RefuelSplash.detect(trip)!!
        assertEquals(17.6, splash.beforePct, 1.5)
        assertEquals(93.7, splash.afterPct, 1.5)
        assertTrue(splash.risePct > 70.0)

        val resolved = RefuelSplash.resolve(
            levelSeries = trip,
            fillBeforePct = 17.6,
            fillAfterPct = 93.7,
            pumpLitres = 40.27,
            linkedReceiptOnTrip = true
        )
        assertTrue(resolved.happenedOnThisTrip)
        assertTrue(resolved.receiptMatches)
        assertEquals(40.27, resolved.pumpLitres!!, 1e-9)
    }

    @Test
    fun sloshSpikeIsNotAFill() {
        val trip = buildList {
            addAll(series(55.0, 55.0, 15))
            add(8 * 60_000L to 62.0) // cornering / tilt spike
            addAll(series(55.1, 54.5, 15, startMs = 9 * 60_000L))
        }
        assertNull(RefuelSplash.detect(trip))
    }

    @Test
    fun recordingThatStartsAtThePumpStillSeesTheFill() {
        val trip = buildList {
            addAll(series(17.6, 93.7, 10, spanMs = 3 * 60_000L))
            addAll(series(93.7, 92.0, 20, startMs = 4 * 60_000L, spanMs = 30 * 60_000L))
        }
        val splash = RefuelSplash.detect(trip)
        assertNotNull(splash)
        assertTrue(splash!!.risePct > 70.0)
    }

    @Test
    fun aReceiptWindowThatCannotExplainTheRiseIsNotBlamedOnIt() {
        // The samples show a small 6 pt top-up; the linked receipt is a 76 pt brim elsewhere.
        val trip = buildList {
            addAll(series(40.0, 40.0, 10))
            addAll(series(40.0, 46.0, 8, startMs = 6 * 60_000L))
            addAll(series(46.0, 45.0, 10, startMs = 8 * 60_000L))
        }
        val resolved = RefuelSplash.resolve(
            levelSeries = trip,
            fillBeforePct = 17.6,
            fillAfterPct = 93.7,
            pumpLitres = 40.27,
            linkedReceiptOnTrip = true
        )
        assertTrue(resolved.happenedOnThisTrip)
        assertFalse(resolved.receiptMatches)
        assertNull(resolved.pumpLitres)
        assertEquals(6.0, resolved.fillAfterPct!! - resolved.fillBeforePct!!, 1.5)
    }

    @Test
    fun noTankSamplesFallsBackToTheLinkerWindowOnlyWhenItIsOnThisTrip() {
        val onTrip = RefuelSplash.resolve(emptyList(), 17.6, 93.7, 40.27, linkedReceiptOnTrip = true)
        assertTrue(onTrip.happenedOnThisTrip)
        assertEquals(RefuelSplash.Evidence.LINKED_WINDOW, onTrip.evidence)
        assertEquals(40.27, onTrip.pumpLitres!!, 1e-9)

        val elsewhere = RefuelSplash.resolve(emptyList(), 17.6, 93.7, 40.27, linkedReceiptOnTrip = false)
        assertFalse(elsewhere.happenedOnThisTrip)
    }

    @Test
    fun fillBurnIsMeasuredAroundTheSplashNotAcrossTheFill() {
        // Splash 17.6 → 93.7 at 71 pts, trip ends at 92.4 %: net float burn is the 1.3 pt
        // after the pump (≈0.59 L on 45 L), NOT the 71 pt rise (32 L) and not a negative burn.
        val trip = buildList {
            addAll(series(17.6, 17.6, 6))
            addAll(series(17.6, 93.7, 8, startMs = 3 * 60_000L))
            addAll(series(93.7, 92.4, 20, startMs = 8 * 60_000L, spanMs = 40 * 60_000L))
        }
        val splash = RefuelSplash.detect(trip)!!
        val split = MidTripFuel.split(
            tripStartPct = 17.6,
            tripEndPct = 92.4,
            tankL = TankCapacity.NOMINAL_L,
            fillBeforePct = splash.beforePct,
            fillAfterPct = splash.afterPct,
            pumpLitres = 40.27,
            fillOnThisTrip = true
        )
        assertTrue(split.tripIsBrim)
        assertTrue("net burn ${split.netBurnLitres}", (split.netBurnLitres ?: 0.0) < 2.0)
        // PID fill window on the 45 L spec must NOT be the 34.25 L the owner was shown.
        assertEquals(45.0 * 0.761, split.pidFillLitres!!, 1.0)
    }
}
