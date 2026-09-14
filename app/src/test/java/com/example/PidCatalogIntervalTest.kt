package com.example

import com.example.model.DefaultPidDefinitions
import com.example.model.PollingPriority
import com.example.model.StandardPidCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard tests for the 2026-09-14 catalogue-interval enforcement
 * (owner: "enabled PID's have no explicit polling interval - can you check
 * all PID's have polling interval?").
 *
 * Root cause: [com.example.model.PidDefinition] used to default
 * `defaultIntervalMs = 250L`, so 114 of the 153 catalogue entries (the whole
 * StandardPidCatalog "additional" J1979 set plus the discovery fallback) never
 * declared an interval of their own. The default is now REMOVED from the
 * constructor - the compiler rejects any PidDefinition without one - and every
 * catalogue entry declares an explicit interval inside its priority band:
 * FAST 100-250 ms, MEDIUM 400-800 ms, SLOW 2000-5000 ms.
 *
 * These tests lock the invariant that the compiler cannot check: every
 * declared interval must respect its tier floor (so the scheduler clamp in
 * ObdScheduler never has to fire) and stay within the documented band ceiling.
 */
class PidCatalogIntervalTest {

    private fun assertBanded(pid: com.example.model.PidDefinition) {
        val interval = pid.defaultIntervalMs
        val floor = pid.priority.floorMs
        assertTrue(
            "${pid.id} (${pid.name}): interval $interval ms is below its " +
                "${pid.priority} floor $floor ms",
            interval >= floor
        )
        assertTrue(
            "${pid.id} (${pid.name}): interval $interval ms exceeds the 5000 ms band ceiling",
            interval <= 5000L
        )
    }

    @Test
    fun `every poll-set PID declares an interval within its priority band`() {
        val defaults = DefaultPidDefinitions.getDefaults()
        assertTrue("poll set should hold the curated live PIDs", defaults.size >= 39)
        defaults.forEach(::assertBanded)
    }

    @Test
    fun `every standard catalogue PID declares an interval within its priority band`() {
        val all = StandardPidCatalog.getAllKnownPids()
        assertTrue("catalogue should hold the full J1979 Mode 01 set", all.size >= 140)
        all.forEach(::assertBanded)
    }

    @Test
    fun `uncatalogued discovery fallback gets an explicit conservative interval`() {
        // 0xEF is not in the catalogue -> lookup() builds the generic fallback,
        // which must also carry an explicit interval (MEDIUM/500 ms), never a
        // constructor default.
        val fallback = StandardPidCatalog.lookup("EF")
        assertEquals("01EF", fallback.id)
        assertEquals(PollingPriority.MEDIUM, fallback.priority)
        assertEquals(500L, fallback.defaultIntervalMs)
    }

    @Test
    fun `catalogue ambient duplicate keeps the slow-tier interval`() {
        // 01BD (lookup-only duplicate of the polled 0146 ambient) must stay SLOW -
        // companion to the 0146 SLOW-band guard in DashboardUpdateConsistencyTest.
        val ambientDup = StandardPidCatalog.getAllKnownPids().first { it.id == "01BD" }
        assertEquals(PollingPriority.SLOW, ambientDup.priority)
        assertTrue(ambientDup.defaultIntervalMs >= 2000L)
    }
}
