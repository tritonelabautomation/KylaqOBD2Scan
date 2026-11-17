package com.example.analysis

/**
 * What the battery sees during idle start-stop stalls (owner pipeline task 5, 2026-09-16:
 * "When engine start stop stopped car sometime AC will be still running during that time
 * does it consuming battery").
 *
 * Honest physics first: the Kylaq's 7VL compressor is BELT-driven with an electromagnetic
 * clutch (docs/reference/kylaq-ac-compressor-hardware.md) - with the engine off the belt
 * stands still, so the compressor CANNOT pump and cooling pauses until restart. What does
 * keep running is the blower/fans, straight off the 12 V battery - yes, it is consuming
 * battery, which is exactly why the start-stop ECU watches state of charge and restarts
 * the engine when the battery needs it.
 *
 * J1979 exposes no battery-current PID, so this analyzer measures the voltage picture:
 * mean/min voltage inside the real stall windows vs the engine-running charging baseline,
 * plus how many stalls overlapped a MEASURED AC-on segment (blower demand present). The
 * depression is a labelled proxy for load - never converted to invented amp-hours.
 */
object StopBatteryAnalyzer {

    data class Result(
        val stopsWithVoltage: Int = 0,
        val samplesInStops: Int = 0,
        val meanVInStops: Double? = null,
        val minVInStops: Double? = null,
        val meanVRunning: Double? = null,
        val acOnStops: Int = 0,
        val acOnStopsTotal: Int = 0
    ) {
        /** Enough stall samples AND a charging baseline to say anything at all. */
        val hasEvidence: Boolean
            get() = samplesInStops >= MIN_SAMPLES_IN_STOPS && meanVRunning != null && meanVInStops != null

        /** How far the battery sags under stall loads vs the charging baseline (proxy). */
        val depressionV: Double?
            get() = if (meanVRunning != null && meanVInStops != null) meanVRunning - meanVInStops else null
    }

    /** Fewer than 3 voltage samples inside stalls is a flicker, not a picture. */
    const val MIN_SAMPLES_IN_STOPS = 3

    /**
     * @param stopWindows stall windows (startTs, endTs) from StartStopAnalyzer - real
     *   measured stalls only; @param voltageSeries all stored 0142 samples;
     * @param runningVoltageSeries voltage samples while the engine was running (the
     *   charging baseline - caller filters by rpm, stalls excluded by construction);
     * @param acSegments MEASURED AC segments from AcVoltageDetector.
     */
    fun analyze(
        stopWindows: List<Pair<Long, Long>>,
        voltageSeries: List<Pair<Long, Double>>,
        runningVoltageSeries: List<Pair<Long, Double>>,
        acSegments: List<AcVoltageDetector.Segment>
    ): Result {
        if (stopWindows.isEmpty() || voltageSeries.isEmpty()) return Result()

        val inStops = voltageSeries.filter { (ts, _) -> stopWindows.any { (a, b) -> ts in a..b } }
        val stopsWithVoltage = stopWindows.count { (a, b) ->
            voltageSeries.any { (ts, _) -> ts in a..b }
        }
        val acOnSegs = acSegments.filter { it.acOn }
        val acOnStops = stopWindows.count { (a, b) ->
            acOnSegs.any { seg -> a < seg.endTs && b > seg.startTs }
        }
        val running = runningVoltageSeries.map { it.second }

        return Result(
            stopsWithVoltage = stopsWithVoltage,
            samplesInStops = inStops.size,
            meanVInStops = if (inStops.isNotEmpty()) inStops.map { it.second }.average() else null,
            minVInStops = inStops.minOfOrNull { it.second },
            meanVRunning = if (running.isNotEmpty()) running.average() else null,
            acOnStops = acOnStops,
            acOnStopsTotal = stopWindows.size
        )
    }
}
