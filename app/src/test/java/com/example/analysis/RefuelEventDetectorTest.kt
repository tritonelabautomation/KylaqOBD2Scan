package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The since-refuel state machine, pinned down (owner 2026-09-19: "create an event driven
 * mechanism to track since refueling ... you will see change in tank fuel level PID 012F %").
 *
 * The signature a sloshing tank cannot fake: a level rise of >= 4 points ACROSS A STOP, or across
 * a session gap. Measured wiggle on the owner's 09-17 drive was +/-1-2 points while moving, and
 * the fill itself rose 37.6 -> 93.7 % entirely between sessions (engine off at the pump, receipt
 * 12:05:06), which is why both modes exist.
 */
class RefuelEventDetectorTest {

    private fun lvl(ts: Long, pct: Double) = RefuelEventDetector.Sample(ts, "012F", pct)
    private fun spd(ts: Long, kmh: Double) = RefuelEventDetector.Sample(ts, "010D", kmh)
    private fun odo(ts: Long, km: Double) = RefuelEventDetector.Sample(ts, "01A6", km)

    @Test
    fun aRiseAcrossAStopIsAnEvent() {
        val d = RefuelEventDetector()
        assertNull(d.onSample(lvl(0, 40.0)))
        assertNull(d.onSample(spd(10, 45.0)))          // moving: window closed, level is stamp only
        assertNull(d.onSample(spd(100, 0.0)))          // stop opens the window, base = 40.0
        assertNull(d.onSample(lvl(120, 88.0)))         // the fill, engine off at the pump
        val ev = d.onSample(spd(500, 12.0))            // driving off closes it
        assertEquals(40.0, ev!!.levelBeforePct, 1e-9)
        assertEquals(88.0, ev.levelAfterPct, 1e-9)
        assertEquals(100L, ev.windowStartMs)
        assertEquals(120L, ev.windowEndMs)
        assertTrue(!ev.betweenSessions)
    }

    @Test
    fun theSameRiseWhileMovingIsNotAnEvent() {
        val d = RefuelEventDetector()
        d.onSample(lvl(0, 40.0))
        d.onSample(spd(10, 45.0))
        assertNull(d.onSample(lvl(120, 88.0)))         // rise with no stop: slosh or a bad row
        assertNull(d.onSample(spd(500, 50.0)))
        assertNull(d.onSessionEnd(600))
    }

    @Test
    fun drivingSloshInsideAStopIsNotAnEvent() {
        val d = RefuelEventDetector()
        d.onSample(lvl(0, 40.0))
        d.onSample(spd(10, 0.0))
        d.onSample(lvl(20, 41.5))                      // +/-1-2 pts of slosh at a signal
        d.onSample(lvl(30, 38.9))
        assertNull(d.onSample(spd(500, 20.0)))
        assertNull(d.onSessionEnd(600))
    }

    @Test
    fun aKoeoWindowFlushesAtSessionEnd() {
        // Session B of 09-17: parked idling, engine off 12:46:07, key-on answers until 12:48:54.
        // A fill inside such a window is only provable when the session ends.
        val d = RefuelEventDetector()
        d.onSample(lvl(0, 37.0))
        d.onSample(spd(10, 0.0))
        d.onSample(lvl(60, 95.0))
        val ev = d.onSessionEnd(120)
        assertEquals(37.0, ev!!.levelBeforePct, 1e-9)
        assertEquals(95.0, ev.levelAfterPct, 1e-9)
    }

    @Test
    fun theGapBetweenSessionsIsAnEvent() {
        // The owner's actual 09-17 refuel: last stamp of session A 37.6 %, first row of the next
        // recorded session 93.7 %, receipt 12:05:06 in between.
        val ev = RefuelEventDetector.crossSession(
            prevTsMs = 1_000_000, prevLevelPct = 37.6, prevOdoKm = 3501.0,
            firstTsMs = 2_500_000, firstLevelPct = 93.7, firstOdoKm = 3525.8
        )
        assertTrue(ev!!.betweenSessions)
        assertEquals(3525.8, ev.odoKm!!, 1e-9)
        assertEquals(56.1, ev.risePct, 1e-6)
    }

    @Test
    fun aSmallGapRiseIsNotAnEvent() {
        assertNull(
            RefuelEventDetector.crossSession(0, 40.0, null, 9_000_000, 42.5, null)
        )
    }

    @Test
    fun aStopBeforeAnyLevelRowCannotInventARise() {
        val d = RefuelEventDetector()
        d.onSample(spd(10, 0.0))                       // window opens with no base level
        d.onSample(lvl(20, 90.0))                      // first level IS the base
        assertNull(d.onSample(spd(500, 10.0)))
    }

    @Test
    fun twoFillsInOneSessionAreTwoEvents() {
        val d2 = RefuelEventDetector()
        val got = listOf(
            d2.onSample(lvl(0, 30.0)),
            d2.onSample(spd(1, 0.0)), d2.onSample(lvl(2, 80.0)), d2.onSample(spd(3, 30.0)),
            d2.onSample(lvl(4, 78.0)),
            d2.onSample(spd(5, 0.0)), d2.onSample(lvl(6, 99.0)), d2.onSample(spd(7, 30.0))
        ).filterNotNull()
        assertEquals(2, got.size)
        assertEquals(50.0, got[0].risePct, 1e-9)
        assertEquals(21.0, got[1].risePct, 1e-9)
    }

    @Test
    fun theOdometerRidesAlongWithTheEvent() {
        val d = RefuelEventDetector()
        d.onSample(lvl(0, 40.0))
        d.onSample(odo(5, 3501.0))
        d.onSample(spd(10, 0.0))
        d.onSample(lvl(20, 90.0))
        val ev = d.onSample(spd(500, 5.0))
        assertEquals(3501.0, ev!!.odoKm!!, 1e-9)
    }
}

class SinceRefuelStatsTest {

    private fun row(ts: Long, pid: String, v: Double?) = SinceRefuelStats.Row(ts, pid, v)

    @Test
    fun distanceUsesTheMonotonicOdometerMax() {
        // Raw 01A6 frames regress on CAN/ELM transients (ledger 8.5: 3495.2 then 3490.9 then
        // 3495.0). An odometer cannot unwind, so distance is first-to-max, never first-to-last.
        val stats = SinceRefuelStats.summarize(
            listOf(
                row(0, "01A6", 3487.9),
                row(10, "01A6", 3495.2),
                row(20, "01A6", 3490.9),
                row(30, "01A6", 3494.9)
            )
        )
        assertEquals(7.3, stats.distanceKm, 1e-6)
    }

    @Test
    fun massFlowIntegratesAtPetrolDensity() {
        // 0.30 g/s idle for one hour = 1.08 kg = 1.4497 L at 0.745 kg/L - the same constant and
        // arithmetic the app's PID screen prints as "0.30 g/s ~ 1.45 L/h".
        val stats = SinceRefuelStats.summarize(
            listOf(
                row(0, "019D", 0.30),
                row(3_600_000, "019D", 0.30)
            )
        )
        assertEquals(0.30 * 3.6 / 0.745, stats.fuelLiters, 1e-9)
        assertEquals(3600L, stats.durationSec)
    }

    @Test
    fun volumeFlowIsTakenDirectly() {
        val stats = SinceRefuelStats.summarize(
            listOf(row(0, "015E", 2.0), row(1_800_000, "015E", 2.0))
        )
        assertEquals(1.0, stats.fuelLiters, 1e-9)
    }

    @Test
    fun economyAndAverageSpeedFollowFromTheTwo() {
        val stats = SinceRefuelStats.summarize(
            listOf(
                row(0, "01A6", 3500.0),
                row(0, "019D", 1.0),
                row(3_600_000, "019D", 1.0),
                row(3_600_000, "01A6", 3510.0)
            )
        )
        assertEquals(10.0, stats.distanceKm, 1e-9)
        assertEquals(10.0 / (3.6 / 0.745), stats.kmL!!, 1e-6)
        assertEquals(10.0, stats.avgSpeedKmh!!, 1e-6)
    }

    @Test
    fun emptyInputIsAnHonestZero() {
        val stats = SinceRefuelStats.summarize(emptyList())
        assertEquals(0.0, stats.distanceKm, 1e-12)
        assertEquals(0.0, stats.fuelLiters, 1e-12)
        assertNull(stats.kmL)
    }
}
