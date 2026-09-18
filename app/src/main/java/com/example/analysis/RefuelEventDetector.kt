package com.example.analysis

/**
 * Event-driven refuel detection off the tank level PID (owner 2026-09-19: "create an event driven
 * mechanism to track since refueling ... you will see change in tank fuel level PID 012F %").
 *
 * The cluster has had a SINCE REFUEL tab all along; the app can now keep the same bookkeeping
 * itself, and - unlike the cluster - can close the loop against pump litres at the next logged
 * fill. Detection is deliberately event-driven, not polling: a refuel is a LEVEL RISE ACROSS A
 * STATIONARY WINDOW (or across a session gap), which is the one signature a sloshing tank cannot
 * fake. While moving, level rows only update the running stamp; rises of >= [DEFAULT_MIN_RISE_PCT]
 * points (~2 L in a 50 L tank) are only accepted between two stopped states, so driving wiggle -
 * measured at +/-1-2 points on the owner's 09-17 drive - can never emit an event.
 *
 * Pure Kotlin on purpose: the whole state machine is unit-testable without a device, and the
 * recovery path replays the same rows through it, so a recovered session detects its refuels too.
 */
class RefuelEventDetector(private val minRisePct: Double = DEFAULT_MIN_RISE_PCT) {

    data class Sample(val tsMs: Long, val pid: String, val value: Double?)

    data class Detected(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val levelBeforePct: Double,
        val levelAfterPct: Double,
        val odoKm: Double?,
        val betweenSessions: Boolean
    ) {
        val risePct: Double get() = levelAfterPct - levelBeforePct
    }

    private var lastLevelPct: Double? = null
    private var lastOdoKm: Double? = null
    private var stationary = false
    private var windowStartMs: Long? = null
    private var windowEndMs: Long? = null
    private var windowBasePct: Double? = null
    private var windowMaxPct: Double? = null

    /** Feeds one decoded row. Returns an event exactly once, when a stationary window closes. */
    fun onSample(s: Sample): Detected? {
        val v = s.value ?: return null
        return when (s.pid) {
            PID_SPEED -> {
                val nowStationary = v < STOP_KMH
                val closed = if (stationary && !nowStationary) closeWindow(s.tsMs) else null
                if (nowStationary && !stationary) openWindow(s.tsMs)
                stationary = nowStationary
                closed
            }
            PID_LEVEL -> {
                lastLevelPct = v
                if (stationary) {
                    windowEndMs = s.tsMs
                    if (windowBasePct == null) windowBasePct = v
                    windowMaxPct = maxOf(windowMaxPct ?: v, v)
                }
                null
            }
            PID_ODO -> {
                lastOdoKm = v
                null
            }
            else -> null
        }
    }

    /**
     * Engine-off at the pump ends the session while the window is still open (auto-record stops
     * with the engine), so the rise is only visible at session end or in the next session. Flush
     * what the window already proves; the cross-session jump catches the rest.
     */
    fun onSessionEnd(tsMs: Long): Detected? =
        if (stationary) closeWindow(tsMs).also { stationary = false } else null

    private fun openWindow(tsMs: Long) {
        windowStartMs = tsMs
        windowEndMs = tsMs
        windowBasePct = lastLevelPct
        windowMaxPct = lastLevelPct
    }

    private fun closeWindow(tsMs: Long): Detected? {
        val start = windowStartMs
        val end = windowEndMs
        val base = windowBasePct
        val max = windowMaxPct
        windowStartMs = null
        windowEndMs = null
        windowBasePct = null
        windowMaxPct = null
        if (start == null || base == null || max == null) return null
        if (max - base < minRisePct) return null
        // end BEFORE the reset, or every event reports the closing sample's stamp instead of the
        // last level row that proved the rise - the test that caught this expected 120, got 500.
        return Detected(start, end ?: tsMs, base, max, lastOdoKm, false)
    }

    companion object {
        const val PID_SPEED = "010D"
        const val PID_LEVEL = "012F"
        const val PID_ODO = "01A6"

        /** ~2 L in the Kylaq's 50 L tank; measured driving slosh on this car stays under 2 pts. */
        const val DEFAULT_MIN_RISE_PCT = 4.0
        const val STOP_KMH = 1.0

        /**
         * The owner's 2026-09-17 refuel happened BETWEEN sessions: engine off at the pump ends
         * auto-recording, the fill raises the level, and the next session opens 93.7 % where the
         * last one left off at 37.6 %. The persisted stamp of the previous session's last level
         * row is the other half of this event.
         */
        fun crossSession(
            prevTsMs: Long,
            prevLevelPct: Double,
            prevOdoKm: Double?,
            firstTsMs: Long,
            firstLevelPct: Double,
            firstOdoKm: Double?,
            minRisePct: Double = DEFAULT_MIN_RISE_PCT
        ): Detected? {
            if (firstTsMs <= prevTsMs) return null
            if (firstLevelPct - prevLevelPct < minRisePct) return null
            return Detected(
                prevTsMs, firstTsMs, prevLevelPct, firstLevelPct,
                firstOdoKm ?: prevOdoKm, true
            )
        }
    }
}

/**
 * Consumption since a detected refuel event, from the app's own rows: distance from the odometer
 * (monotonic max - raw 01A6 frames regress on CAN/ELM transients, ledger 8.5, and an odometer
 * cannot unwind), fuel from the rate PID integrated over its own timeline, exactly as
 * [TripFuelSummary] does it so the two never disagree about a litre.
 */
object SinceRefuelStats {

    data class Row(val tsMs: Long, val pid: String, val value: Double?)

    data class Stats(
        val distanceKm: Double,
        val fuelLiters: Double,
        val durationSec: Long,
        val avgSpeedKmh: Double?
    ) {
        val kmL: Double? get() = if (fuelLiters > 0.05 && distanceKm > 0.05) distanceKm / fuelLiters else null
    }

    private const val FUEL_DENSITY_KG_L = 0.745

    fun summarize(rows: List<Row>): Stats {
        val sorted = rows.sortedBy { it.tsMs }
        if (sorted.isEmpty()) return Stats(0.0, 0.0, 0L, null)

        var firstOdo: Double? = null
        var maxOdo: Double? = null
        for (r in sorted) {
            if (r.pid != RefuelEventDetector.PID_ODO) continue
            val v = r.value ?: continue
            if (firstOdo == null) firstOdo = v
            maxOdo = maxOf(maxOdo ?: v, v)
        }
        val distanceKm = if (firstOdo != null && maxOdo != null) maxOf(0.0, maxOdo - firstOdo) else 0.0

        var fuelLiters = 0.0
        var prevRateTs: Long? = null
        var rateLh: Double? = null
        for (r in sorted) {
            when (r.pid) {
                "019D" -> {
                    val gps = r.value ?: continue
                    commitRate(prevRateTs, r.tsMs, rateLh)?.let { fuelLiters += it }
                    rateLh = gps * 3.6 / FUEL_DENSITY_KG_L
                    prevRateTs = r.tsMs
                }
                "015E" -> {
                    val lh = r.value ?: continue
                    commitRate(prevRateTs, r.tsMs, rateLh)?.let { fuelLiters += it }
                    rateLh = lh
                    prevRateTs = r.tsMs
                }
                else -> Unit
            }
        }

        val durationSec = if (sorted.size > 1) (sorted.last().tsMs - sorted.first().tsMs) / 1000L else 0L
        val avg = if (durationSec > 0) distanceKm / (durationSec / 3600.0) else null
        return Stats(distanceKm, fuelLiters, durationSec, avg)
    }

    private fun commitRate(prevTs: Long?, ts: Long, rate: Double?): Double? {
        if (prevTs == null || rate == null || ts <= prevTs) return null
        return rate * (ts - prevTs) / 3_600_000.0
    }
}
