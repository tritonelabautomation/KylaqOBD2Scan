package com.example

import com.example.analysis.TripFuelSummary
import com.example.analysis.WeeklyTripOverview
import com.example.analysis.WeeklyTripOverview.DriveBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Trip Overview week analytics (owner benchmark 2026-09-13).
 * All numbers hand-computed:
 *  - histogram bins 0,10 -> CONGESTED 30 s; 20 -> SLOW 30 s; 50 -> NORMAL 40 s; idle 5 s STOPPED
 *  - score: calm 1-60/3600=0.983333, flow min(1,60/45)=1, eff 15/19=0.789474,
 *    coast 400/360=1, discipline 1-5/65=0.923077 -> 0.931663 -> 93 ("Good")
 *  - week totals: 30 + 20 km -> total 50, daily avg 50/7 = 7.142857, top day 30
 */
class WeeklyTripOverviewTest {

    private fun sum(
        dist: Double, kmL: Double?, dur: Long, idle: Double = 0.0, coast: Double = 0.0,
        movAvg: Double = 0.0, hist: List<Pair<Int, Double>> = emptyList()
    ) = TripFuelSummary.Summary(
        fuelLiters = 0.0, distanceKm = dist, kmPerLiter = kmL, durationSeconds = dur,
        averageSpeedKmh = movAvg, movingAverageSpeedKmh = movAvg, maxSpeedKmh = 120.0,
        coastSeconds = coast, idleSeconds = idle, speedHistogram = hist, sampleCount = 10,
        hasSpeedSeries = hist.isNotEmpty(), hasFuelSeries = kmL != null
    )

    @Test
    fun `band split follows histogram bins and idle seconds`() {
        val secs = WeeklyTripOverview.bandSeconds(
            sum(10.0, null, 105L, idle = 5.0, hist = listOf(0 to 10.0, 10 to 20.0, 20 to 30.0, 50 to 40.0))
        )
        assertEquals(30.0, secs[DriveBand.CONGESTED]!!, 0.001)
        assertEquals(30.0, secs[DriveBand.SLOW]!!, 0.001)
        assertEquals(40.0, secs[DriveBand.NORMAL]!!, 0.001)
        assertEquals(5.0, secs[DriveBand.STOPPED]!!, 0.001)
    }

    @Test
    fun `score weights produce the hand-computed 93 Good`() {
        val s = sum(30.0, 15.0, 3600L, idle = 60.0, coast = 400.0, movAvg = 60.0, hist = listOf(60 to 3600.0))
        assertEquals(93, WeeklyTripOverview.scoreOf(s))
        assertEquals("Good", WeeklyTripOverview.scoreLabel(93))
        assertEquals("Fair", WeeklyTripOverview.scoreLabel(60))
        assertEquals("Needs work", WeeklyTripOverview.scoreLabel(40))
    }

    @Test
    fun `week grouping totals and previous-week window`() {
        val now = System.currentTimeMillis()
        val start = WeeklyTripOverview.weekStartMs(now, 0)
        val day = 86_400_000L
        val trips = listOf(
            WeeklyTripOverview.TripInput("t1", "Morning drive", start + day, sum(30.0, 15.0, 3600L, hist = listOf(60 to 3000.0))),
            WeeklyTripOverview.TripInput("t2", "Evening drive", start + 6 * day, sum(20.0, 12.0, 2400L, hist = listOf(40 to 2000.0))),
            WeeklyTripOverview.TripInput("t0", "Last week", start - 3 * day, sum(44.0, 14.0, 3000L, hist = listOf(50 to 2500.0)))
        )
        val ov = WeeklyTripOverview.overview(trips, now, 0)
        assertEquals(50.0, ov.totals.totalKm, 0.001)
        assertEquals(50.0 / 7.0, ov.totals.dailyAvgKm, 0.001)
        assertEquals(30.0, ov.totals.topDayKm, 0.001)
        assertEquals(2, ov.trips.size) // only this week's two
        assertEquals("t2", ov.trips.first().tripId) // newest first
        assertNotNull(ov.prev)
        assertEquals(44.0, ov.prev!!.totalKm, 0.001)
        assertNotNull(ov.score)
        assertTrue(ov.bandPercents.values.sum() in 99.9..100.1)
        assertTrue(ov.rangeLabel.contains("–"))
    }

    @Test
    fun `empty week yields zeroed overview with null score and prev`() {
        val ov = WeeklyTripOverview.overview(emptyList(), System.currentTimeMillis(), 0)
        assertEquals(0.0, ov.totals.totalKm, 0.001)
        assertTrue(ov.trips.isEmpty())
        assertNull(ov.score)
        assertNull(ov.prev)
        assertEquals(7, ov.days.size)
    }

    @Test
    fun `trip titles follow time of day`() {
        val cal = java.util.Calendar.getInstance()
        fun at(hour: Int): Long {
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, 0)
            return cal.timeInMillis
        }
        assertEquals("Morning drive", WeeklyTripOverview.tripTitle(at(8), ""))
        assertEquals("Midday drive", WeeklyTripOverview.tripTitle(at(13), ""))
        assertEquals("Evening drive", WeeklyTripOverview.tripTitle(at(19), ""))
        assertEquals("Night drive", WeeklyTripOverview.tripTitle(at(2), ""))
    }
}
