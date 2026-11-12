package com.example

import com.example.analysis.yDomain
import com.example.data.RecordingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner bug batch 2026-09-16 16:54 ("bugs bugs only bugs"):
 *  - parked-session trends drew a -0.5…+0.5 km/h axis under an all-zero speed line;
 *  - a recording ran 99 minutes against a silent link and died as an unsaved corpse.
 */
class SilentLinkAndAxisFloorTest {

    @Test
    fun allZeroSeriesGetsZeroFlooredAxisNotNegative() {
        val (lo, span) = yDomain(0.0, 0.0)
        assertEquals(0.0, lo, 1e-9)
        assertTrue("span must stay positive", span > 0.0)
    }

    @Test
    fun nonNegativeSeriesNeverShowsNegativeAxis() {
        val (lo, _) = yDomain(0.0, 26.2)
        assertTrue(lo >= 0.0)
    }

    @Test
    fun realVariationKeepsItsZoom() {
        val (lo, _) = yDomain(12.1, 14.7)
        assertTrue("voltage wiggle must not be floored to zero", lo > 10.0)
    }

    @Test
    fun realNegativesArePreserved() {
        val (lo, _) = yDomain(-5.0, 3.0)
        assertTrue(lo < -5.0 + 1e-9)
    }

    @Test
    fun watchdogFiresOnlyAfterTheSilentLimit() {
        assertFalse(RecordingManager.shouldAutoStop(lastRxMs = 1_000L, nowMs = 1_000L + 4 * 60_000L))
        assertTrue(RecordingManager.shouldAutoStop(lastRxMs = 1_000L, nowMs = 1_000L + 6 * 60_000L))
    }

    @Test
    fun watchdogNeverFiresBeforeFirstResponse() {
        assertFalse(RecordingManager.shouldAutoStop(lastRxMs = 0L, nowMs = 99 * 60_000L))
    }
}
