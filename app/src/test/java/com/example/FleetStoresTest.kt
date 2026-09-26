package com.example

import com.example.data.DocumentCodec
import com.example.data.ExpenseCodec
import com.example.data.ReminderCodec
import com.example.data.VoiceParse
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM codec + voice-parse tests for the fleet-parity stores. */
class FleetStoresTest {

    @Test
    fun `expense round trips with pipes and newlines`() {
        val e = ExpenseCodec.ExpenseEntry(
            idMs = 42L, dateUtc = "2026-09-08T01:02:03Z", category = "Car Wash",
            amount = 350.5, vendor = "Express | Wash", note = "foam\nwash"
        )
        val d = ExpenseCodec.decode(ExpenseCodec.encode(e))
        assertNotNull(d)
        assertEquals("Express | Wash", d!!.vendor)
        assertEquals("foam\nwash", d.note)
        assertEquals(350.5, d.amount, 0.001)
        assertNull(ExpenseCodec.decode("e9|1|d|c|1|v|n"))
        assertNull(ExpenseCodec.decode("e1|bad|d|c|1|v|n"))
    }

    @Test
    fun `document round trips with optional expiry`() {
        val withExpiry = DocumentCodec.VehicleDocument(1L, "Insurance", "HDFC policy", "POL-1", "HDFC", "2027-03-31", 1806422400000L)
        val d1 = DocumentCodec.decode(DocumentCodec.encode(withExpiry))
        assertEquals(1806422400000L, d1!!.expiryMs)
        val noExpiry = DocumentCodec.VehicleDocument(2L, "Other", "Loan letter", "", "", null, null)
        val d2 = DocumentCodec.decode(DocumentCodec.encode(noExpiry))
        assertNull(d2!!.expiryMs)
        assertNull(d2.expiryUtc)
    }

    @Test
    fun `reminder repeat rolls forward past now`() {
        val day = 24L * 60 * 60 * 1000L
        val now = 1_757_000_000_000L
        val monthly = ReminderCodec.CustomReminder(1L, "Insurance renewal", now - 400 * day, ReminderCodec.Repeat.MONTHLY, null, null, false, "")
        val next = ReminderCodec.nextDue(monthly, now)
        assertTrue(next > now)
        assertTrue(next - now <= 30 * day)
        val oneOff = ReminderCodec.CustomReminder(2L, "PUC", now - 5 * day, ReminderCodec.Repeat.NONE, null, null, false, "")
        assertEquals(now - 5 * day, ReminderCodec.nextDue(oneOff, now))
        val roundTrip = ReminderCodec.decode(ReminderCodec.encode(monthly))
        assertEquals(ReminderCodec.Repeat.MONTHLY, roundTrip!!.repeat)
        assertNull(ReminderCodec.decode("r1|1|t|1|BOGUS|-|-|0|n"))
    }

    @Test
    fun `voice parse extracts litres and price`() {
        val t = "filled 35 litres at BPCL total 3200 rupees"
        assertEquals(35.0, VoiceParse.liters(t)!!, 0.001)
        assertEquals(3200.0, VoiceParse.price(t)!!, 0.001)
        assertEquals(150.0, VoiceParse.amount("paid 150 for car wash at Express Wash")!!, 0.001)
        assertNull(VoiceParse.liters("no numbers here"))
    }
}
