package com.example.engine

/**
 * Detects and quantifies "driving in neutral" / coasting on the Kylaq 1.0 TSI.
 *
 * The owner's manual describes the feature the user wants tracked: when the selector lever is
 * in D/S, neither pedal is depressed and the speed is between 20 and 130 km/h, the gearbox can
 * disengage and the engine drops to idle instead of engine-braking. OBD sees two distinct
 * coasting signatures, and this class tells them apart:
 *
 *  * [CoastMode.ENGINE_BRAKING_FUEL_CUT] — gear still engaged, injectors shut off (fuel rate
 *    ≈ 0, rpm well above idle). This is the *efficient* coast.
 *  * [CoastMode.NEUTRAL_IDLE_COAST] — drivetrain disengaged, engine at idle speed (rpm near
 *    idle while the car is still moving). The car burns idle fuel but loses engine braking.
 *
 * For every completed event we integrate distance, duration and the fuel actually burned, and
 * compare it against two honest baselines:
 *
 *  * idle burn over the same time (what a neutral coast would cost) — saved by fuel-cut coasting;
 *  * modelled powered cruise at the same speed (what holding the speed with the pedal costs) —
 *    saved by *any* coast.
 *
 * The 20-130 km/h window and "both pedals released" rule come straight from the manual's
 * conditions for the automatic idle-shift, so events outside it are ignored on purpose.
 */
class CoastNeutralDetector(
    private val minSpeedKmh: Double = 20.0,
    private val maxSpeedKmh: Double = 130.0,
    private val idleRpm: Double = 900.0,
    private val idleRpmTolerance: Double = 250.0,
    private val releasedPedalPct: Double = 2.0,
    private val idleFuelLh: Double = PowertrainModel.IDLE_FUEL_LH,
    private val maxRetainedEvents: Int = 200
) {

    enum class CoastMode { ENGINE_BRAKING_FUEL_CUT, NEUTRAL_IDLE_COAST }

    data class Sample(
        val timestampMonotonicMs: Long,
        val speedKmh: Double,
        val rpm: Double?,
        val throttlePct: Double?,
        val pedalPct: Double?,
        val brakeActive: Boolean?,
        val fuelRateLh: Double?,
        /** Modelled L/h needed to hold this speed with the pedal; supplied by the caller. */
        val cruiseFuelLh: Double?
    )

    data class CoastEvent(
        val startMonotonicMs: Long,
        val endMonotonicMs: Long,
        val durationMs: Long,
        val distanceM: Double,
        val mode: CoastMode,
        val fuelUsedL: Double,
        val fuelSavedVsIdleL: Double,
        val fuelSavedVsCruiseL: Double,
        val maxSpeedKmh: Double,
        val averageSpeedKmh: Double
    )

    data class CoastSummary(
        val eventCount: Int = 0,
        val neutralEvents: Int = 0,
        val fuelCutEvents: Int = 0,
        val totalDistanceM: Double = 0.0,
        val totalSeconds: Double = 0.0,
        val totalFuelUsedL: Double = 0.0,
        val totalSavedVsIdleL: Double = 0.0,
        val totalSavedVsCruiseL: Double = 0.0
    ) {
        val totalKm: Double get() = totalDistanceM / 1000.0
    }

    private class Open(
        val startMs: Long,
        var lastMs: Long,
        var mode: CoastMode,
        var distanceM: Double,
        var fuelUsedL: Double,
        var savedVsIdleL: Double,
        var savedVsCruiseL: Double,
        var maxSpeed: Double,
        var speedTimeIntegral: Double
    )

    private var open: Open? = null
    private val events = ArrayDeque<CoastEvent>()

    var summary: CoastSummary = CoastSummary()
        private set
    var activeMode: CoastMode? = null
        private set
    var lastEvent: CoastEvent? = null
        private set

    fun recentEvents(limit: Int = 20): List<CoastEvent> = events.toList().takeLast(limit)

    /**
     * Feeds one telemetry sample. Returns a completed [CoastEvent] when a coasting phase ends,
     * otherwise null.
     */
    fun onSample(sample: Sample): CoastEvent? {
        val coasting = isCoasting(sample)
        val now = sample.timestampMonotonicMs

        if (!coasting) {
            val closing = open
            open = null
            activeMode = null
            return closing?.let { close(it, now) }
        }

        val dtHours = open?.let { (now - it.lastMs).coerceIn(0L, 5000L) / 3_600_000.0 } ?: 0.0
        val dtSeconds = open?.let { (now - it.lastMs).coerceIn(0L, 5000L) / 1000.0 } ?: 0.0

        val mode = classify(sample)
        val existing = open
        if (existing == null) {
            open = Open(
                startMs = now,
                lastMs = now,
                mode = mode,
                distanceM = 0.0,
                fuelUsedL = 0.0,
                savedVsIdleL = 0.0,
                savedVsCruiseL = 0.0,
                maxSpeed = sample.speedKmh,
                speedTimeIntegral = 0.0
            )
            activeMode = mode
            return null
        }

        existing.lastMs = now
        existing.mode = mode
        existing.distanceM += sample.speedKmh / 3.6 * dtSeconds
        existing.speedTimeIntegral += sample.speedKmh * dtSeconds
        existing.maxSpeed = maxOf(existing.maxSpeed, sample.speedKmh)
        existing.fuelUsedL += (sample.fuelRateLh ?: 0.0) * dtHours
        existing.savedVsIdleL += maxOf(0.0, idleFuelLh - (sample.fuelRateLh ?: idleFuelLh)) * dtHours
        existing.savedVsCruiseL += maxOf(0.0, (sample.cruiseFuelLh ?: 0.0) - (sample.fuelRateLh ?: 0.0)) * dtHours
        activeMode = mode
        return null
    }

    fun reset() {
        open = null
        activeMode = null
        events.clear()
        lastEvent = null
        summary = CoastSummary()
    }

    private fun isCoasting(sample: Sample): Boolean {
        if (sample.speedKmh < minSpeedKmh || sample.speedKmh > maxSpeedKmh) return false
        if (sample.brakeActive == true) return false
        val throttleReleased = sample.throttlePct == null || sample.throttlePct <= releasedPedalPct
        val pedalReleased = sample.pedalPct == null || sample.pedalPct <= releasedPedalPct
        return throttleReleased && pedalReleased
    }

    private fun classify(sample: Sample): CoastMode {
        val rpm = sample.rpm
        return if (rpm != null && rpm <= idleRpm + idleRpmTolerance) {
            CoastMode.NEUTRAL_IDLE_COAST
        } else {
            CoastMode.ENGINE_BRAKING_FUEL_CUT
        }
    }

    private fun close(openEvent: Open, nowMs: Long): CoastEvent {
        val durationMs = (nowMs - openEvent.startMs).coerceAtLeast(0L)
        // Ignore taps shorter than a second: sensor noise, not a coast.
        if (durationMs < 1000L) return null

        val event = CoastEvent(
            startMonotonicMs = openEvent.startMs,
            endMonotonicMs = nowMs,
            durationMs = durationMs,
            distanceM = openEvent.distanceM,
            mode = openEvent.mode,
            fuelUsedL = openEvent.fuelUsedL,
            fuelSavedVsIdleL = openEvent.savedVsIdleL,
            fuelSavedVsCruiseL = openEvent.savedVsCruiseL,
            maxSpeedKmh = openEvent.maxSpeed,
            averageSpeedKmh = averageSpeed(openEvent)
        )
        events.addLast(event)
        while (events.size > maxRetainedEvents) events.removeFirst()
        lastEvent = event
        summary = summary.copy(
            eventCount = summary.eventCount + 1,
            neutralEvents = summary.neutralEvents + if (event.mode == CoastMode.NEUTRAL_IDLE_COAST) 1 else 0,
            fuelCutEvents = summary.fuelCutEvents + if (event.mode == CoastMode.ENGINE_BRAKING_FUEL_CUT) 1 else 0,
            totalDistanceM = summary.totalDistanceM + event.distanceM,
            totalSeconds = summary.totalSeconds + durationMs / 1000.0,
            totalFuelUsedL = summary.totalFuelUsedL + event.fuelUsedL,
            totalSavedVsIdleL = summary.totalSavedVsIdleL + event.fuelSavedVsIdleL,
            totalSavedVsCruiseL = summary.totalSavedVsCruiseL + event.fuelSavedVsCruiseL
        )
        return event
    }

    private fun averageSpeed(openEvent: Open): Double {
        val seconds = (openEvent.lastMs - openEvent.startMs) / 1000.0
        return if (seconds > 0.5) openEvent.speedTimeIntegral / seconds else openEvent.maxSpeed
    }
}
