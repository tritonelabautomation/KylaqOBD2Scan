package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.RawLogRecovery
import com.example.data.CsvExporter
import com.example.data.MemoryGuard
import com.example.data.RawLogManager
import com.example.data.RecordingManager
import com.example.data.db.TripRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The 2026-11-16 heap fix, verified on the two paths that actually died.
 *
 * The owner's phone reported `Failed to allocate a 56 byte allocation ... target footprint
 * 536870912` and a `GcWatcher.finalize() timed out after 10 seconds`. Neither stack named a bug in
 * one function - they named a heap that had been full of long-lived objects for a long time. The
 * two biggest contributors on the recovery path were:
 *
 *  * `ZipImporter` doing `JSONObject(jsonFile.readText())` - the whole drive as text, then as a
 *    tree, then as records;
 *  * `recoverFromRawLog` building a `List<Telemetry>`, then a `TransactionRecord` per row, then a
 *    `SynchronizedSample` per row - three object graphs for one drive.
 *
 * Both are now streamed. These tests prove the streamed versions produce the SAME output as the
 * collecting ones - a memory fix that quietly changes the recovered trip would be a data-loss bug
 * wearing a fix's clothes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamedRecoveryTest {

    private lateinit var context: Context
    private lateinit var rawLogDir: File

    private val ist: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MemoryGuard.resetForTests()
        rawLogDir = File(context.filesDir, "stream_raw_logs_${System.nanoTime()}").apply { mkdirs() }
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun istMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int = 0): Long =
        java.util.Calendar.getInstance(ist).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
            set(java.util.Calendar.MILLISECOND, ms)
        }.timeInMillis

    private fun istWall(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = ist }
            .format(Date(millis))

    /** A realistic raw log: rpm + speed + coolant + voltage, dated, one frame per 130 ms. */
    private fun writeRawLog(name: String, driveDay: Long, polls: Int): File {
        // Every frame is a complete single-frame Mode-01 answer: PCI, 41, PID, data.
        // ("7E9041420D62" was a typo for the voltage frame - 11 hex characters cannot be
        // byte-aligned, so a strict parser correctly refuses it. Fixed here rather than
        // loosening the parser: the parser is right and the fixture was wrong.)
        val frames = listOf(
            "7E8" + "04" + "41" + "0C" + "0F28",   // 41 0C 0F 28 -> 970 rpm
            "7E8 " + "04" + "41" + "0D" + "50",    // 41 0D 50    -> 80 km/h  (separated id form)
            "7E8" + "04" + "41" + "05" + "59",     // 41 05 59    -> 49 C
            "7E9" + "04" + "41" + "42" + "0D62"    // 41 42 0D 62 -> 13.77 V
        )
        val file = File(rawLogDir, name)
        val sb = StringBuilder()
        var t = driveDay
        for (p in 0 until polls) {
            for (frame in frames) {
                sb.append(istWall(t)).append(" RX < ").append(frame).append('\n')
                t += 130L
            }
        }
        file.writeText(sb.toString())
        return file
    }

    @Test
    fun theStreamingParserKeepsExactlyTheRowsTheCollectingOneKeeps() {
        val day = istMillis(2026, 11, 16, 20, 30, 0)
        val file = writeRawLog("raw_log_stream01.txt", day, polls = 20)

        val collected = RawLogRecovery.extractTelemetry(file, tz = ist)
        val streamed = mutableListOf<RawLogRecovery.Telemetry>()
        val count = RawLogRecovery.forEachTelemetry(file, tz = ist) { streamed.add(it) }

        // Same count, same rows, same order - the memory fix must be invisible in the output.
        assertEquals(collected.size, count)
        assertEquals(collected.size, streamed.size)
        assertEquals(collected, streamed)
        assertTrue("the fixture must actually parse: ${collected.size}", collected.isNotEmpty())
    }

    @Test
    fun aStreamedRawLogRecoveryStillProducesANormalTrip() = runBlocking {
        val day = istMillis(2026, 11, 16, 20, 30, 0)
        val file = writeRawLog("raw_log_stream02.txt", day, polls = 25)

        val manager = RecordingManager(context, RawLogManager(rawLogDir), TripRepository(context))
        val outcome = manager.recoverFromRawLog(file)
        assertTrue(
            "expected a recovered trip, got $outcome",
            outcome is RecordingManager.RecoveryOutcome.Recovered
        )
        val sessionId = (outcome as RecordingManager.RecoveryOutcome.Recovered).sessionId

        // The trip exists as a real recording: session dir with all four artifacts + Room rows.
        val sessionDir = File(manager.recordingsDir, "session_$sessionId")
        assertTrue(File(sessionDir, "$sessionId.json").exists())
        val txCsv = File(sessionDir, "${sessionId}_transactions.csv")
        assertTrue(txCsv.exists())
        assertTrue(File(sessionDir, "${sessionId}_samples.csv").exists())

        // The transactions file must carry the drive, and it must be readable back through the
        // SAME streaming reader the import path uses.
        val rows = CsvExporter.readTransactionsFromCsv(txCsv)
        // 25 polls x 4 frames. If a fixture frame is malformed the parser drops it and this number
        // falls - which is exactly how the bad voltage fixture above was caught.
        assertEquals("a frame in the fixture stopped parsing", 100, rows.size)
        assertEquals("0C", rows[0].pid)
        assertTrue("RPM must decode: ${rows[0].decodedValue}", (rows[0].decodedValue ?: 0.0) > 500.0)

        // Room got samples too, so the trip renders curves/fuel/X-ray like a stopped one.
        val repo = TripRepository(context)
        assertTrue(repo.getSampleCountForTrip(sessionId) > 0)
        val trip = repo.getTripById(sessionId)
        assertNotNull(trip)
        assertEquals("COMPLETED", trip!!.status)

        // The temporary working directory must be gone - otherwise every recovered drive would
        // cost double the disk.
        assertTrue(
            "recovery scratch dir left behind",
            manager.recordingsDir.listFiles()?.none { it.name.startsWith(".recover_") } ?: true
        )
    }

    @Test
    fun aRawLogWithNoTelemetryIsStillReportedAsNothingToRecover() = runBlocking {
        val file = File(rawLogDir, "raw_log_stream03.txt")
        file.writeText(
            (0 until 20).joinToString("\n") { "2026-11-16 20:30:0${it % 10}.000 TX > 7E0 02010C" } + "\n"
        )

        val manager = RecordingManager(context, RawLogManager(rawLogDir), TripRepository(context))
        val outcome = manager.recoverFromRawLog(file)
        // TX-only lines are requests, not telemetry: no trip may be invented from them.
        assertTrue("expected NothingToRecover, got $outcome",
            outcome is RecordingManager.RecoveryOutcome.NothingToRecover)
        // And no scratch directory survives the early return.
        assertTrue(
            manager.recordingsDir.listFiles()?.none { it.name.startsWith(".recover_") } ?: true
        )
    }
}
