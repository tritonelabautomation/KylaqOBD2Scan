package com.example

import com.example.analysis.TripTrendAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner question 2026-09-16: "Why altitude is missing in trend and trip logs?"
 * Two real gaps: the per-pid sample rows the UI reads never persisted the GPS
 * altitude stamp (only the in-memory wide samples / CSV did), and the Trends tab
 * never offered an altitude channel. The extractor below backs the new channel;
 * these tests pin its honesty rules: fix-less rows are skipped (gaps stay gaps,
 * never interpolated, never 0.0), order is by timestamp, no fixes = empty series
 * so the chart stays hidden instead of drawing a fake flat line.
 */
class AltitudeTrendChannelTest {

    private data class Row(val ts: Long, val alt: Double?)

    @Test
    fun altitudeSeriesKeepsTimestampsAndSkipsFixlessRows() {
        val rows = listOf(Row(1000, 540.0), Row(2000, null), Row(3000, 552.5), Row(4000, null))
        val pts = TripTrendAnalyzer.altitudePoints(rows, { it.ts }, { it.alt })
        assertEquals(listOf(1000L to 540.0, 3000L to 552.5), pts)
    }

    @Test
    fun altitudeSeriesSortsOutOfOrderRows() {
        val rows = listOf(Row(3000, 552.0), Row(1000, 540.0))
        assertEquals(
            listOf(1000L to 540.0, 3000L to 552.0),
            TripTrendAnalyzer.altitudePoints(rows, { it.ts }, { it.alt })
        )
    }

    @Test
    fun noFixesMeansEmptySeriesNotZeroes() {
        val rows = listOf(Row(1000, null), Row(2000, null))
        assertEquals(emptyList<Pair<Long, Double>>(), TripTrendAnalyzer.altitudePoints(rows, { it.ts }, { it.alt }))
    }
}
