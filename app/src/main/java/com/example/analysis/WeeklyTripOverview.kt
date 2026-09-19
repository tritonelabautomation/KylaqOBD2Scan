package com.example.analysis

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * WEEKLY TRIP OVERVIEW analytics (owner request 2026-09-13: match the polish of
 * OBDeleven's Trip-tracker "7-day overview" - score dial, vs-last-week deltas,
 * stacked driving-breakdown bars, per-trip quality strips).
 *
 * Pure and unit-testable: consumes per-trip [TripFuelSummary.Summary] (already
 * computed from stored samples) and produces everything the overview screen draws.
 * No new telemetry, no new storage - the same recorded data, presented properly.
 */
object WeeklyTripOverview {

    /** How a minute of driving felt, from the trip's own speed histogram + idle seconds. */
    enum class DriveBand { NORMAL, SLOW, CONGESTED, STOPPED }

    /** Kylaq 1.0 TSI ARAI figure - the efficiency yardstick for the score. */
    const val ARAI_KML = 19.0

    data class TripInput(
        val tripId: String,
        val title: String,
        val startMs: Long,
        val summary: TripFuelSummary.Summary
    )

    data class TripCard(
        val tripId: String,
        val title: String,
        val startMs: Long,
        val dayLabel: String,
        val distanceKm: Double,
        val durationSec: Long,
        val kmPerL: Double?,
        /** Fraction of the trip in each band (sums to 1, or all-zero when no speed data). */
        val fractions: Map<DriveBand, Double>,
        val score: Int
    )

    data class DayBucket(val label: String, val minutes: Map<DriveBand, Double>)

    data class WeekTotals(val totalKm: Double, val dailyAvgKm: Double, val topDayKm: Double)

    data class WeekOverview(
        val rangeLabel: String,
        val totals: WeekTotals,
        val totalMinutes: Double,
        val bandPercents: Map<DriveBand, Double>,
        val days: List<DayBucket>,
        val trips: List<TripCard>,
        val score: Int?,
        val scoreLabel: String?,
        val prev: WeekTotals?
    )

    /**
     * Seconds per band: standstill = STOPPED (engine-running idle PLUS idle start-stop
     * stalls - the band means "not moving", and splitting the engine-off seconds out of
     * it would under-report city standstill); histogram bins <20 CONGESTED, 20-49 SLOW,
     * 50+ NORMAL.
     */
    fun bandSeconds(s: TripFuelSummary.Summary): Map<DriveBand, Double> {
        var congested = 0.0
        var slow = 0.0
        var normal = 0.0
        for ((bin, sec) in s.speedHistogram) {
            when {
                bin < 20 -> congested += sec
                bin < 50 -> slow += sec
                else -> normal += sec
            }
        }
        return mapOf(
            DriveBand.NORMAL to normal,
            DriveBand.SLOW to slow,
            DriveBand.CONGESTED to congested,
            DriveBand.STOPPED to (s.idleSeconds + s.engineOffSeconds)
        )
    }

    /**
     * 0-100 driving-quality score, documented weights (all inputs come from the trip's
     * own recorded summary - nothing invented):
     *  25 % calm     = 1 - idle/duration
     *  25 % flow     = moving-average speed vs 45 km/h free flow
     *  25 % economy  = km/L vs ARAI 19.0 (neutral 0.6 when fuel rate unavailable)
     *  10 % coasting = coast seconds vs 10 % of drive time (decel fuel-cut skill)
     *  15 % discipline= moving average near the 65 km/h efficiency anchor
     */
    fun scoreOf(s: TripFuelSummary.Summary): Int {
        val dur = s.durationSeconds.toDouble().coerceAtLeast(1.0)
        val calm = (1.0 - (s.idleSeconds + s.engineOffSeconds) / dur).coerceIn(0.0, 1.0)
        val flow = if (s.hasSpeedSeries) (s.movingAverageSpeedKmh / 45.0).coerceIn(0.0, 1.0) else 0.5
        val eff = s.kmPerLiter?.let { (it / ARAI_KML).coerceIn(0.0, 1.0) } ?: 0.6
        val coast = (s.coastSeconds / maxOf(30.0, dur * 0.10)).coerceIn(0.0, 1.0)
        val discipline = if (s.hasSpeedSeries) {
            (1.0 - kotlin.math.abs(s.movingAverageSpeedKmh - 65.0) / 65.0).coerceIn(0.0, 1.0)
        } else 0.5
        val raw = 0.25 * calm + 0.25 * flow + 0.25 * eff + 0.10 * coast + 0.15 * discipline
        return (raw * 100).toInt().coerceIn(0, 100)
    }

    fun scoreLabel(score: Int): String = when {
        score >= 75 -> "Good"
        score >= 50 -> "Fair"
        else -> "Needs work"
    }

    /** Monday-start week boundary for the given offset (0 = this week). */
    fun weekStartMs(nowMs: Long, weekOffset: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun..7=Sat
        cal.add(Calendar.DAY_OF_YEAR, -((dow + 5) % 7) + weekOffset * 7)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun dayLabel(ms: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        return DAY_LABELS[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7]
    }

    fun tripTitle(startMs: Long, fallback: String): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startMs
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "Morning drive"
            in 11..16 -> "Midday drive"
            in 17..21 -> "Evening drive"
            else -> "Night drive"
        }.let { if (fallback.isNotBlank() && fallback != it) fallback else it }
    }

    fun overview(trips: List<TripInput>, nowMs: Long, weekOffset: Int = 0): WeekOverview {
        val start = weekStartMs(nowMs, weekOffset)
        val end = start + 7L * 86_400_000L
        val prevStart = start - 7L * 86_400_000L

        fun totalsFor(window: Pair<Long, Long>): WeekTotals? {
            val inWindow = trips.filter { it.startMs in window.first until window.second }
            if (inWindow.isEmpty()) return null
            val perDay = DoubleArray(7)
            for (t in inWindow) perDay[((t.startMs - window.first) / 86_400_000L).toInt().coerceIn(0, 6)] += t.summary.distanceKm
            val total = inWindow.sumOf { it.summary.distanceKm }
            return WeekTotals(total, total / 7.0, perDay.max())
        }

        val week = trips.filter { it.startMs in start until end }.sortedByDescending { it.startMs }

        val dayMinutes = Array(7) { mutableMapOf<DriveBand, Double>().withDefault { 0.0 } }
        val bandSecondsTotal = DriveBand.values().associateWith { 0.0 }.toMutableMap()
        val cards = week.map { t ->
            val secs = bandSeconds(t.summary)
            val dayIdx = ((t.startMs - start) / 86_400_000L).toInt().coerceIn(0, 6)
            secs.forEach { (b, s) ->
                dayMinutes[dayIdx][b] = (dayMinutes[dayIdx][b] ?: 0.0) + s / 60.0
                bandSecondsTotal[b] = (bandSecondsTotal[b] ?: 0.0) + s
            }
            val tot = secs.values.sum()
            TripCard(
                tripId = t.tripId,
                title = tripTitle(t.startMs, t.title),
                startMs = t.startMs,
                dayLabel = dayLabel(t.startMs),
                distanceKm = t.summary.distanceKm,
                durationSec = t.summary.durationSeconds,
                kmPerL = t.summary.kmPerLiter,
                fractions = if (tot > 0) secs.mapValues { it.value / tot } else secs.mapValues { 0.0 },
                score = scoreOf(t.summary)
            )
        }

        val grand = bandSecondsTotal.values.sum()
        val percents = bandSecondsTotal.mapValues { if (grand > 0) it.value / grand * 100.0 else 0.0 }
        val days = (0..6).map { i -> DayBucket(DAY_LABELS[i], bandSecondsTotal.keys.associateWith { b -> dayMinutes[i][b] ?: 0.0 }) }
        val totals = totalsFor(start to end) ?: WeekTotals(0.0, 0.0, 0.0)
        val totalMinutes = days.sumOf { d -> d.minutes.values.sum() }
        val avgScore = cards.map { it.score }.average().takeIf { cards.isNotEmpty() }

        val fmt = com.example.data.RecordTime.formatter("d MMM")
        val rangeLabel = "${fmt.format(java.util.Date(start))} – ${fmt.format(java.util.Date(end - 86_400_000L))}"

        return WeekOverview(
            rangeLabel = rangeLabel,
            totals = totals,
            totalMinutes = totalMinutes,
            bandPercents = percents,
            days = days,
            trips = cards,
            score = avgScore?.toInt(),
            scoreLabel = avgScore?.toInt()?.let { scoreLabel(it) },
            prev = totalsFor(prevStart to start)
        )
    }
}
