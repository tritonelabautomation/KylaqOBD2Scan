package com.example.service

/**
 * The auto-record rule, extracted so it can be tested and so BOTH supervisors obey one definition.
 *
 * Owner mandate, 2026-09-17: *"even in background all ways it should run and record it never ever
 * loose the logs."* Two supervisors now run this rule - one in `MainViewModel` (while the phone UI
 * is open) and one in [ObdKeepAliveService] (while the process lives, UI or no UI). Two copies of a
 * timing rule is how one of them ends up shredding a drive, so the decision lives here and both
 * callers just act on it.
 *
 * ## The Kylaq's Idle Start-Stop trap (owner 2026-09-15, P0)
 *
 * This car shuts the engine off at traffic lights and restarts it when the clutch goes in. While it
 * is off, the ECU keeps answering with rpm = 0 - the link is alive, the car is awake, the driver is
 * sitting at a red light. A single "engine off for 60 s -> stop recording" rule therefore cut a city
 * drive into fragments at every long junction, and the restart began a SECOND trip.
 *
 * The distinction that fixes it is between a *fresh low reading* and *no reading at all*:
 *
 *  - `rpm <= 200` on a FRESH answer: the adapter is talking to an awake car. That is a start-stop
 *    stall, so it gets the long grace window - long enough to sit through a junction.
 *  - `rpm == null`: `liveNumericMap` only serves fresh values, so a null means the entry went stale
 *    and was dropped. Ignition off, or Bluetooth gone. That is a real end of drive, so it gets the
 *    short window.
 */
object AutoRecordPolicy {

    /** Below this the engine is not driving the car. Idle on this engine is ~970 rpm warm. */
    const val ENGINE_RUNNING_RPM = 200.0

    /**
     * Idle Start-Stop grace. Five minutes at a junction is already unusual; the point is that it
     * must exceed any single red light by a wide margin.
     */
    const val START_STOP_GRACE_MS = 300_000L

    /** Ignition genuinely off (no fresh readings at all): one minute, then save the trip. */
    const val ENGINE_OFF_GRACE_MS = 60_000L

    /**
     * A session younger than this is never auto-stopped (owner 2026-09-21: a 31 km drive
     * listed a 3-transaction stub trip). Cranking blips and half-second ECU silence at
     * start can otherwise open AND close a session before the drive begins; the grace
     * windows below already cover real ends of drive, this covers false ones at the start.
     */
    const val MIN_SESSION_MS = 90_000L

    /** What the supervisor should do on this tick. */
    enum class Decision {
        /** Engine running and nothing is being recorded: start. */
        START_RECORDING,

        /** Nothing to do - either already recording with the engine on, or engine off and idle. */
        NONE,

        /** The engine has been off longer than the applicable grace window: stop and save. */
        STOP_RECORDING
    }

    /**
     * The grace window that applies to this reading. Exposed because the reason behind it is the
     * part that keeps getting re-broken: a fresh 0 rpm is a traffic light, a missing rpm is a
     * parked car.
     */
    fun graceMsFor(rpm: Double?): Long = if (rpm != null) START_STOP_GRACE_MS else ENGINE_OFF_GRACE_MS

    /**
     * @param rpm               the fresh 010C reading, or null when the map holds none (stale).
     * @param isRecording       whether a session is open.
     * @param isPolling         whether the scheduler is actually polling - starting a recording
     *                          against a dead link produces an empty trip, which is worse than no
     *                          trip because it looks like a drive that got 0 km.
     * @param autoRecordEnabled the owner's setting. Off means off: the supervisor never starts a
     *                          recording behind his back, and never stops one either - an open
     *                          session with auto-record switched off is the owner's to close.
     * @param engineOffSinceMs  elapsed-realtime mark of when the engine first read off, 0 = running.
     * @param nowMs             `SystemClock.elapsedRealtime()`. Monotonic, so a wall-clock jump or a
     *                          timezone change cannot stretch or truncate a drive.
     */
    fun decide(
        rpm: Double?,
        isRecording: Boolean,
        isPolling: Boolean,
        autoRecordEnabled: Boolean,
        engineOffSinceMs: Long,
        nowMs: Long,
        sessionAgeMs: Long? = null
    ): Decision {
        if (!autoRecordEnabled) return Decision.NONE

        val engineRunning = rpm != null && rpm > ENGINE_RUNNING_RPM
        if (engineRunning) {
            if (isRecording) return Decision.NONE
            // Never open a session against a dead link: the trip would be empty, and an empty trip
            // in the list looks like a drive that covered 0 km rather than like nothing at all.
            return if (isPolling) Decision.START_RECORDING else Decision.NONE
        }
        if (!isRecording) return Decision.NONE

        // Engine off - or no fresh reading at all - while a session is open. Which window applies
        // depends on which of those it is; see the Idle Start-Stop note above.
        val grace = graceMsFor(rpm)
        return if (engineOffSinceMs > 0L && nowMs - engineOffSinceMs > grace &&
            (sessionAgeMs == null || sessionAgeMs >= MIN_SESSION_MS)
        ) {
            Decision.STOP_RECORDING
        } else {
            Decision.NONE
        }
    }

    /**
     * The engine-off mark to carry into the next tick.
     *
     * `0` means "engine is running", so a fresh high reading always clears the clock - otherwise a
     * drive that stalled once would keep a stale mark and stop early on the next red light.
     */
    fun nextEngineOffSince(rpm: Double?, isRecording: Boolean, engineOffSinceMs: Long, nowMs: Long): Long {
        if (rpm != null && rpm > ENGINE_RUNNING_RPM) return 0L
        if (!isRecording) return 0L
        return if (engineOffSinceMs == 0L) nowMs else engineOffSinceMs
    }
}
