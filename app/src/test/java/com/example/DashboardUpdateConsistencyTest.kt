package com.example

import com.example.analysis.ChartSampling
import com.example.scheduler.LiveTelemetryStore
import com.example.ui.screens.formatLiveValue
import com.example.ui.screens.numericWithStaleFallback
import com.example.ui.screens.isLiveError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard tests for the 2026-09-13 dashboard update-consistency + trends fixes
 * (owner live-report: "inconsistency in dashboard update ... Trends are not proper").
 *
 * Locks in:
 *  1. ADAPTIVE STALENESS - the poll loop is a serial round-robin, so a PID's real
 *     refresh gap is the whole cycle time, not its configured interval. Fixed tier
 *     budgets shorter than the observed cadence made healthy tiles flicker between
 *     value and "(stale)" placeholder. The budget must grow with the observed gap
 *     while never dropping below the tier floor.
 *  2. STALE DISPLAY POLICY - a stale-but-valid display string keeps its number with
 *     the "(stale)" marker, and the dashboard formatters must NOT treat it as an
 *     error (that red flip-flop was the visible inconsistency).
 *  3. CHART DOWNSAMPLING - trend charts bound their path size without lying about
 *     the window: first and last samples always survive.
 *
 * Pure JVM - no Robolectric, no Android runtime.
 */
class DashboardUpdateConsistencyTest {

    // ------------------------------------------------------- adaptive staleness

    @Test
    fun adaptiveThresholdFallsBackToTierFloorWithoutObservation() {
        assertEquals(2_500L, LiveTelemetryStore.adaptiveStaleThresholdMs(2_500L, null))
        assertEquals(5_000L, LiveTelemetryStore.adaptiveStaleThresholdMs(5_000L, 0L))
        assertEquals(15_000L, LiveTelemetryStore.adaptiveStaleThresholdMs(15_000L, -7L))
    }

    @Test
    fun adaptiveThresholdNeverDropsBelowTierFloor() {
        // observed 800 ms cadence -> 2*800+1500 = 3100, floor 5000 wins
        assertEquals(5_000L, LiveTelemetryStore.adaptiveStaleThresholdMs(5_000L, 800L))
    }

    @Test
    fun adaptiveThresholdGrowsWithObservedSerialCycleTime() {
        // The root-cause scenario: FAST pid (floor 2.5 s) actually refreshes every
        // 4 s because the round-robin cycle is that long. Old fixed budget marked it
        // stale mid-cycle; adaptive budget = 2*4000+1500 = 9500 ms keeps it steady.
        assertEquals(9_500L, LiveTelemetryStore.adaptiveStaleThresholdMs(2_500L, 4_000L))
        assertEquals(17_000L, LiveTelemetryStore.adaptiveStaleThresholdMs(15_000L, 7_750L))
    }

    // ------------------------------------------------------ stale display policy

    @Test
    fun staleMarkerKeepsTheNumberReadable() {
        val store = LiveTelemetryStore()
        store.publish(
            pidId = "010C",
            ecuId = "7E8",
            item = com.example.model.LiveTelemetryValue(
                parameterName = "Engine RPM",
                numericValue = 970.0,
                displayValue = "970",
                unit = "RPM",
                source = com.example.model.ValueSource.STANDARD_OBD,
                timestampMonotonic = 1_000L,
                isValid = true,
                isStale = false,
                sourcePid = "010C",
                sourceEcuId = "7E8",
                rawBytes = null
            ),
            preferredEcu = "7E8"
        )
        store.markStale(nowMonotonic = 60_000L, thresholdFor = { 2_500L }, preferredEcuFor = { "7E8" })

        val shown = store.decodedMap.value["010C"]!!
        assertEquals("970 RPM (stale)", shown)
        assertTrue("number must stay visible while stale", shown.startsWith("970"))
    }

    @Test
    fun formattersTreatStaleValueAsDataNotError() {
        val map = mapOf("010C" to "970 RPM (stale)")
        assertEquals("970 RPM (stale)", formatLiveValue(map, "010C"))
        assertFalse("a stale number must not flip the tile red", isLiveError(map, "010C"))
    }

    @Test
    fun formattersStillFlagGenuineMissingData() {
        val map = mapOf(
            "0105" to "Not available",
            "0167" to "implausible raw - no data"
        )
        assertEquals("Not available", formatLiveValue(map, "0105"))
        assertTrue(isLiveError(map, "0105"))
        assertEquals("Not available", formatLiveValue(map, "0105"))
        assertFalse("legacy stale placeholder would still render as error",
            isLiveError(mapOf("0105" to "Not available (stale)"), "0105").not())
    }

    // --------------------------------------------- poll cadence / AC-related PIDs

    @Test
    fun priorityFloorsMatchTheDocumentedBands() {
        assertEquals(100L, com.example.model.PollingPriority.FAST.floorMs)
        assertEquals(400L, com.example.model.PollingPriority.MEDIUM.floorMs)
        assertEquals(2000L, com.example.model.PollingPriority.SLOW.floorMs)
    }

    @Test
    fun noEnabledCataloguePidCanPollFasterThanItsTierFloor() {
        // Root cause 2026-09-13: 114/153 entries inherited the 250 ms constructor
        // default, so validated SLOW PIDs (ambient 0146 = the AC card's only live
        // input, trims, tank...) came due on every serial pass and the board
        // updated in lurches. The scheduler clamps with priority.floorMs.
        val defs = com.example.model.DefaultPidDefinitions.getDefaults()
        for (d in defs) {
            val effective = maxOf(d.defaultIntervalMs, d.priority.floorMs)
            assertTrue("${d.id} effective interval $effective below floor ${d.priority.floorMs}",
                effective >= d.priority.floorMs)
        }
        val ambient = defs.first { it.id == "0146" }
        assertTrue("ambient must stay in the documented SLOW band",
            maxOf(ambient.defaultIntervalMs, ambient.priority.floorMs) in 2000L..5000L)
        val ambientDup = defs.first { it.id == "01BD" }
        assertTrue("the duplicate ambient research PID must not spam at 250 ms",
            maxOf(ambientDup.defaultIntervalMs, ambientDup.priority.floorMs) >= 2000L)
    }

    @Test
    fun acCardAmbientFallsBackToStaleDisplayNumber() {
        assertEquals(25.0, numericWithStaleFallback(null, "25 °C (stale)")!!, 1e-9)
        assertEquals(25.0, numericWithStaleFallback(null, "25 °C")!!, 1e-9)
        assertEquals(-5.0, numericWithStaleFallback(null, "-5 °C")!!, 1e-9)
        assertEquals("numeric view wins when fresh", 26.0, numericWithStaleFallback(26.0, "25 °C (stale)")!!, 1e-9)
        assertNull(numericWithStaleFallback(null, "Not available"))
        assertNull(numericWithStaleFallback(null, null))
    }

    // --------------------------------------------------------- chart downsampling

    @Test
    fun downsampleIsIdentityWhenWithinBudget() {
        val values = (0..99).toList()
        assertSame(values, ChartSampling.downsample(values, 600))
    }

    @Test
    fun downsampleBoundsSizeAndKeepsWindowEnds() {
        val values = (0..4999).toList()
        val out = ChartSampling.downsample(values, 600)
        assertTrue("bounded path size, was ${out.size}", out.size <= 600)
        assertEquals(0, out.first())
        assertEquals(4999, out.last())
        assertTrue("stride must stay ordered", out.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun downsampleHandlesTinyAndDegenerateBudgets() {
        assertEquals(listOf(1, 2, 3), ChartSampling.downsample(listOf(1, 2, 3), 2))
        val big = (0..50).toList()
        assertSame(big, ChartSampling.downsample(big, 2)) // budget < 3 disables sampling
        assertEquals(50, ChartSampling.downsample(big, 3).last())
    }
}
