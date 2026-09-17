package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.RawLogManager
import com.example.data.RecordTime
import com.example.data.RecordingManager
import com.example.data.db.TripRepository
import com.example.model.Direction
import com.example.model.ResponseStatus
import com.example.model.TransactionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's exact failure, end to end.
 *
 * 2026-09-17: *"again today logs not saved unable to recover it why the hell kylaq TSI coach app is
 * killed in background it supposed to be service right even in background all ways it should run
 * and record it never ever loose the logs."*
 *
 * Before this change a drive lived in two RAM lists and wrote its first byte inside
 * `stopRecording()`, so a kill lost all of it and recovery had to re-parse raw hex from a file
 * whose date had to be guessed. These tests run the real [RecordingManager] - real files, real Room
 * - and kill it the way Android does: by dropping it on the floor and constructing a fresh one over
 * the same data directory, which is exactly what a restarted process sees.
 *
 *  1. a killed session is rebuilt automatically, with no banner and no tap;
 *  2. the rebuilt trip is a NORMAL trip - Room row COMPLETED, telemetry rows, all four files;
 *  3. every recovered timestamp is IST, per the same day's mandate;
 *  4. restarting a recording no longer throws the previous session away;
 *  5. once recovered, it is not recovered twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KilledSessionRecoveryTest {

    private lateinit var context: Context
    private lateinit var rawLogDir: java.io.File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rawLogDir = java.io.File(context.filesDir, "raw_logs").apply { mkdirs() }
        // A clean slate per test: leftover journals from another test would be "recovered" here.
        java.io.File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun newManager(): RecordingManager =
        RecordingManager(context, RawLogManager(rawLogDir), TripRepository(context))

    /**
     * Frames from the owner's own raw log: `41 0C 0F 28` = 970 rpm, `41 0D 50` = 80 km/h,
     * `41 05 59` = 49 °C coolant, `41 42 0D 62` = 13.77 V.
     */
    private fun realFrames(startMs: Long): List<TransactionRecord> {
        val shapes = listOf(
            Triple("0C", "410C0F28", 970.0),
            Triple("0D", "410D50", 80.0),
            Triple("05", "410559", 49.0),
            Triple("42", "41420D62", 13.77)
        )
        val out = mutableListOf<TransactionRecord>()
        for (poll in 0 until 5) {
            for ((pid, frame, value) in shapes) {
                val t = startMs + poll * 520L + out.size * 130L
                out += TransactionRecord(
                    timestampUtc = RecordTime.stamp(t),
                    timestampMonotonic = t,
                    direction = Direction.RX,
                    canRxId = if (pid == "42") "7E9" else "7E8",
                    requestHex = "01$pid",
                    responseHex = frame,
                    service = "01",
                    pid = pid,
                    rawPayload = frame.removePrefix("41$pid"),
                    decodedParameter = when (pid) {
                        "0C" -> "Engine RPM"
                        "0D" -> "Vehicle speed"
                        "05" -> "Coolant"
                        else -> "Control module voltage"
                    },
                    decodedValue = value,
                    decodedValueDisplay = "$value",
                    unit = when (pid) {
                        "0C" -> "rpm"
                        "0D" -> "km/h"
                        "05" -> "°C"
                        else -> "V"
                    },
                    responseStatus = ResponseStatus.OK
                )
            }
        }
        return out
    }

    @Test
    fun aKilledDriveIsRebuiltAutomaticallyIntoANormalTrip() = runBlocking {
        val startMs = System.currentTimeMillis() - 20 * 60_000L
        val frames = realFrames(startMs)

        // ── the drive, recorded live ──
        val killed = newManager()
        val meta = killed.startRecording()!!
        frames.forEach { killed.recordTransaction(it) }
        // No stopRecording(). The process is gone: drop it and build a fresh manager over the same
        // data directory, which is all a restarted process gets.

        // ── what the owner had to do before: open Trips & Recordings and tap a banner. Now: ──
        val restarted = newManager()
        val summary = restarted.recoverUnfinishedSessions()

        assertNotNull("a killed session must be recovered without any tap", summary)
        assertEquals(1, summary!!.recoveredSessions)
        assertEquals(frames.size, summary.recoveredTransactions)
        assertEquals(meta.sessionId, summary.sessionIds.single())
        assertTrue(summary.lostSessions.isEmpty())
        assertTrue(summary.notice().contains("Recovered 1 killed session"))

        // ── it is a NORMAL trip, not a corpse ──
        val trip = TripRepository(context).getTripById(meta.sessionId)
        assertNotNull("the recovered trip must be in Room", trip)
        assertEquals("COMPLETED", trip!!.status)
        assertEquals(frames.size, trip.sampleCount)
        assertEquals(970.0, trip.maxRpm, 0.001)
        assertEquals(80.0, trip.maxSpeedKmh, 0.001)
        assertEquals(49.0, trip.maxCoolantC, 0.001)
        assertTrue("duration must come from the real frames, not a default", trip.durationSeconds > 0)
        assertTrue("both answering ECUs must survive recovery: ${trip.detectedEcus}",
            trip.detectedEcus.contains("7E8") && trip.detectedEcus.contains("7E9"))

        val samples = TripRepository(context).getSamplesForTrip(meta.sessionId)
        assertEquals(frames.size, samples.size)

        // ── and the files a stopped trip would have ──
        val dir = java.io.File(context.filesDir, "recordings/session_${meta.sessionId}")
        assertTrue(dir.isDirectory)
        for (name in listOf(
            "${meta.sessionId}_transactions.csv",
            "${meta.sessionId}_samples.csv",
            "${meta.sessionId}.json",
            "${meta.sessionId}_bundle.zip"
        )) {
            val f = java.io.File(dir, name)
            assertTrue("$name must exist", f.exists())
            assertTrue("$name must not be empty", f.length() > 0L)
        }
        // The samples CSV is no longer the empty file raw-log recovery used to write: a recovered
        // trip has curves, fuel trend and X-ray wide rows like any other.
        val sampleLines = java.io.File(dir, "${meta.sessionId}_samples.csv").readLines()
        assertEquals(frames.size + 1, sampleLines.size)

        // ── IST, per the same day's mandate ──
        val txLines = java.io.File(dir, "${meta.sessionId}_transactions.csv").readLines()
        assertTrue("recovered rows must be stamped IST: ${txLines[1]}", txLines[1].contains("+05:30"))
        assertTrue(trip.startTimeUtc.endsWith("+05:30"))
        val endStamp = trip.endTimeUtc!!
        assertTrue(endStamp.endsWith("+05:30"))
        // The trip window is the DATA window: the Room row's start/end epochs come from the first
        // and last journaled frame, not from the moment the session was opened, so a recovered
        // duration is the drive and not the drive plus the connect-to-first-response gap.
        assertEquals(frames.first().timestampMonotonic, trip.startTimestamp)
        assertEquals(frames.last().timestampMonotonic, trip.endTimestamp!!)
        assertEquals(
            (frames.last().timestampMonotonic - frames.first().timestampMonotonic) / 1000L,
            trip.durationSeconds
        )
    }

    @Test
    fun aRecoveredSessionIsNeverRecoveredTwice() = runBlocking {
        val frames = realFrames(System.currentTimeMillis() - 60_000L)
        val killed = newManager()
        val meta = killed.startRecording()!!
        frames.forEach { killed.recordTransaction(it) }

        val restarted = newManager()
        assertNotNull(restarted.recoverUnfinishedSessions())
        // Second launch: nothing pending, nothing announced. A notice every launch would train the
        // owner to ignore the one that matters.
        val again = newManager().recoverUnfinishedSessions()
        assertTrue("a finished recovery must stay finished: $again", again == null)
        // And the trip was not duplicated or padded by the second pass.
        assertEquals(frames.size, TripRepository(context).getSamplesForTrip(meta.sessionId).size)
    }

    @Test
    fun aSecondStartCannotReplaceOrShredTheDriveAlreadyRunning() = runBlocking {
        // startRecording() used to call clear() on both RAM lists unconditionally, so a second
        // START - auto-reconnect, screen re-entry, a retry after a dropped link - silently deleted
        // the drive in progress. Two supervisors now run the same auto-record rule (the UI one and
        // the one inside the keep-alive service), so both can see "engine on, nothing recording" on
        // the same tick; without a guard that cuts one city drive into two trips.
        val frames = realFrames(System.currentTimeMillis() - 120_000L)
        val manager = newManager()
        val first = manager.startRecording()!!
        frames.forEach { manager.recordTransaction(it) }

        // startRecording() now REFUSES to open a second live session and hands back the one already
        // running - two supervisors (UI + service) can both see "engine on, nothing recording" on the
        // same tick, and a second START used to orphan-save the first drive and shred it in two.
        val second = manager.startRecording()!!
        assertEquals("a live session must not be replaced", first.sessionId, second.sessionId)

        // A STOP still closes the drive, and a later START opens a genuinely new one.
        manager.stopRecording()
        val third = manager.startRecording()!!
        assertTrue("after a stop, a NEW session opens", third.sessionId != first.sessionId)

        // stopRecording() writes the files and the Room rows itself, so what the wait covers is the
        // transition to COMPLETED carrying the right row count.
        val repo = TripRepository(context)
        val deadline = System.currentTimeMillis() + 10_000L
        var recoveredTrip = repo.getTripById(first.sessionId)
        while (System.currentTimeMillis() < deadline &&
            !(recoveredTrip?.status == "COMPLETED" && recoveredTrip?.sampleCount == frames.size)
        ) {
            kotlinx.coroutines.delay(50)
            recoveredTrip = repo.getTripById(first.sessionId)
        }
        assertNotNull("the interrupted session must still be saved", recoveredTrip)
        assertEquals("COMPLETED", recoveredTrip!!.status)
        assertEquals(frames.size, recoveredTrip.sampleCount)
        assertEquals(frames.size, repo.getSamplesForTrip(first.sessionId).size)
    }

    @Test
    fun anInterruptedSessionStillInRamIsPersistedRatherThanCleared() = runBlocking {
        // The orphan path itself: a session left in RAM without a clean STOP is written out by the
        // same finalization every other caller uses. Reachable when stopRecording() is abandoned
        // part-way - its scope cancelled mid-finalization - which is one of the ways the old code
        // lost a drive that had already been recorded perfectly.
        val startMs = System.currentTimeMillis() - 90_000L
        val frames = realFrames(startMs)
        val manager = newManager()
        val meta = manager.startRecording()!!
        frames.forEach { manager.recordTransaction(it) }

        val saved = manager.finalizeSession(
            metadata = meta,
            txList = frames,
            sampleList = emptyList(),
            rawLogFile = null,
            recovered = false
        )
        assertNotNull("an interrupted session must be persisted, not dropped", saved)
        assertEquals(frames.size, saved!!.transactionCount)

        val repo = TripRepository(context)
        val trip = repo.getTripById(meta.sessionId)
        assertNotNull(trip)
        assertEquals("COMPLETED", trip!!.status)
        assertEquals(frames.size, trip.sampleCount)
        // The samples CSV was empty here, so the wide rows are replayed from the transactions -
        // a trip is never saved with transactions but no curves.
        assertEquals(frames.size, repo.getSamplesForTrip(meta.sessionId).size)
    }

    @Test
    fun aCleanStopLeavesNothingForRecoveryToFind() = runBlocking {
        val frames = realFrames(System.currentTimeMillis() - 60_000L)
        val manager = newManager()
        manager.startRecording()
        frames.forEach { manager.recordTransaction(it) }
        val saved = manager.stopRecording()
        assertNotNull(saved)

        val summary = newManager().recoverUnfinishedSessions()
        assertTrue("a stopped trip must not be rebuilt a second time: $summary", summary == null)
    }

    @Test
    fun aSessionWithNoEcuResponsesProducesNoInventedTrip() = runBlocking {
        // Ignition off, adapter asleep: the journal has a header and nothing else. Recovery must
        // report nothing rather than create a zero-line trip that looks like a real drive.
        val manager = newManager()
        manager.startRecording()
        val summary = newManager().recoverUnfinishedSessions()
        assertTrue("an empty session must not become a trip: $summary",
            summary == null || summary.recoveredSessions == 0)
    }

    @Test
    fun everyRecoveredTimestampRoundTripsToTheInstantItWasRecorded() = runBlocking {
        val startMs = System.currentTimeMillis() - 30 * 60_000L
        val frames = realFrames(startMs)
        val killed = newManager()
        val meta = killed.startRecording()!!
        frames.forEach { killed.recordTransaction(it) }

        newManager().recoverUnfinishedSessions()

        val rows = TripRepository(context).getSamplesForTrip(meta.sessionId)
        assertEquals(frames.size, rows.size)
        for (i in frames.indices) {
            assertEquals(
                "row $i must keep its own instant, not the recovery time",
                frames[i].timestampMonotonic,
                rows[i].timestamp
            )
            assertEquals(frames[i].timestampMonotonic, RecordTime.parseMillis(rows[i].timestampUtc))
        }
    }
}
