package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The auto-record rule, pinned down.
 *
 * Owner mandate 2026-09-17: *"it supposed to be service right even in background all ways it should
 * run and record it never ever loose the logs."* The rule used to be an inline `while` loop inside
 * `MainViewModel.startSessionAutomation()`, which meant (a) it stopped the moment the UI went away
 * and (b) it was untestable, so the Idle Start-Stop regression that shredded a city drive into
 * fragments was found by the owner on the road rather than by a test.
 *
 * It now lives here as a pure function run by BOTH the UI supervisor and the service supervisor, and
 * every one of these cases is a drive that used to be lost or fragmented.
 */
class AutoRecordPolicyTest {

    private val rpmIdle = 970.0      // warm idle on the 1.0 TSI
    private val rpmZero = 0.0        // fresh answer with the engine off: a start-stop stall
    private val noReading: Double? = null

    private fun decide(
        rpm: Double?,
        recording: Boolean,
        polling: Boolean = true,
        auto: Boolean = true,
        offSince: Long = 0L,
        now: Long = 1_000_000L
    ) = AutoRecordPolicy.decide(rpm, recording, polling, auto, offSince, now)

    // ── Starting ───────────────────────────────────────────────────────────────────────────

    @Test
    fun engineOnAndNothingRecordingStartsADrive() {
        assertEquals(
            AutoRecordPolicy.Decision.START_RECORDING,
            decide(rpm = rpmIdle, recording = false)
        )
    }

    @Test
    fun engineOnWhileAlreadyRecordingChangesNothing() {
        // The other half of the "two trips for one drive" bug: a supervisor that starts again
        // mid-drive replaces the session.
        assertEquals(AutoRecordPolicy.Decision.NONE, decide(rpm = rpmIdle, recording = true))
    }

    @Test
    fun neverStartsADriveAgainstADeadLink() {
        // rpm can still hold a fresh value while the scheduler has stopped polling (link dropped a
        // moment ago). Opening a session then writes an EMPTY trip, and an empty trip in the list
        // looks like a drive that covered 0 km - worse than no trip at all.
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            decide(rpm = rpmIdle, recording = false, polling = false)
        )
    }

    @Test
    fun settingOffMeansTheSupervisorNeverTouchesRecording() {
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            decide(rpm = rpmIdle, recording = false, auto = false)
        )
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            decide(rpm = noReading, recording = true, auto = false, offSince = 1L, now = 9_999_999L)
        )
    }

    // ── Idle Start-Stop (owner 2026-09-15, P0) ─────────────────────────────────────────────

    @Test
    fun aFreshZeroRpmIsATrafficLightNotTheEndOfTheDrive() {
        // The Kylaq shuts the engine off at junctions and keeps answering with rpm = 0. The old
        // single 60 s rule stopped the recording there and the restart began a second trip.
        val stall = 240_000L // four minutes at a long junction, well inside the grace
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            decide(rpm = rpmZero, recording = true, offSince = 1_000L, now = 1_000L + stall)
        )
        assertEquals(AutoRecordPolicy.START_STOP_GRACE_MS, AutoRecordPolicy.graceMsFor(rpmZero))
    }

    @Test
    fun aStallLongerThanTheGraceWindowStillSavesTheTrip() {
        // Five minutes of fresh zero readings means the car really was switched off at a junction
        // and never restarted - that is a parked car, and the drive gets saved.
        assertEquals(
            AutoRecordPolicy.Decision.STOP_RECORDING,
            decide(
                rpm = rpmZero,
                recording = true,
                offSince = 1_000L,
                now = 1_000L + AutoRecordPolicy.START_STOP_GRACE_MS + 1L
            )
        )
    }

    @Test
    fun aMissingReadingIsAnIgnitionOffAndGetsTheShortWindow() {
        // `liveNumericMap` only serves fresh values, so null means the entry went stale: ignition
        // off or Bluetooth gone. One minute, then save - a parked car must not keep a session open
        // for five minutes and hold the adapter busy.
        assertEquals(AutoRecordPolicy.ENGINE_OFF_GRACE_MS, AutoRecordPolicy.graceMsFor(noReading))
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            decide(rpm = noReading, recording = true, offSince = 1_000L, now = 30_000L)
        )
        assertEquals(
            AutoRecordPolicy.Decision.STOP_RECORDING,
            decide(
                rpm = noReading,
                recording = true,
                offSince = 1_000L,
                now = 1_000L + AutoRecordPolicy.ENGINE_OFF_GRACE_MS + 1L
            )
        )
    }

    @Test
    fun engineOffWithoutAnOpenSessionIsNotAnEvent() {
        assertEquals(AutoRecordPolicy.Decision.NONE, decide(rpm = noReading, recording = false))
        assertEquals(AutoRecordPolicy.Decision.NONE, decide(rpm = rpmZero, recording = false))
    }

    // ── The engine-off clock ───────────────────────────────────────────────────────────────

    @Test
    fun theClockStartsOnTheFirstEngineOffTickAndIsNotRearmedEveryTick() {
        // If it were rearmed each tick the window would never elapse and the trip would never be
        // saved, which is the other way a drive stays open until the process dies.
        val t0 = 5_000L
        assertEquals(t0, AutoRecordPolicy.nextEngineOffSince(rpmZero, true, 0L, t0))
        val later = 9_000L
        assertEquals(
            "the mark must not move once set",
            t0,
            AutoRecordPolicy.nextEngineOffSince(rpmZero, true, t0, later)
        )
    }

    @Test
    fun aRestartedEngineClearsTheClock() {
        // Otherwise one stall early in a drive leaves a stale mark and the next junction stops it.
        assertEquals(
            0L,
            AutoRecordPolicy.nextEngineOffSince(rpmIdle, true, 5_000L, 9_000L)
        )
    }

    @Test
    fun theClockIsNotArmedWhileNothingIsRecording() {
        assertEquals(0L, AutoRecordPolicy.nextEngineOffSince(rpmZero, false, 0L, 9_000L))
    }

    @Test
    fun theBoundaryIsExactlyTheThresholdAndJustAboveIt() {
        assertEquals("200.0", AutoRecordPolicy.ENGINE_RUNNING_RPM.toString())
        // Cranking sits around 150-250 rpm, so the threshold has to be a strict greater-than or a
        // starter motor would open a trip.
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            decide(rpm = 200.0, recording = false)
        )
        assertEquals(
            AutoRecordPolicy.Decision.START_RECORDING,
            decide(rpm = 200.1, recording = false)
        )
    }

    @Test
    fun aWholeCityDriveWithSixJunctionsStaysOneTrip() {
        // End-to-end over the rule rather than one tick: idle -> stall at six junctions -> parked.
        // This is the drive the old 60 s rule cut into seven pieces.
        var offSince = 0L
        var recording = false
        var started = 0
        var stopped = 0
        var now = 0L

        fun tick(rpm: Double?) {
            val decision = AutoRecordPolicy.decide(rpm, recording, true, true, offSince, now)
            when (decision) {
                AutoRecordPolicy.Decision.START_RECORDING -> { recording = true; started++ }
                AutoRecordPolicy.Decision.STOP_RECORDING -> { recording = false; stopped++ }
                AutoRecordPolicy.Decision.NONE -> Unit
            }
            offSince = AutoRecordPolicy.nextEngineOffSince(rpm, recording, offSince, now)
        }

        // Leaves the driveway.
        tick(rpmIdle)
        repeat(6) {
            // Drives, then sits at a junction for two minutes with the engine off and the ECU awake.
            repeat(60) { now += 2_000; tick(rpmIdle) }
            repeat(60) { now += 2_000; tick(rpmZero) }
        }
        // Parks: the adapter stops answering, so the readings go stale.
        repeat(40) { now += 2_000; tick(noReading) }

        assertEquals("one drive, one session", 1, started)
        assertEquals("saved once, when he parked", 1, stopped)
        assertEquals(false, recording)
    }

    @Test
    fun aSessionYoungerThanTheMinimumIsNeverAutoStopped() {
        // Owner 2026-09-21: beside a 31 km drive the list carried a 3-transaction stub -
        // a cranking blip opened a session and ECU silence seconds later closed it. The
        // grace windows cover real ends of drive; MIN_SESSION_MS covers false ones at
        // the start.
        val now = 5_000_000L
        val offSince = now - 120_000L // long past any grace window
        assertEquals(
            AutoRecordPolicy.Decision.NONE,
            AutoRecordPolicy.decide(
                rpm = null, isRecording = true, isPolling = true,
                autoRecordEnabled = true, engineOffSinceMs = offSince, nowMs = now,
                sessionAgeMs = 30_000L
            )
        )
        assertEquals(
            AutoRecordPolicy.Decision.STOP_RECORDING,
            AutoRecordPolicy.decide(
                rpm = null, isRecording = true, isPolling = true,
                autoRecordEnabled = true, engineOffSinceMs = offSince, nowMs = now,
                sessionAgeMs = AutoRecordPolicy.MIN_SESSION_MS + 1_000L
            )
        )
        // Callers that pass no age keep the historical verdict.
        assertEquals(
            AutoRecordPolicy.Decision.STOP_RECORDING,
            AutoRecordPolicy.decide(
                rpm = null, isRecording = true, isPolling = true,
                autoRecordEnabled = true, engineOffSinceMs = offSince, nowMs = now
            )
        )
    }
}
