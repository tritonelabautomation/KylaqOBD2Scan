package com.example

import com.example.data.CarpoolCodec
import com.example.data.CarpoolCodec.CarpoolEntry
import com.example.data.CarpoolCodec.Rider
import com.example.data.RecordTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car-pool ledger's pure core (owner 2026-09-19): line codec with hostile rider names, the
 * four-seat cap, and the IST month roll-up with its effective-cost arithmetic. A ride logged at
 * 00:20 IST on the 1st is the new month even though UTC still calls it the previous day.
 */
class CarpoolCodecTest {

    @Test
    fun roundTripSurvivesHostileNamesAndFourRiders() {
        val e = CarpoolEntry(
            idMs = 1_700_000_000_000L,
            tripId = "trip_ab12",
            dateUtc = "2026-09-19T08:05:00+05:30",
            distanceKm = 24.5,
            riders = listOf(
                Rider("Ravi|Kumar", 150.0, 10.0),
                Rider("Semi;Colon", 150.0, 20.0),
                Rider("Tilde~Wave", 100.0, null),
                Rider("", 75.5, 24.5)
            )
        )
        val back = CarpoolCodec.decode(CarpoolCodec.encode(e))!!
        assertEquals(e.idMs, back.idMs)
        assertEquals(e.tripId, back.tripId)
        assertEquals(e.distanceKm, back.distanceKm, 1e-9)
        assertEquals(4, back.riders.size)
        assertEquals("Ravi|Kumar", back.riders[0].name)
        assertEquals("Semi;Colon", back.riders[1].name)
        assertEquals("Tilde~Wave", back.riders[2].name)
        assertEquals(10.0, back.riders[0].distanceKm!!, 1e-9)
        assertEquals(20.0, back.riders[1].distanceKm!!, 1e-9)
        assertEquals(null, back.riders[2].distanceKm)
        assertEquals(475.5, back.earned, 1e-9)
        // cost per km
        assertEquals(15.0, back.riders[0].amount / back.riders[0].effectiveDistance(back.distanceKm), 1e-9)
    }

    @Test
    fun oldFormatWithoutRiderDistanceStillDecodes() {
        // c1 format: name~amount only, distance null -> fallback to trip distance
        val oldLine = "c1|1700000000000|2026-09-19T08:05:00+05:30|24.50|trip_ab12|Ravi~150.00;Kumar~100.00"
        val decoded = CarpoolCodec.decode(oldLine)!!
        assertEquals(2, decoded.riders.size)
        assertEquals(null, decoded.riders[0].distanceKm)
        assertEquals(24.5, decoded.riders[0].effectiveDistance(decoded.distanceKm), 1e-9)
    }

    @Test
    fun standaloneEntriesAndGarbageLinesDecodeSafely() {
        val solo = CarpoolEntry(2L, null, "2026-09-18T18:00:00+05:30", 12.0, listOf(Rider("A", 50.0)))
        assertEquals(null, CarpoolCodec.decode(CarpoolCodec.encode(solo))!!.tripId)
        assertNull(CarpoolCodec.decode("c1|not|enough"))
        assertNull(CarpoolCodec.decode("x9|1|d|1.0|-|A~1"))
    }

    @Test
    fun monthlyGroupsByIstMonthAndDoesTheEffectiveMath() {
        val ist = ZoneId.of("Asia/Kolkata")
        // 2026-10-01 00:20 IST is still 2026-09-30 18:50 UTC: the month key must follow IST.
        val octFirst = ZonedDateTime.of(2026, 10, 1, 0, 20, 0, 0, ist).toInstant().toEpochMilli()
        val septMid = ZonedDateTime.of(2026, 9, 15, 9, 0, 0, 0, ist).toInstant().toEpochMilli()
        val entries = listOf(
            CarpoolEntry(septMid, "t1", "x", 20.0, listOf(Rider("A", 100.0), Rider("B", 100.0))),
            CarpoolEntry(octFirst, "t2", "x", 24.0, listOf(Rider("C", 150.0)))
        )
        val rows = CarpoolCodec.monthly(entries) { if (it.tripId == "t1") 350.0 else null }
        assertEquals(2, rows.size)
        assertEquals("2026-09", rows[0].month)
        assertEquals(200.0, rows[0].earned, 1e-9)
        assertEquals(150.0, rows[0].effective!!, 1e-9) // 350 fuel - 200 earned
        assertEquals("2026-10", rows[1].month)
        assertEquals(150.0, rows[1].earned, 1e-9)
        assertNull(rows[1].effective) // fuel cost unknown => no fabricated effective
        assertTrue(rows[1].fuelCost == null)
    }

    @Test
    fun ridesLinkToTheTripWhoseWindowCoversThem() {
        val w = listOf(
            CarpoolCodec.TripWindow("t1", 1_000, 2_000),
            CarpoolCodec.TripWindow("t2", 5_000, null) // still recording: open end covers onward
        )
        assertEquals("t1", CarpoolCodec.tripLinkFor(1_500, w))
        assertEquals("t1", CarpoolCodec.tripLinkFor(2_000, w)) // window end inclusive
        assertEquals("t2", CarpoolCodec.tripLinkFor(9_000, w))
        // The gap between two trips is exactly the owner's abruptly-stopped case: no trip
        // covers it, the ride stays standalone until a recovery replays the session.
        assertNull(CarpoolCodec.tripLinkFor(3_000, w))
    }

    @Test
    fun whenMsRoundTripsTheIstStamp() {
        val ist = ZoneId.of("Asia/Kolkata")
        val ms = ZonedDateTime.of(2026, 9, 19, 22, 21, 0, 0, ist).toInstant().toEpochMilli()
        val e = CarpoolEntry(1L, null, RecordTime.stamp(ms), 10.0, listOf(Rider("A", 50.0)))
        assertEquals(ms, CarpoolCodec.whenMs(e))
    }

    @Test
    fun monthCardsTakeTheirFuelFromTheRefuelLedger() {
        // Owner 2026-09-20: "its not fetching the fuel to cost fetch the price and show the
        // effective cost". The month's pump money is what the fuel ledger says he spent that
        // month; effective recomputes as fuel - earned so the card always shows net or saving.
        val rows = listOf(
            CarpoolCodec.MonthRow("2026-08", 1, 30.0, 235.0, null, null),
            CarpoolCodec.MonthRow("2026-07", 2, 60.0, 500.0, 400.0, -100.0)
        )
        val merged = CarpoolCodec.withRefuelFuel(rows, mapOf("2026-08" to 1296.0))
        assertEquals(1296.0, merged[0].fuelCost!!, 0.001)
        assertEquals(1061.0, merged[0].effective!!, 0.001) // 1296 - 235, still out of pocket
        // No refuel that month: the trip-derived cost survives...
        assertEquals(400.0, merged[1].fuelCost!!, 0.001)
        assertEquals(-100.0, merged[1].effective!!, 0.001)
        // ...and a month with neither refuel nor trip fuel is cash-zero, not a guess:
        val zero = CarpoolCodec.withRefuelFuel(
            listOf(CarpoolCodec.MonthRow("2026-09", 13, 338.0, 2534.0, null, null)), emptyMap()
        )[0]
        assertEquals(0.0, zero.fuelCost!!, 0.001)
        assertEquals(-2534.0, zero.effective!!, 0.001) // earned beat fuel spend: a saving
        assertEquals(2534.0, zero.earned, 0.001)
    }

    @Test
    fun aBackDatedRideBelongsToTheMonthItHappenedNotTheMonthItWasSaved() {
        // Owner 2026-09-20: the August header read "1 ride(s) - 0 km - earned 0" above a
        // 30 km / 235 ride, because monthly() keyed by the SAVE instant. Saved 2026-09-09,
        // rode 2026-08-18: the row must be August's, with the ride's own km and money.
        val e = CarpoolEntry(
            idMs = RecordTime.parseStamp("2026-09-09T01:29:00.000+05:30")!!,
            tripId = null,
            dateUtc = "2026-08-18T00:41:00.000+05:30",
            distanceKm = 30.0,
            riders = listOf(Rider("Akash", 235.0))
        )
        val rows = CarpoolCodec.monthly(listOf(e)) { null }
        assertEquals(1, rows.size)
        assertEquals("2026-08", rows[0].month)
        assertEquals(30.0, rows[0].distanceKm, 0.001)
        assertEquals(235.0, rows[0].earned, 0.001)
    }
}
