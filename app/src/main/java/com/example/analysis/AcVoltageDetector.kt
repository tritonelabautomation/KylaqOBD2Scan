package com.example.analysis

/**
 * AC-state detection from battery-voltage fluctuation (owner 2026-09-16: "i found a easy
 * way to guess if AC is in or off see voltage fluctuations due to AC on", with a labelled
 * drive: AC off for the first ~10 km, switched on during a 90-100 s signal halt).
 *
 * WHY THIS EXISTS: SAE J1979 mode 01 exposes NO compressor-state PID on the EA211 (the
 * climate bus is a separate comfort-CAN the ELM327 never sees), so until now the app could
 * only price AC from owner tags via [com.example.engine.AcClimateModel]. The electrical
 * system gives a MEASURED proxy:
 *  - AC OFF: clutch open, pulley free-wheels; the only 0142 movement is alternator
 *    regulation - a quiet band (MAD typically a few hundredths of a volt);
 *  - AC ON: compressor clutch cycles and the blower/condenser fans pulse, so electrical
 *    load steps repeatedly and the regulated voltage fluctuates visibly around a slightly
 *    lower mean (the alternator is carrying real load).
 *
 * DESIGN RULES (same honesty bar as everything else):
 *  - self-calibrating: the "quiet" baseline is THIS trip's own quietest windows (20th
 *    percentile), never a hard-coded volts-per-car guess; thresholds are multiples of it
 *    with a physical floor;
 *  - engine-running samples only (rpm >= 400): cranking dips and key-off decay are not AC;
 *  - cranking-territory voltages (< 12.2 V) are masked even when rpm says running;
 *  - MAD (median-absolute-deviation) not std-dev, so single coast-alternator-boost spikes
 *    cannot flip the state;
 *  - hysteresis (2 windows to ON, 3 windows back to OFF) so clutch beats near the threshold
 *    do not chatter the state;
 *  - when the evidence is thin (few windows, or no separation between quiet and noisy
 *    regimes) the result says so via [Result.confidence] instead of pretending certainty.
 *
 * Pure JVM, unit tested in AcVoltageDetectorTest. Real-drive validation against the
 * owner's labelled 2df90142 CSV is pending re-attachment (uploads are ephemeral).
 */
object AcVoltageDetector {

    data class Sample(
        val tsMs: Long,
        val voltageV: Double,
        val rpm: Double? = null
    )

    /** One analysis window: centre time, robust fluctuation, evidence count. */
    data class WindowMetric(val tsMs: Long, val madV: Double, val samples: Int)

    data class Segment(val startTs: Long, val endTs: Long, val acOn: Boolean) {
        val seconds: Double get() = (endTs - startTs) / 1000.0
    }

    data class Result(
        val segments: List<Segment> = emptyList(),
        /** (timestamp, new state) for every state flip, in order. */
        val switchEvents: List<Pair<Long, Boolean>> = emptyList(),
        val acOnSeconds: Double = 0.0,
        /** MAD of the trip's quietest windows - the AC-off electrical baseline. */
        val quietMadV: Double? = null,
        /** Median MAD inside detected ON segments. */
        val onMadV: Double? = null,
        /**
         * Separation between noisy and quiet regimes (p90/p20 of window MADs). ~1 means
         * the whole trip looks the same and any ON/OFF split is weak evidence.
         */
        val confidence: Double = 0.0,
        val windows: Int = 0
    ) {
        val hasEvidence: Boolean get() = windows >= MIN_WINDOWS
        companion object {
            val EMPTY = Result()
        }
    }

    /** Below this rpm the alternator signature is not the Climatronic's fault. */
    const val ENGINE_RUNNING_RPM = 400.0

    /** Cranking / recovery territory - masked even if rpm claims running. */
    const val CRANK_MASK_V = 12.2
    const val OVERVOLT_MASK_V = 15.5

    const val WINDOW_MS = 60_000L
    const val STEP_MS = 30_000L
    const val MIN_SAMPLES_PER_WINDOW = 12
    const val MIN_WINDOWS = 4

    /** Hysteresis: windows above/below threshold before the state may flip. */
    const val WINDOWS_TO_ON = 2
    const val WINDOWS_TO_OFF = 3

    /** Physical floors so a freak-quiet trip cannot shrink thresholds to nothing. */
    const val QUIET_FLOOR_V = 0.02
    const val ON_MARGIN_V = 0.10
    const val OFF_MARGIN_V = 0.06

    fun detect(
        samples: List<Sample>,
        windowMs: Long = WINDOW_MS,
        stepMs: Long = STEP_MS
    ): Result {
        if (samples.size < MIN_SAMPLES_PER_WINDOW) return Result.EMPTY
        val sorted = samples.sortedBy { it.tsMs }
        val usable = sorted.filter { s ->
            s.rpm != null && s.rpm >= ENGINE_RUNNING_RPM &&
                s.voltageV in CRANK_MASK_V..OVERVOLT_MASK_V
        }
        if (usable.size < MIN_SAMPLES_PER_WINDOW) return Result.EMPTY

        val firstTs = usable.first().tsMs
        val lastTs = usable.last().tsMs
        if (lastTs - firstTs < windowMs) return Result.EMPTY

        // ---- windowed robust fluctuation ----
        val metrics = ArrayList<WindowMetric>()
        var w = firstTs
        while (w + windowMs <= lastTs + stepMs) {
            val inWindow = usable.filter { it.tsMs in w until (w + windowMs) }
            if (inWindow.size >= MIN_SAMPLES_PER_WINDOW) {
                metrics += WindowMetric(w, mad(inWindow.map { it.voltageV }), inWindow.size)
            }
            w += stepMs
        }
        if (metrics.size < MIN_WINDOWS) return Result.EMPTY

        // ---- self-calibration ----
        val mads = metrics.map { it.madV }.sorted()
        val quiet = maxOf(percentile(mads, 0.20), QUIET_FLOOR_V)
        val noisy = percentile(mads, 0.90)
        val thrOn = maxOf(quiet * 2.0, quiet + ON_MARGIN_V)
        val thrOff = maxOf(quiet * 1.5, quiet + OFF_MARGIN_V)

        // ---- hysteretic state machine over windows ----
        var acOn = false
        var above = 0
        var below = 0
        val switches = mutableListOf<Pair<Long, Boolean>>()
        val windowState = mutableListOf<Pair<WindowMetric, Boolean>>()
        for (m in metrics) {
            when {
                m.madV >= thrOn -> {
                    above++
                    below = 0
                    if (!acOn && above >= WINDOWS_TO_ON) {
                        acOn = true
                        switches += m.tsMs to true
                    }
                }
                m.madV < thrOff -> {
                    below++
                    above = 0
                    if (acOn && below >= WINDOWS_TO_OFF) {
                        acOn = false
                        switches += m.tsMs to false
                    }
                }
                else -> { /* between thresholds: hold state, decay both counters */
                    above = 0
                    below = 0
                }
            }
            windowState += m to acOn
        }

        // ---- segments from the per-window state track ----
        val segments = mutableListOf<Segment>()
        var segStart = firstTs
        var segOn = windowState.first().second
        for (i in 1 until windowState.size) {
            val (m, state) = windowState[i]
            if (state != segOn) {
                segments += Segment(segStart, m.tsMs, segOn)
                segStart = m.tsMs
                segOn = state
            }
        }
        segments += Segment(segStart, lastTs, segOn)

        val onSeconds = segments.filter { it.acOn }.sumOf { it.seconds }
        val onMads = windowState.filter { it.second }.map { it.first.madV }
        val confidence = if (quiet > 0.0) (noisy / quiet).coerceAtMost(99.0) else 0.0

        return Result(
            segments = segments,
            switchEvents = switches,
            acOnSeconds = onSeconds,
            quietMadV = quiet,
            onMadV = if (onMads.isNotEmpty()) median(onMads.sorted()) else null,
            confidence = confidence,
            windows = metrics.size
        )
    }

    /** Mean absolute deviation from the median - robust to single alternator-boost spikes. */
    private fun mad(values: List<Double>): Double {
        val med = median(values.sorted())
        return values.map { kotlin.math.abs(it - med) }.average()
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun percentile(sortedAsc: List<Double>, p: Double): Double {
        if (sortedAsc.isEmpty()) return 0.0
        val idx = (p * (sortedAsc.size - 1))
        val lo = idx.toInt().coerceIn(0, sortedAsc.size - 1)
        val hi = (lo + 1).coerceIn(0, sortedAsc.size - 1)
        val frac = idx - lo
        return sortedAsc[lo] * (1 - frac) + sortedAsc[hi] * frac
    }
}
