package com.example.engine

/**
 * Turbo behaviour tracker for the 1.0 TSI's wastegated turbocharger.
 *
 * The ECU does not publish a boost PID the Kylaq answers reliably, so boost is derived the
 * physically correct way: manifold absolute pressure (010B) minus barometric pressure (0133,
 * or a sea-level fallback). Negative = vacuum (part load, throttle closed), positive = the
 * turbo is compressing the charge.
 *
 * Tracked, all from standard J1979 signals:
 *  * live / peak boost and deepest vacuum;
 *  * a boost-vs-rpm histogram (the "turbo curve" the user asked for);
 *  * tip-in lag: time from a pedal stab (pedal <15 % to >60 %) until boost first exceeds
 *    [spoolThresholdKpa] — the measurable form of the well-known sub-2000 rpm lag;
 *  * over-boost occurrences above [overBoostKpa] (wastegate health hint);
 *  * charge-air temperature and commanded wastegate duty when the car answers 0166/016E.
 */
class TurboAnalyzer(
    private val fallbackBaroKpa: Double = 101.0,
    private val spoolThresholdKpa: Double = 40.0,
    private val overBoostKpa: Double = 160.0,
    private val tipInFromPct: Double = 15.0,
    private val tipInToPct: Double = 60.0,
    private val spoolTimeoutMs: Long = 5000L,
    private val rpmBinSize: Int = 500
) {

    data class TipInEvent(
        val startMonotonicMs: Long,
        val startRpm: Double,
        val lagMs: Long?,
        val peakBoostKpa: Double
    )

    data class TurboSnapshot(
        val boostKpa: Double?,
        val boostPsi: Double?,
        val isBoosting: Boolean,
        val vacuumKpa: Double?,
        val peakBoostKpa: Double,
        val overBoostEvents: Int,
        val tipInCount: Int,
        val lastLagMs: Long?,
        val averageLagMs: Long?,
        val chargeTempC: Double?,
        val wastegatePct: Double?,
        val boostVsRpm: List<Pair<Int, Double>>
    )

    private var currentBoost: Double? = null
    private var peakBoost: Double = 0.0
    private var deepestVacuum: Double? = null
    private var overBoostCount: Int = 0
    private var chargeTemp: Double? = null
    private var wastegate: Double? = null

    private val boostByRpmBin = LinkedHashMap<Int, BinAccumulator>()
    private val tipIns = ArrayDeque<TipInEvent>()

    private var tipInStartMs: Long? = null
    private var tipInStartRpm: Double? = null
    private var tipInPeakBoost: Double = 0.0
    private var lastPedal: Double? = null

    fun onSample(
        timestampMonotonicMs: Long,
        rpm: Double?,
        mapKpa: Double?,
        baroKpa: Double?,
        pedalOrThrottlePct: Double?,
        chargeTempC: Double? = null,
        wastegatePct: Double? = null
    ): TurboSnapshot {
        chargeTempC?.let { chargeTemp = it }
        wastegatePct?.let { wastegate = it }

        val boost = mapKpa?.let { map -> map - (baroKpa ?: fallbackBaroKpa) }
        currentBoost = boost

        boost?.let { b ->
            if (b > peakBoost) peakBoost = b
            if (b < 0 && (deepestVacuum == null || b < deepestVacuum!!)) deepestVacuum = b
            if (b > overBoostKpa) overBoostCount++
            if (rpm != null && rpm > 500) {
                val bin = (rpm.toInt() / rpmBinSize) * rpmBinSize
                boostByRpmBin.getOrPut(bin) { BinAccumulator() }.add(b)
            }
        }

        trackTipIn(timestampMonotonicMs, rpm, pedalOrThrottlePct, boost)

        return snapshot()
    }

    fun snapshot(): TurboSnapshot {
        val lags = tipIns.mapNotNull { it.lagMs }
        return TurboSnapshot(
            boostKpa = currentBoost,
            boostPsi = currentBoost?.let { it * 0.145038 },
            isBoosting = (currentBoost ?: 0.0) > 0.0,
            vacuumKpa = deepestVacuum,
            peakBoostKpa = peakBoost,
            overBoostEvents = overBoostCount,
            tipInCount = tipIns.size,
            lastLagMs = tipIns.lastOrNull()?.lagMs,
            averageLagMs = if (lags.isEmpty()) null else lags.average(),
            chargeTempC = chargeTemp,
            wastegatePct = wastegate,
            boostVsRpm = boostByRpmBin.entries
                .filter { it.value.count >= 3 }
                .map { it.key to it.value.average() }
                .sortedBy { it.first }
        )
    }

    fun reset() {
        currentBoost = null
        peakBoost = 0.0
        deepestVacuum = null
        overBoostCount = 0
        chargeTemp = null
        wastegate = null
        boostByRpmBin.clear()
        tipIns.clear()
        tipInStartMs = null
        tipInStartRpm = null
        tipInPeakBoost = 0.0
        lastPedal = null
    }

    private fun trackTipIn(ts: Long, rpm: Double?, pedal: Double?, boost: Double?) {
        val previous = lastPedal
        lastPedal = pedal

        if (previous != null && pedal != null) {
            val started = previous < tipInFromPct && pedal >= tipInFromPct
            if (started && tipInStartMs == null) {
                tipInStartMs = ts
                tipInStartRpm = rpm
                tipInPeakBoost = 0.0
            }
        }

        val start = tipInStartMs
        if (start != null) {
            tipInPeakBoost = maxOf(tipInPeakBoost, boost ?: 0.0)
            val elapsed = ts - start
            val spooled = (boost ?: 0.0) >= spoolThresholdKpa
            if (spooled || elapsed >= spoolTimeoutMs) {
                tipIns.addLast(
                    TipInEvent(
                        startMonotonicMs = start,
                        startRpm = tipInStartRpm ?: 0.0,
                        lagMs = if (spooled) elapsed else null,
                        peakBoostKpa = tipInPeakBoost
                    )
                )
                while (tipIns.size > 100) tipIns.removeFirst()
                tipInStartMs = null
                tipInStartRpm = null
                tipInPeakBoost = 0.0
            }
        }
    }

    private class BinAccumulator {
        var count: Int = 0
            private set
        private var sum: Double = 0.0
        fun add(value: Double) {
            count++
            sum += value
        }
        fun average(): Double = if (count == 0) 0.0 else sum / count
    }
}
