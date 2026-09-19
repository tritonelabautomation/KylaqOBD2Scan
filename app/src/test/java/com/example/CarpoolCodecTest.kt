package com.example

import com.example.data.CarpoolCodec
import com.example.data.CarpoolCodec.CarpoolEntry
import com.example.data.CarpoolCodec.Rider
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
                Rider("Ravi|Kumar", 150.0),
                Rider("Semi;Colon", 150.0),
                Rider("Tilde~Wave", 100.0),
                Rider("", 75.5)
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
        assertEquals(475.5, back.earned, 1e-9)
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
}
