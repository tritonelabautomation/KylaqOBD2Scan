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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "After trip when click stop trip is not saved always on recover mode only why the hell"
 * (owner 2026-09-22, with a 1 h 42 min / 31 km drive on the cluster and a trip list full of
 * "(recovered)" entries).
 *
 * A STOP that dies mid-finalization writes no trip, and by design leaves the journal unfinished
 * so recovery can rebuild the drive - which is why every one of his drives came back as
 * "Recovered Run" instead of as the trip he stopped. Two things made the death window huge on a
 * long drive, and both are fixed and pinned here:
 *
 *  1. stopRecording parsed the ENTIRE crash journal (transactions and wide rows) on every stop,
 *     just to compare its row count with the RAM list - two extra full copies of a 60 000-row
 *     drive materialised inside finalization's own peak. The comparison is now a streaming line
 *     count, and the journal is parsed only when it genuinely knows more rows than RAM.
 *  2. the whole trip's Room samples went in as ONE transaction - a rollback segment the size of
 *     the drive on phone flash. [TripRepository.insertSamples] now writes 2 000-row chunks, so
 *     this test's 2 500 rows cross the chunk boundary on purpose.
 *
 * The second test pins the banner bug in the same screenshot: the session being recorded RIGHT
 * NOW has a raw log on disk by design, and it used to be offered as "1 unsaved log session" with
 * a Recover button while the drive was still running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopSavesTheTripTest {

    private lateinit var context: Context
    private lateinit var rawLogDir: File
    private lateinit var recordingsDir: File
    private lateinit var rawLog: RawLogManager

    private val day1 = RecordTime.parseMillis("2026-09-22T07:32:00.000+05:30")!!

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rawLogDir = File(context.filesDir, "raw_logs")
        recordingsDir = File(context.filesDir, "recordings")
        recordingsDir.deleteRecursively()
        rawLogDir.deleteRecursively()
        rawLogDir.mkdirs()
        File(context.filesDir, "merged_sessions.log").delete()
        rawLog = RawLogManager(rawLogDir)
    }

    private fun newManager(): RecordingManager =
        RecordingManager(context, rawLog, TripRepository(context))

    private fun tx(pid: String, frame: String, value: Double, unit: String, t: Long) =
        TransactionRecord(
            timestampUtc = RecordTime.stamp(t),
            timestampMonotonic = t,
            direction = Direction.RX,
            canRxId = "7E8",
            requestHex = "01$pid",
            responseHex = frame,
            service = "01",
            pid = pid,
            rawPayload = frame.removePrefix("41$pid"),
            decodedParameter = if (pid == "0C") "Engine RPM" else "Vehicle speed",
            decodedValue = value,
            decodedValueDisplay = "$value $unit",
            unit = unit,
            responseStatus = ResponseStatus.OK
        )

    @Test
    fun stoppingALongDriveSavesItAsANormalTripNotAsSomethingToRecover() = runBlocking {
        val manager = newManager()
        val meta = manager.startRecording()!!
        // 2 500 rows: past the Room insert chunk boundary (2 000), and big enough that the old
        // parse-the-journal-to-compare-it stop path was doing megabytes of pointless work here.
        val frames = (0 until 2500).map { i ->
            if (i % 2 == 0) tx("0C", "410C0F28", 970.0, "rpm", day1 + i * 130L)
            else tx("0D", "410D50", 80.0, "km/h", day1 + i * 130L)
        }
        frames.forEach { manager.recordTransaction(it) }

        val saved = manager.stopRecording()
        assertNotNull("STOP must save the trip, not hand it to recovery", saved)
        val id = saved!!.metadata.sessionId
        assertEquals(2500, saved.transactionCount)
        assertFalse(
            "a stopped trip must not wear the recovered badge: ${saved.metadata.adapter}",
            saved.metadata.adapter.contains("recovered")
        )
        assertFalse(
            "and must not be NAMED like a resurrection: ${saved.metadata.sessionName}",
            saved.metadata.sessionName.contains("Recovered", ignoreCase = true)
        )

        // The database holds the whole drive, in chunks that all landed.
        val trip = TripRepository(context).getTripById(id)
        assertNotNull(trip)
        assertEquals("COMPLETED", trip!!.status)
        assertEquals(2500, TripRepository(context).getSamplesForTrip(id).size)
        assertTrue(File(recordingsDir, "session_$id/$id.json").isFile)
        assertTrue(File(recordingsDir, "session_$id/${id}_transactions.csv").isFile)

        // A FRESH PROCESS finds a saved trip and nothing to rebuild - the owner's exact complaint
        // was that this moment always produced a "Recovered Run" instead.
        val restarted = newManager()
        restarted.loadSavedRecordings()
        assertNull(
            "nothing may be waiting for recovery after a clean stop: ${restarted.recoverUnfinishedSessions()?.notice()}",
            restarted.recoverUnfinishedSessions()
        )
        assertTrue(restarted.findUnsavedRawLogs().isEmpty())
        assertEquals(listOf(id), restarted.savedRecordings.value.map { it.metadata.sessionId })
    }

    @Test
    fun theDriveInProgressIsNeverOfferedAsAnUnsavedCorpse() = runBlocking {
        val manager = newManager()
        val meta = manager.startRecording()!!
        repeat(20) { i ->
            manager.recordTransaction(tx("0C", "410C0F28", 970.0, "rpm", day1 + i * 130L))
        }
        val live = File(rawLogDir, "raw_log_${meta.sessionId}.txt")
        assertTrue("the fixture must be writing the live raw log", live.length() > 64L)
        assertTrue(
            "a drive in progress is not a corpse: ${manager.findUnsavedRawLogs().map { it.name }}",
            manager.findUnsavedRawLogs().none { it.name == live.name }
        )

        manager.stopRecording()
        assertTrue(
            "and once saved it is a saved trip's own log, still not a corpse",
            manager.findUnsavedRawLogs().none { it.name == live.name }
        )
    }
}
