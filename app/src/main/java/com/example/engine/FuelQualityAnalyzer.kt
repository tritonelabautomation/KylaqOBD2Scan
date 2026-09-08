package com.example.engine

/**
 * Per-tank fuel behaviour and octane-quality inference — the "X95 vs normal petrol" feature.
 *
 * A premium (95 RON, e.g. X95/XP95/V-Power) and a regular (91 RON) fill behave differently in a
 * knock-controlled turbo engine, and the difference *is* visible on standard OBD signals:
 *
 *  * **Ignition timing (010E)** — with good fuel the ECU runs closer to MBT; at steady light-load
 *    cruise a healthy 1.0 TSI on 95 RON typically shows more advance than on marginal fuel.
 *  * **Knock retard proxy** — sudden timing withdrawals of several degrees while load is high
 *    are the ECU pulling spark because the knock sensors heard something. More events = poorer
 *    fuel (or hot intake charge).
 *  * **Fuel trims (0106/0107)** — persistent positive long-term trim means the ECU is adding fuel
 *    to hit lambda, another signature of weak fuel or a tired tank.
 *  * **Efficiency** — km/L per tank closes the loop.
 *
 * Tanks are segmented automatically: a fuel-level (012F) rise of [refuelLevelRisePct] or more
 * inside [refuelWindowMs] starts a new segment, so the user only has to keep driving and the
 * comparison table fills itself.
 *
 * Honest limits, stated in the UI too: this is evidence, not a lab test — temperature, traffic
 * and route mix also move these numbers, so the analyzer compares *cruise-only* timing samples
 * and reports sample counts alongside every average.
 */
class FuelQualityAnalyzer(
    private val refuelLevelRisePct: Double = 8.0,
    private val refuelWindowMs: Long = 45 * 60 * 1000L,
    private val cruiseSpeedRange: ClosedFloatingPointRange<Double> = 40.0..110.0,
    private val cruiseLoadRange: ClosedFloatingPointRange<Double> = 8.0..45.0,
    private val cruiseRpmRange: ClosedFloatingPointRange<Double> = 1400.0..3200.0,
    private val knockRetardDeg: Double = 4.0,
    private val knockWindowMs: Long = 3000L,
    private val highLoadPct: Double = 45.0,
    private val maxSegments: Int = 40
) {

    data class TankSegment(
        val index: Int,
        val startMonotonicMs: Long,
        var endMonotonicMs: Long? = null,
        var fuelUsedL: Double = 0.0,
        var distanceM: Double = 0.0,
        var cruiseTimingSamples: Int = 0,
        var avgCruiseTimingDeg: Double? = null,
        var minCruiseTimingDeg: Double? = null,
        var avgStftPct: Double? = null,
        var avgLtftPct: Double? = null,
        var knockRetardEvents: Int = 0,
        var score: Int = 0
    ) {
        val kmPerLiter: Double?
            get() = if (fuelUsedL > 0.05 && distanceM > 200) distanceM / 1000.0 / fuelUsedL else null

        /** Human label for tables: "Tank 3". */
        val label: String get() = "Tank $index"
    }

    private val segments = mutableListOf<TankSegment>()
    private var current: TankSegment? = null

    private var levelWindowMin: Double? = null
    private var levelWindowMinTs: Long = 0L
    private var lastLevel: Double? = null

    private var timingSum = 0.0
    private var timingMin: Double? = null
    private var stftSum = 0.0
    private var stftCount = 0
    private var ltftSum = 0.0
    private var ltftCount = 0

    private var recentMaxTiming: Double? = null
    private var recentMaxTimingTs: Long = 0L

    private var lastTs: Long = 0L

    fun tanks(): List<TankSegment> = segments.toList()

    fun activeTank(): TankSegment? = current

    /**
     * Feeds one decoded sample. Every parameter is nullable because any PID may be unsupported
     * on a given vehicle; the analyzer simply skips what it cannot see.
     */
    fun onSample(
        timestampMonotonicMs: Long,
        fuelLevelPct: Double?,
        timingDeg: Double?,
        stftPct: Double?,
        ltftPct: Double?,
        speedKmh: Double?,
        loadPct: Double?,
        rpm: Double?,
        fuelRateLh: Double?
    ) {
        val dtHours = if (lastTs > 0) (timestampMonotonicMs - lastTs).coerceIn(0L, 5000L) / 3_600_000.0 else 0.0
        val dtSeconds = if (lastTs > 0) (timestampMonotonicMs - lastTs).coerceIn(0L, 5000L) / 1000.0 else 0.0
        lastTs = timestampMonotonicMs

        ensureSegment(timestampMonotonicMs)
        val tank = current ?: return

        speedKmh?.let { tank.distanceM += it / 3.6 * dtSeconds }
        fuelRateLh?.let { tank.fuelUsedL += it * dtHours }

        fuelLevelPct?.let { level -> maybeStartNewTank(timestampMonotonicMs, level, tank) }

        val cruising = speedKmh != null && loadPct != null && rpm != null &&
            speedKmh in cruiseSpeedRange && loadPct in cruiseLoadRange && rpm in cruiseRpmRange
        if (cruising && timingDeg != null) {
            timingSum += timingDeg
            tank.cruiseTimingSamples++
            timingMin = if (timingMin == null || timingDeg < timingMin!!) timingDeg else timingMin
            tank.avgCruiseTimingDeg = timingSum / tank.cruiseTimingSamples
            tank.minCruiseTimingDeg = timingMin
        }

        if (loadPct != null && loadPct >= highLoadPct && timingDeg != null) {
            val recentMax = recentMaxTiming
            val recentTs = recentMaxTimingTs
            if (recentMax != null && timestampMonotonicMs - recentTs <= knockWindowMs &&
                recentMax - timingDeg >= knockRetardDeg
            ) {
                tank.knockRetardEvents++
                recentMaxTiming = timingDeg
                recentMaxTimingTs = timestampMonotonicMs
            } else if (recentMax == null || timingDeg > recentMax) {
                recentMaxTiming = timingDeg
                recentMaxTimingTs = timestampMonotonicMs
            }
        }

        stftPct?.let { stftSum += it; stftCount++; tank.avgStftPct = stftSum / stftCount }
        ltftPct?.let { ltftSum += it; ltftCount++; tank.avgLtftPct = ltftSum / ltftCount }

        tank.score = scoreOf(tank)
    }

    fun reset() {
        segments.clear()
        current = null
        levelWindowMin = null
        lastLevel = null
        timingSum = 0.0
        timingMin = null
        stftSum = 0.0
        stftCount = 0
        ltftSum = 0.0
        ltftCount = 0
        recentMaxTiming = null
        lastTs = 0L
    }

    /**
     * Plain-language comparison of the best and worst scored tanks, for the Insights screen.
     * Returns null while there is not enough data to say anything honest.
     */
    fun comparisonNote(): String? {
        val scored = segments.filter { it.cruiseTimingSamples >= 30 }
        if (scored.size < 2) return null
        val best = scored.maxByOrNull { it.score } ?: return null
        val worst = scored.minByOrNull { it.score } ?: return null
        if (best.index == worst.index) return null
        val timingDelta = (best.avgCruiseTimingDeg ?: 0.0) - (worst.avgCruiseTimingDeg ?: 0.0)
        val economyDelta = (best.kmPerLiter ?: 0.0) - (worst.kmPerLiter ?: 0.0)
        return buildString {
            append("Tank ${best.index} behaved like the better fuel: ")
            append(String.format("%+.1f° cruise ignition timing", timingDelta))
            if (best.kmPerLiter != null && worst.kmPerLiter != null) {
                append(String.format(", %+.2f km/L", economyDelta))
            }
            append(", ${best.knockRetardEvents} vs ${worst.knockRetardEvents} knock-retard events ")
            append("against Tank ${worst.index}. Higher octane lets the ECU hold spark advance; ")
            append("weak fuel shows up as pulled timing and positive long-term trim.")
        }
    }

    private fun ensureSegment(ts: Long) {
        if (current == null) {
            val nextIndex = segments.size + 1
            val tank = TankSegment(index = nextIndex, startMonotonicMs = ts)
            segments.add(tank)
            while (segments.size > maxSegments) segments.removeAt(0)
            current = tank
            resetAccumulators()
        }
    }

    private fun maybeStartNewTank(ts: Long, level: Double, tank: TankSegment) {
        val windowMin = levelWindowMin
        val windowTs = levelWindowMinTs
        if (windowMin == null || ts - windowTs > refuelWindowMs) {
            levelWindowMin = level
            levelWindowMinTs = ts
        } else if (level < windowMin) {
            levelWindowMin = level
            levelWindowMinTs = ts
        }

        val previousLevel = lastLevel
        lastLevel = level

        val rise = if (previousLevel != null) level - previousLevel else 0.0
        val windowRise = level - (levelWindowMin ?: level)
        if (rise >= refuelLevelRisePct || windowRise >= refuelLevelRisePct) {
            tank.endMonotonicMs = ts
            tank.score = scoreOf(tank)
            val nextIndex = segments.size + 1
            val fresh = TankSegment(index = nextIndex, startMonotonicMs = ts)
            segments.add(fresh)
            while (segments.size > maxSegments) segments.removeAt(0)
            current = fresh
            resetAccumulators()
            levelWindowMin = level
            levelWindowMinTs = ts
        }
    }

    private fun resetAccumulators() {
        timingSum = 0.0
        timingMin = null
        stftSum = 0.0
        stftCount = 0
        ltftSum = 0.0
        ltftCount = 0
        recentMaxTiming = null
    }

    /**
     * 0-100 evidence score: cruise timing near the healthy band for this engine, trims near
     * zero, and few knock withdrawals. Deliberately conservative — with few samples the score
     * stays at the neutral 50 instead of pretending confidence.
     */
    private fun scoreOf(tank: TankSegment): Int {
        if (tank.cruiseTimingSamples < 10) return 50
        val timing = tank.avgCruiseTimingDeg ?: return 50
        // Healthy cruise advance for a 1.0 TSI sits roughly 20-35° BTDC.
        val timingScore = when {
            timing >= 28.0 -> 100.0
            timing >= 20.0 -> 70.0 + (timing - 20.0) / 8.0 * 30.0
            else -> (timing / 20.0 * 70.0).coerceAtLeast(0.0)
        }
        val ltft = tank.avgLtftPct ?: 0.0
        val trimScore = (100.0 - (kotlin.math.abs(ltft) / 10.0 * 100.0)).coerceIn(0.0, 100.0)
        val knockPenalty = (tank.knockRetardEvents * 4.0).coerceAtMost(40.0)
        val raw = timingScore * 0.55 + trimScore * 0.45 - knockPenalty
        return raw.coerceIn(0.0, 100.0).toInt()
    }
}
