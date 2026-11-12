package com.example.analysis

/**
 * Engine-load impact of the AC (owner pipeline task 4, 2026-09-16: "Engine load based on
 * AC on off"). Uses the MEASURED segments from [AcVoltageDetector] - every engine-running
 * observation is attributed to its AC-on or AC-off regime and the means are compared:
 * how many load points, rpm and L/h does the compressor clutch actually cost on THIS
 * trip of THIS car. Pure and honest: a regime with too few samples yields nulls and
 * `isMeaningful = false` instead of a fabricated delta (no-fake-values rule).
 *
 * Callers must hand in ENGINE-RUNNING observations only - a start-stop stall inside an
 * AC-on segment would otherwise average in load=0 samples and fake a "lighter engine".
 */
object AcLoadAnalyzer {

    /** One regime (AC on or AC off): measured seconds plus the means it collected. */
    data class RegimeStats(
        val seconds: Double = 0.0,
        val loadSamples: Int = 0,
        val meanLoadPct: Double? = null,
        val meanRpm: Double? = null,
        val meanFuelLh: Double? = null
    )

    data class Comparison(
        val acOn: RegimeStats = RegimeStats(),
        val acOff: RegimeStats = RegimeStats(),
        val loadDeltaPct: Double? = null,
        val rpmDelta: Double? = null,
        val fuelDeltaLh: Double? = null
    ) {
        /** Both regimes measured with enough load samples to justify showing a delta. */
        val isMeaningful: Boolean
            get() = loadDeltaPct != null &&
                acOn.loadSamples >= MIN_SAMPLES &&
                acOff.loadSamples >= MIN_SAMPLES
    }

    /** Fewer than 5 load samples per regime is a guess, not a comparison. */
    const val MIN_SAMPLES = 5

    fun compare(
        segments: List<AcVoltageDetector.Segment>,
        loadSeries: List<Pair<Long, Double>>,
        rpmSeries: List<Pair<Long, Double>>,
        fuelSeries: List<Pair<Long, Double>>
    ): Comparison {
        if (segments.isEmpty()) return Comparison()

        // Attribution: [startTs, endTs) per segment; the final segment also owns its
        // endTs so the last observation of the trip is not dropped.
        fun regimeOf(ts: Long): Boolean? {
            val seg = segments.firstOrNull { ts >= it.startTs && ts < it.endTs }
                ?: segments.last().takeIf { ts == it.endTs }
                ?: return null
            return seg.acOn
        }

        fun stats(on: Boolean): RegimeStats {
            val seconds = segments.filter { it.acOn == on }
                .sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) } / 1000.0
            val loads = loadSeries.filter { regimeOf(it.first) == on }.map { it.second }
            val rpms = rpmSeries.filter { regimeOf(it.first) == on }.map { it.second }
            val fuels = fuelSeries.filter { regimeOf(it.first) == on }.map { it.second }
            return RegimeStats(
                seconds = seconds,
                loadSamples = loads.size,
                meanLoadPct = loads.averageOrNull(),
                meanRpm = rpms.averageOrNull(),
                meanFuelLh = fuels.averageOrNull()
            )
        }

        val on = stats(true)
        val off = stats(false)
        return Comparison(
            acOn = on,
            acOff = off,
            loadDeltaPct = deltaOf(on.meanLoadPct, off.meanLoadPct),
            rpmDelta = deltaOf(on.meanRpm, off.meanRpm),
            fuelDeltaLh = deltaOf(on.meanFuelLh, off.meanFuelLh)
        )
    }

    private fun deltaOf(on: Double?, off: Double?): Double? =
        if (on != null && off != null) on - off else null

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
