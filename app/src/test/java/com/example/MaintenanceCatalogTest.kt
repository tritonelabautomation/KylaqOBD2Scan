package com.example

import com.example.data.MaintenanceCatalog
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM due-state tests for the Kylaq service catalogue (VehIQ-style health board). */
class MaintenanceCatalogTest {

    private val oil = MaintenanceCatalog.KYLAQ_ITEMS.first { it.id == "engine_oil" }
    private val pads = MaintenanceCatalog.KYLAQ_ITEMS.first { it.id == "front_pads" }
    private val now = 1_757_000_000_000L // fixed "today"
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `unlogged item is unknown`() {
        val state = MaintenanceCatalog.evaluate(oil, null, 20_000.0, now)
        assertEquals(MaintenanceCatalog.DueStatus.UNKNOWN, state.status)
        assertEquals("Not logged yet", state.headline)
    }

    @Test
    fun `fresh service is good with remaining km and days`() {
        val last = MaintenanceCatalog.ServiceLog("engine_oil", "2026-06-01T00:00:00Z", now - 30 * day, 10_000.0, 4500.0)
        val state = MaintenanceCatalog.evaluate(oil, last, 12_000.0, now)
        assertEquals(MaintenanceCatalog.DueStatus.GOOD, state.status)
        assertEquals(13_000.0, state.kmRemaining!!, 0.001) // 10k + 15k - 12k
        assertEquals(335, state.daysRemaining)           // 365 - 30
        assertTrue(state.headline.contains("km"))
    }

    @Test
    fun `distance-only item goes due soon then overdue`() {
        // Serviced at 1 000 km, interval 40 000 km, now at 40 000 km => exactly 1 000 km left.
        val soon = MaintenanceCatalog.ServiceLog("front_pads", "2026-01-01T00:00:00Z", now - 100 * day, 1_000.0, null)
        assertEquals(
            MaintenanceCatalog.DueStatus.DUE_SOON,
            MaintenanceCatalog.evaluate(pads, soon, 40_000.0, now).status
        )
        val over = MaintenanceCatalog.ServiceLog("front_pads", "2025-01-01T00:00:00Z", now - 600 * day, 39_000.0, null)
        assertEquals(
            MaintenanceCatalog.DueStatus.OVERDUE,
            MaintenanceCatalog.evaluate(pads, over, 80_000.0, now).status
        )
        // distance-only items never age by calendar
        assertNull(MaintenanceCatalog.evaluate(pads, over, 10_000.0, now).daysRemaining)
        assertEquals(MaintenanceCatalog.DueStatus.GOOD, MaintenanceCatalog.evaluate(pads, over, 10_000.0, now).status)
    }

    @Test
    fun `time-based item overdue by calendar alone`() {
        val old = MaintenanceCatalog.ServiceLog("engine_oil", "2024-06-01T00:00:00Z", now - 400 * day, 5_000.0, null)
        val state = MaintenanceCatalog.evaluate(oil, old, 6_000.0, now) // only 1k km driven
        assertEquals(MaintenanceCatalog.DueStatus.OVERDUE, state.status)
    }

    @Test
    fun `catalogue covers the EA211 service plan`() {
        val ids = MaintenanceCatalog.KYLAQ_ITEMS.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("engine_oil", "oil_filter", "air_filter", "spark_plugs", "brake_fluid")))
        assertTrue(MaintenanceCatalog.KYLAQ_ITEMS.all { it.intervalKm > 0 })
    }
}
