package com.example

import com.example.ai.GeminiTextClient
import com.example.data.Fmt
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM tests for unit/currency formatting and receipt-scan JSON parsing. */
class FmtAndReceiptParseTest {

    @Test
    fun `metric and imperial formatting`() {
        assertEquals("10.0 km", Fmt.distance(10.0, metric = true))
        assertEquals("6.2 mi", Fmt.distance(10.0, metric = false))
        assertEquals("100 km/h", Fmt.speed(100.0, metric = true))
        assertEquals("62 mph", Fmt.speed(100.0, metric = false))
        assertEquals("15.0 km/L", Fmt.efficiency(15.0, metric = true))
        assertEquals("--", Fmt.distance(null))
        assertEquals("₹350", Fmt.money(350.0))
        assertEquals("₹3.50", Fmt.money(3.5, decimals = 2))
    }

    @Test
    fun `receipt json parses with missing fields`() {
        val json = """{"liters":35.2,"price_per_litre":108.5,"total":3819,"vendor":"BPCL","category":"Fuel"}"""
        val scan = GeminiTextClient.parseReceiptJson(json)!!
        assertEquals(35.2, scan.liters!!, 0.001)
        assertEquals(108.5, scan.pricePerL!!, 0.001)
        assertEquals(3819.0, scan.total!!, 0.001)
        assertEquals("BPCL", scan.vendor)
        assertEquals("Fuel", scan.category)

        val partial = GeminiTextClient.parseReceiptJson("""{"total":500,"category":"Service"}""")!!
        assertNull(partial.liters)
        assertNull(partial.pricePerL)
        assertEquals(500.0, partial.total!!, 0.001)

        assertNull(GeminiTextClient.parseReceiptJson("not json at all"))
    }
}
