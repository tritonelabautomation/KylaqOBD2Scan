package com.example

import com.example.data.FuelLogCodec
import com.example.data.FuelLogCodec.FuelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuelio-parity fuel log maths (added 2026-09-09): codec v1/v2 compatibility,
 * partial-tank interval semantics, station intelligence and CSV merge.
 */
class FuelLogParityTest {

    private fun entry(
        id: Long,
        odo: Double?,
        liters: Double,
        price: Double = 120.0,
        station: String = "",
        partial: Boolean = false
    ) = FuelEntry(
        idMs = id,
        dateUtc = "2026-09-0${(id % 9) + 1}T10:00:00Z",
        liters = liters,
        pricePerL = price,
        odometerKm = odo,
        station = station,
        grade = FuelLogCodec.GRADE_REGULAR,
        note = "",
        partial = partial
    )

    @Test
    fun `v1 lines still decode with partial defaulting to false`() {
        val v1 = "f1|1000|2026-01-01T00:00:00Z|30.0000|110.0000|1500.0000|IOCCL|X95|note"
        val e = FuelLogCodec.decode(v1)
        assertTrue(e != null)
        assertEquals(false, e!!.partial)
        assertEquals("IOCCL", e.station)
    }

    @Test
    fun `v2 roundtrip preserves partial flag and note`() {
        val e = entry(5, 100.0, 10.0, station = "BP, Uppal", partial = true).copy(note = "line|break")
        val decoded = FuelLogCodec.decode(FuelLogCodec.encode(e))
        assertEquals(e, decoded)
        assertTrue(decoded!!.partial)
        assertEquals("line|break", decoded.note)
    }

    @Test
    fun `unknown or corrupted lines decode to null`() {
        assertNull(FuelLogCodec.decode("f9|1|2|3"))
        assertNull(FuelLogCodec.decode(""))
    }

    @Test
    fun `partial fill-ups fold into the next full-tank interval (Fuelio semantics)`() {
        val list = listOf(
            entry(1, 100.0, 10.0),          // anchors
            entry(2, null, 5.0, partial = true), // contributes litres, never anchors
            entry(3, 260.0, 12.0)           // closes interval: 160 km / 17 L
        )
        val intervals = FuelLogCodec.intervals(list)
        assertEquals(1, intervals.size)
        assertEquals(3L, intervals[0].first)
        assertEquals(160.0 / 17.0, intervals[0].second, 1e-9)
    }

    @Test
    fun `all-full logs match the legacy consecutive-delta maths`() {
        val list = listOf(entry(1, 0.0, 10.0), entry(2, 150.0, 10.0), entry(3, 310.0, 10.0))
        val intervals = FuelLogCodec.intervals(list)
        assertEquals(2, intervals.size)
        assertEquals(15.0, intervals[0].second, 1e-9)
        assertEquals(16.0, intervals[1].second, 1e-9)
    }

    @Test
    fun `station intelligence aggregates per station from own log only`() {
        val list = listOf(
            entry(1, 100.0, 10.0, price = 115.0, station = "IOCL Begumpet"),
            entry(2, 400.0, 10.0, price = 121.0, station = "IOCL Begumpet"),
            entry(3, 700.0, 10.0, price = 118.0, station = "BP Uppal")
        )
        val stats = FuelLogCodec.stationStats(list)
        assertEquals(2, stats.size)
        val iocl = stats.first { it.name == "IOCL Begumpet" }
        assertEquals(2, iocl.visits)
        assertEquals(118.0, iocl.avgPrice, 1e-9)
        assertEquals(115.0, iocl.bestPrice, 1e-9)
        assertEquals(121.0, iocl.lastPrice, 1e-9)
    }

    @Test
    fun `csv roundtrip survives commas quotes and newlines`() {
        val list = listOf(
            entry(1, 100.0, 36.89, price = 125.17, station = "COCO, IOCL \"Begumpet\"", partial = false)
                .copy(note = "multi\nline"),
            entry(2, null, 5.0, partial = true)
        )
        val back = FuelLogCodec.fromCsv(FuelLogCodec.toCsv(list))
        assertEquals(2, back.size)
        assertEquals(list[0].station, back[0].station)
        assertEquals(list[0].note, back[0].note)
        assertTrue(back[1].partial)
        assertFalse(back[0].partial)
    }

    @Test
    fun `merge keeps existing entries on id collision and appends newcomers`() {
        val existing = listOf(entry(1, 100.0, 10.0), entry(2, 250.0, 10.0))
        val incoming = listOf(entry(2, 999.0, 99.0), entry(3, 400.0, 10.0))
        val merged = FuelLogCodec.merge(existing, incoming)
        assertEquals(3, merged.size)
        assertEquals(250.0, merged.first { it.idMs == 2L }.odometerKm!!, 1e-9)
        assertTrue(merged.any { it.idMs == 3L })
    }

    @Test
    fun `costThisMonth sums only the current calendar month`() {
        val now = System.currentTimeMillis()
        val thisMonth = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
        val lastYear = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.YEAR, -1)
        }.timeInMillis
        val list = listOf(entry(thisMonth, 100.0, 10.0, price = 10.0), entry(lastYear, 50.0, 10.0, price = 10.0))
        assertEquals(100.0, FuelLogCodec.costThisMonth(list, now)!!, 1e-9)
    }
}
