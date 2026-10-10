package com.example

import com.example.analysis.DriveInsightsStore
import com.example.engine.CoastNeutralDetector
import com.example.engine.FuelQualityAnalyzer
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM tests for the durable drive-insight codec: coast sessions and tank segments must
 * survive an encode/decode round trip because the manual's coasting requirement says behaviour
 * and mileage are SAVED, and X95-vs-regular evidence must span restarts.
 */
class DriveInsightsStoreTest {

    @Test
    fun `coast summary round trips`() {
        val coast = CoastNeutralDetector.CoastSummary(
            eventCount = 7,
            neutralEvents = 2,
            fuelCutEvents = 5,
            totalDistanceM = 4310.0,
            totalSeconds = 245.0,
            totalFuelUsedL = 0.42,
            totalSavedVsIdleL = 0.31,
            totalSavedVsCruiseL = 0.18
        )
        val decoded = DriveInsightsStore.decodeCoast(
            DriveInsightsStore.encodeCoast("2026-09-08T04:05:06Z", coast)
        )
        assertNotNull(decoded)
        assertEquals("2026-09-08T04:05:06Z", decoded!!.savedAtUtc)
        assertEquals(7, decoded.eventCount)
        assertEquals(2, decoded.neutralEvents)
        assertEquals(5, decoded.fuelCutEvents)
        assertEquals(4.31, decoded.distanceKm, 0.001)
        assertEquals(245.0, decoded.coastSeconds, 0.001)
        assertEquals(0.42, decoded.fuelUsedL, 0.001)
        assertEquals(0.31, decoded.savedVsIdleL, 0.001)
        assertEquals(0.18, decoded.savedVsCruiseL, 0.001)
        assertTrue(decoded.display.contains("4.3 km"))
    }

    @Test
    fun `tank segment round trips including nullable evidence`() {
        val tank = FuelQualityAnalyzer.TankSegment(
            index = 3,
            startMonotonicMs = 123_456_789L,
            endMonotonicMs = 987_654_321L,
            fuelUsedL = 28.4,
            distanceM = 486_000.0,
            cruiseTimingSamples = 120,
            avgCruiseTimingDeg = 14.2,
            minCruiseTimingDeg = 9.8,
            avgStftPct = null,
            avgLtftPct = -1.5,
            knockRetardEvents = 2,
            score = 74
        )
        val decoded = DriveInsightsStore.decodeTank(
            DriveInsightsStore.encodeTank("2026-09-08T04:05:06Z", tank)
        )
        assertNotNull(decoded)
        assertEquals(123_456_789L, decoded!!.startMonotonicMs)
        assertEquals(28.4, decoded.fuelUsedL, 0.001)
        assertEquals(486.0, decoded.distanceKm, 0.001)
        assertEquals(14.2, decoded.avgCruiseTimingDeg!!, 0.001)
        assertEquals(9.8, decoded.minCruiseTimingDeg!!, 0.001)
        assertNull(decoded.avgStftPct)
        assertEquals(-1.5, decoded.avgLtftPct!!, 0.001)
        assertEquals(2, decoded.knockRetardEvents)
        assertEquals(74, decoded.score)
        assertEquals(486.0 / 28.4, decoded.kmPerLiter!!, 0.01)
        assertEquals("123456789", decoded.dedupKey)
    }

    @Test
    fun `malformed and foreign lines decode to null`() {
        assertNull(DriveInsightsStore.decodeCoast(""))
        assertNull(DriveInsightsStore.decodeCoast("t1|garbage"))
        assertNull(DriveInsightsStore.decodeTank("c1|2026|1|2|3|4|5|6|7|8"))
        assertNull(DriveInsightsStore.decodeCoast("c99|x|1|1|1|1|1|1|1|1"))
        assertNull(DriveInsightsStore.decodeTank("t1|d|notanumber|1|1|1|1|1|1|1|1"))
    }
}
