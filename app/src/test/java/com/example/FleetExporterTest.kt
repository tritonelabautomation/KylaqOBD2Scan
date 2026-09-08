package com.example

import com.example.data.DocumentCodec
import com.example.data.ExpenseCodec
import com.example.data.FleetExporter
import com.example.data.FuelLogCodec
import com.example.data.MaintenanceCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM tests for the unified export ledger (CSV + JSON rendering). */
class FleetExporterTest {

    private val fuel = listOf(
        FuelLogCodec.FuelEntry(
            idMs = 3L, dateUtc = "2026-09-01T10:00:00Z", liters = 30.0, pricePerL = 108.0,
            odometerKm = 4200.0, station = "BPCL, Sector 9", grade = "X95", note = ""
        )
    )
    private val services = listOf(
        MaintenanceCatalog.ServiceLog(
            itemId = "engine_oil", dateUtc = "2026-08-15T09:00:00Z", dateMs = 2L,
            odometerKm = 3900.0, cost = 4500.0, rating = 4, notes = "oil|filter"
        )
    )
    private val expenses = listOf(
        ExpenseCodec.ExpenseEntry(
            idMs = 1L, dateUtc = "2026-09-03T08:00:00Z", category = "Toll",
            amount = 260.0, vendor = "FASTag", note = "ORR"
        )
    )
    private val documents = listOf(
        DocumentCodec.VehicleDocument(
            idMs = 0L, type = "Insurance", title = "Comprehensive", number = "POL-1",
            issuer = "HDFC", expiryUtc = "2027-01-01T00:00:00Z", expiryMs = 1798761600000L
        )
    )

    @Test
    fun `rows merge all ledgers newest first`() {
        val rows = FleetExporter.collectRows(fuel, services, expenses, documents)
        assertEquals(4, rows.size)
        // Documents sort by expiry (2027), then the 2026 ledger rows newest-first.
        assertEquals("Document", rows[0].type)
        assertEquals("Toll", rows[1].type) // 2026-09-03
        assertEquals("Fuel", rows[2].type) // 2026-09-01
        assertEquals("Service", rows[3].type) // 2026-08-15
        assertEquals(3240.0, rows[2].amount!!, 0.001) // 30 L * 108
        assertEquals("4/5", rows[3].detail.substring(0, 3))
    }

    @Test
    fun `csv escapes commas and quotes`() {
        val csv = FleetExporter.toCsv(FleetExporter.collectRows(fuel, services, expenses, documents))
        val header = csv.lineSequence().first()
        assertEquals("date,type,name,amount,odometer_km,detail", header)
        assertTrue(csv.contains("\"BPCL, Sector 9\"") || csv.contains("BPCL, Sector 9"))
        // station with comma must be quoted inside the detail cell
        assertTrue(csv.contains("\"\"\"") || csv.lines().any { it.contains("\"") })
        assertEquals(5, csv.trim().lineSequence().count()) // header + 4 rows
    }

    @Test
    fun `json carries vehicle meta and entries`() {
        val rows = FleetExporter.collectRows(fuel, services, expenses, documents)
        val json = JSONObject(FleetExporter.toJson(rows, "Kylaq 1.0 TSI", "15.2 km/L lifetime"))
        assertEquals("KylaqOBD2Scan", json.getString("app"))
        assertEquals("Kylaq 1.0 TSI", json.getString("vehicle"))
        assertEquals(4, json.getJSONArray("entries").length())
        val entries = json.getJSONArray("entries")
        assertEquals("Document", entries.getJSONObject(0).getString("type"))
        val toll = entries.getJSONObject(1)
        assertEquals("Toll", toll.getString("type"))
        assertEquals(260.0, toll.getDouble("amount"), 0.001)
    }
}
