package com.example

import com.example.discovery.PidCapabilityManager
import com.example.model.CapabilityStatus
import com.example.ui.screens.formatLiveValue
import com.example.ui.screens.isLiveError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LINEAGE GUARD (2026-09-12): the "most dashboard variables empty" bug hid behind a
 * silent "Not available". These tests lock the honest-label contract the telemetry
 * grid now renders, and the capability-flow key contract the grid's mapping relies on.
 */
class DashboardHonestLabelsTest {

    @Test
    fun `missing live value without capability info still says Not available`() {
        assertEquals("Not available", formatLiveValue(emptyMap(), "0162"))
    }

    @Test
    fun `injected NOT SUPPORTED label passes through and is not flagged as live error`() {
        val map = mapOf("0162" to "NOT SUPPORTED BY ECU")
        assertEquals("NOT SUPPORTED BY ECU", formatLiveValue(map, "0162"))
        assertFalse(isLiveError(map, "0162"))
    }

    @Test
    fun `probing and timeout labels pass through verbatim`() {
        assertEquals("probing...", formatLiveValue(mapOf("0144" to "probing..."), "0144"))
        assertEquals("no answer (timeout)", formatLiveValue(mapOf("0144" to "no answer (timeout)"), "0144"))
    }

    @Test
    fun `real values still win over labels`() {
        assertEquals("978 rpm", formatLiveValue(mapOf("010C" to "978 rpm"), "010C"))
    }

    @Test
    fun `capability flow exposes both 2-hex and 4-hex keys after marking`() {
        // TelemetryDashboardContent maps capability entries onto 4-hex tile keys; the
        // manager must keep both spellings or the mapping silently misses.
        val mgr = PidCapabilityManager()
        mgr.markPidStatus("0162", CapabilityStatus.NOT_SUPPORTED)
        val flow = mgr.capabilitiesFlow.value
        assertEquals(CapabilityStatus.NOT_SUPPORTED, flow["62"])
        assertEquals(CapabilityStatus.NOT_SUPPORTED, flow["0162"])
        assertTrue(mgr.capabilitiesFlow.value.isNotEmpty())
    }

    @Test
    fun `NOT_SUPPORTED pids are never live eligible`() {
        val mgr = PidCapabilityManager()
        mgr.markPidStatus("0152", CapabilityStatus.NOT_SUPPORTED)
        assertFalse(mgr.isLiveEligible("0152"))
        assertFalse(mgr.isPidSupported("0152"))
    }
}
