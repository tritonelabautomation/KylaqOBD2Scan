package com.example.analysis

/**
 * Idle start-stop accounting (owner 2026-09-15: "did you add fuel saved with auto start stop
 * function and also fuel spikes any during the auto start stop?").
 *
 * The Kylaq 1.0 TSI has an Idle Start-Stop System: at a standstill the ECU shuts the engine
 * off, keeps answering on CAN (rpm 0, fuel rate ~0) and cranks again on clutch/brake release.
 * Two things must be handled honestly:
 *
 *  1) FUEL SAVED. While stopped the car genuinely burns (almost) nothing - the measured fuel
 *     rate already reflects that in the trip total. What the driver wants to SEE is what the
 *     stop avoided: the warm-idle burn for the same seconds. The baseline is MEASURED from
 *     this trip's own warm-idle samples whenever there is enough evidence, and only falls
 *     back to the 1.05 L/h model figure otherwise - and the result is always labelled an
 *     estimate. No invented numbers: if the trip had no start-stop events there is nothing
 *     to claim and [Summary.baselineSource] is [Baseline.NONE].
 *
 *  2) RESTART SPIKES. Cranking enrichment briefly pushes the fuel rate up when the engine
 *     fires again. That fuel IS really burned (it stays in the trip total - hiding it would
 *     under-report consumption), but it must not pollute idle or trend averages as if it
 *     were steady-state burn, so the enrichment window is measured and reported separately.
 *
 * A stall is only recognised while fresh samples keep arriving: when the link goes quiet
 * (ignition off, Bluetooth gone) the gap CLOSES the window at the last observation instead
 * of turning missing data into claimed engine-off seconds.
 *
 * Pure JVM, no Android dependencies, unit-testable.
 */
object StartStopAnalyzer {

    /**
     * One point of the merged timeline. Fields are null when that PID did not report at this
     * timestamp; [analyze] carries the last known value forward across the normal polling
     * cadence and drops it after a data gap.
     */
    data class Observation(
        val tsMs: Long,
        val rpm: Double? = null,
        val speedKmh: Double? = null,
        val fuelLh: Double? = null
    )

    enum class Baseline {
        /** Warm-idle rate integrated from this trip's own idle samples. */
        MEASURED,
        /** No usable idle evidence in this trip - the 1.05 L/h model figure was used. */
        MODEL,
        /** No start-stop events: nothing to compare against, nothing claimed. */
        NONE
    }

    data class Summary(
        val stopEvents: Int = 0,
        /** Seconds with the engine off at a standstill while the ECU kept answering. */
        val engineOffSeconds: Double = 0.0,
        /** Fuel actually measured during the stalls (normally ~0; never assumed to be 0). */
        val fuelBurnedWhileStoppedL: Double = 0.0,
        val restartCount: Int = 0,
        /** Highest fuel rate seen inside a post-restart enrichment window. */
        val restartPeakFuelLh: Double? = null,
        /** Fuel integrated across all post-restart enrichment windows. */
        val restartSpikeFuelL: Double = 0.0,
        /** Warm-idle seconds (engine running, standing still, outside enrichment windows). */
        val warmIdleSeconds: Double = 0.0,
        /** Warm-idle rate measured on this trip, when there was enough evidence. */
        val warmIdleLh: Double? = null,
        val baselineSource: Baseline = Baseline.NONE,
        /** The rate the savings figure was computed against (measured rate or the model). */
        val baselineIdleLh: Double? = null,
        /**
         * (baseline x engine-off time) - measured fuel burned while stopped, clamped at 0.
         * An estimate relative to real recorded data - the UI must label it as such.
         */
        val estimatedFuelSavedL: Double = 0.0,
        /**
         * The measured stall windows themselves, (startTs, endTs) pairs, in trip order
         * (added 2026-09-16, owner pipeline task 5): endTs is the LAST observation still
         * proven stopped, never the restart instant - downstream slicing (battery voltage
         * during stalls) must not credit running time to a stall.
         */
        val stopWindows: List<Pair<Long, Long>> = emptyList()
    ) {
        val hasStartStopActivity: Boolean get() = stopEvents > 0
    }

    /** Below this, with the vehicle standing still and the ECU answering, the engine is off. */
    const val ENGINE_RUNNING_RPM = 300.0

    /** A restart must be decisive; cranking flicker must not end a stall early. */
    const val RESTART_RPM = 400.0

    /** Warm-idle window for baseline measurement (matches the idle band used elsewhere). */
    const val IDLE_RPM_MAX = 1300.0

    /** Anything shorter is a sensor dropout or restart flicker, not a start-stop event. */
    const val MIN_STOP_SECONDS = 3.0

    /** Same gap rule as TripFuelSummary: slow PIDs legitimately arrive ~10 s apart. */
    const val MAX_GAP_MS = 15_000L

    /** Cranking-enrichment window after a restart; excluded from the idle baseline. */
    const val SPIKE_WINDOW_MS = 6_000L

    /** Minimum idle evidence before the trip's own rate replaces the model figure. */
    const val MIN_BASELINE_IDLE_SECONDS = 30.0

    /** Matches PowertrainModel.IDLE_FUEL_LH - kept local so the analyzer stays dependency-free. */
    const val MODEL_IDLE_LH = 1.05

    fun analyze(observations: List<Observation>): Summary {
        if (observations.size < 2) return Summary()
        val sorted = observations.sortedBy { it.tsMs }

        // Forward-fill state with gap expiry.
        var lastRpm: Double? = null
        var lastSpeed: Double? = null
        var lastFuel: Double? = null
        var lastRpmTs = Long.MIN_VALUE
        var lastSpeedTs = Long.MIN_VALUE
        var lastFuelTs = Long.MIN_VALUE

        var stopEvents = 0
        var engineOffSeconds = 0.0
        var fuelWhileStoppedL = 0.0
        var restartCount = 0
        var spikeFuelL = 0.0
        var spikePeak: Double? = null
        var spikeUntilTs = Long.MIN_VALUE
        var warmIdleSeconds = 0.0
        var idleFuelSeconds = 0.0
        var idleFuelL = 0.0

        // Pending stall: seconds/fuel accumulate and are committed only when the stall
        // proves real (>= MIN_STOP_SECONDS). A flicker is discarded, not claimed.
        var pendingSeconds = 0.0
        var pendingFuelL = 0.0
        var pendingStartTs: Long? = null
        val stopWindows = mutableListOf<Pair<Long, Long>>()

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]

            // Update the carried-forward picture with whatever prev reported.
            prev.rpm?.let { lastRpm = it; lastRpmTs = prev.tsMs }
            prev.speedKmh?.let { lastSpeed = it; lastSpeedTs = prev.tsMs }
            prev.fuelLh?.let { lastFuel = it; lastFuelTs = prev.tsMs }

            val rawGapMs = cur.tsMs - prev.tsMs
            if (rawGapMs > MAX_GAP_MS) {
                // The link went quiet (ignition off, Bluetooth drop, ECU silent). Close the
                // pending stall at the last observation - silent time is NEVER converted
                // into claimed engine-off seconds - and expire carried values.
                if (pendingSeconds >= MIN_STOP_SECONDS) {
                    stopEvents++
                    engineOffSeconds += pendingSeconds
                    fuelWhileStoppedL += pendingFuelL
                    pendingStartTs?.let { stopWindows += it to prev.tsMs }
                }
                pendingSeconds = 0.0
                pendingFuelL = 0.0
                pendingStartTs = null
                if (cur.tsMs - lastRpmTs > MAX_GAP_MS) lastRpm = null
                if (cur.tsMs - lastSpeedTs > MAX_GAP_MS) lastSpeed = null
                if (cur.tsMs - lastFuelTs > MAX_GAP_MS) lastFuel = null
                continue
            }
            if (rawGapMs <= 0L) continue
            val dt = rawGapMs / 1000.0

            // Interval attribution: the seconds before cur belong to the state carried by
            // prev (same rule as TripTrendAnalyzer / RideBehaviorRecorder).
            val engineOff = lastRpm != null && lastRpm < ENGINE_RUNNING_RPM &&
                (lastSpeed == null || lastSpeed < 1.0)
            val inSpikeWindow = prev.tsMs < spikeUntilTs
            val warmIdle = !engineOff && !inSpikeWindow &&
                lastRpm != null && lastRpm in RESTART_RPM..IDLE_RPM_MAX &&
                (lastSpeed == null || lastSpeed < 1.0)

            when {
                engineOff -> {
                    if (pendingStartTs == null) pendingStartTs = prev.tsMs
                    pendingSeconds += dt
                    lastFuel?.let { pendingFuelL += it * dt / 3600.0 }
                }

                else -> {
                    // The stall (if any) just ended.
                    if (pendingSeconds >= MIN_STOP_SECONDS) {
                        stopEvents++
                        engineOffSeconds += pendingSeconds
                        fuelWhileStoppedL += pendingFuelL
                        pendingStartTs?.let { stopWindows += it to prev.tsMs }
                        if (lastRpm != null && lastRpm >= RESTART_RPM) {
                            // Engine fired again: open the cranking-enrichment window at the
                            // start of the transition interval, where cranking actually began.
                            restartCount++
                            spikeUntilTs = prev.tsMs + SPIKE_WINDOW_MS
                        }
                    }
                    pendingSeconds = 0.0
                    pendingFuelL = 0.0
                    pendingStartTs = null

                    if (inSpikeWindow) {
                        lastFuel?.let {
                            spikeFuelL += it * dt / 3600.0
                            if (spikePeak == null || it > spikePeak!!) spikePeak = it
                        }
                    } else if (warmIdle) {
                        warmIdleSeconds += dt
                        lastFuel?.let {
                            idleFuelSeconds += dt
                            idleFuelL += it * dt / 3600.0
                        }
                    }
                }
            }
        }

        // A trip that ends while still stalled (parked with start-stop active, recording
        // stopped by the watchdog) still gets its committed seconds.
        if (pendingSeconds >= MIN_STOP_SECONDS) {
            stopEvents++
            engineOffSeconds += pendingSeconds
            fuelWhileStoppedL += pendingFuelL
            pendingStartTs?.let { stopWindows += it to sorted.last().tsMs }
        }

        val measuredIdleLh =
            if (idleFuelSeconds >= MIN_BASELINE_IDLE_SECONDS && idleFuelL > 0.0) {
                idleFuelL / (idleFuelSeconds / 3600.0)
            } else {
                null
            }

        val baselineSource = when {
            stopEvents == 0 -> Baseline.NONE
            measuredIdleLh != null -> Baseline.MEASURED
            else -> Baseline.MODEL
        }
        val baselineLh = when (baselineSource) {
            Baseline.MEASURED -> measuredIdleLh
            Baseline.MODEL -> MODEL_IDLE_LH
            Baseline.NONE -> null
        }
        val savedL = if (baselineLh != null) {
            maxOf(0.0, baselineLh * engineOffSeconds / 3600.0 - fuelWhileStoppedL)
        } else {
            0.0
        }

        return Summary(
            stopEvents = stopEvents,
            engineOffSeconds = engineOffSeconds,
            fuelBurnedWhileStoppedL = fuelWhileStoppedL,
            restartCount = restartCount,
            restartPeakFuelLh = spikePeak,
            restartSpikeFuelL = spikeFuelL,
            warmIdleSeconds = warmIdleSeconds,
            warmIdleLh = measuredIdleLh,
            baselineSource = baselineSource,
            baselineIdleLh = baselineLh,
            estimatedFuelSavedL = savedL,
            stopWindows = stopWindows
        )
    }
}
