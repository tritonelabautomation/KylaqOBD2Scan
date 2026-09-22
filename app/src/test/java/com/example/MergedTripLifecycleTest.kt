package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CsvExporter
import com.example.data.JsonExporter
import com.example.data.RawLogManager
import com.example.data.RecordTime
import com.example.data.RecordingManager
import com.example.data.db.TripRepository
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import com.example.data.db.entities.instantMs
import com.example.model.Direction
import com.example.model.RecordingMetadata
import com.example.model.ResponseStatus
import com.example.model.SynchronizedSample
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
 * Merging, end to end, on real files and a real Room database.
 *
 * Owner 2026-09-22: *"When I merge fragmented same trip it's showing as recovered and merging
 * multiple times man do a proper review while merging. If more than 40 min difference between
 * merging files ask user to confirm. For different dates ask user confirmation are you sure. Once
 * it's merged properly no need to keep old fragmented trips for recovered logs immediately after
 * merged auto backup."*
 *
 * The loop he described was mechanical. A merge deleted the fragments' session directories, their
 * Room rows and their crash journals - but their raw ELM logs stayed in `files/raw_logs`, and
 * recovery decides what to rebuild with exactly one question: *is this session id already a saved
 * trip?* After a merge the answer is no, because the merged trip has a NEW id. So the next launch
 * rebuilt every fragment as a fresh "Recovered Run" and handed him the same pieces back to merge
 * again, each pass folding another copy of the drive into the trip.
 *
 * These tests drive the real [RecordingManager] and assert the whole contract:
 *  1. a clean same-drive stitch still happens in ONE tap - a review must not become a nag;
 *  2. a gap over 40 minutes, and pieces on different dates, stop and ask, and write nothing until
 *     the owner agrees;
 *  3. after a merge the fragments are gone from the trip list, from Room, from `recordings/` AND
 *     from `files/raw_logs`, so a fresh process recovers nothing and re-merging is refused;
 *  4. the fragments' raw logs are folded into the merged bundle - removed from the scan directory,
 *     never removed from the trip;
 *  5. the merged trip keeps the elevation its pieces recorded, including pieces whose transactions
 *     CSV predates the `altitude_m` column, and reports the tank percentage at both ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergedTripLifecycleTest {

    private lateinit var context: Context
    private lateinit var rawLogDir: File
    private lateinit var recordingsDir: File

    /**
     * The ONE raw-log writer, shared with the manager under test. `RecordingManager` opens and
     * closes the session log through the instance it was built with, so a second instance over the
     * same directory would have no open writer and would silently log nothing - the fixture would
     * pass while testing an empty file.
     */
    private lateinit var rawLog: RawLogManager

    /** 2026-09-21 08:00 IST - the morning of the owner's shredded 31 km drive. */
    private val day1 = RecordTime.parseMillis("2026-09-21T08:00:00.000+05:30")!!
    private val day2 = RecordTime.parseMillis("2026-09-22T09:00:00.000+05:30")!!

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rawLogDir = File(context.filesDir, "raw_logs")
        recordingsDir = File(context.filesDir, "recordings")
        // A clean slate per test: a leftover journal, raw log or tombstone from another test would
        // be "recovered" or "refused" here and the assertion would be about the wrong thing.
        recordingsDir.deleteRecursively()
        rawLogDir.deleteRecursively()
        rawLogDir.mkdirs()
        File(context.filesDir, "merged_sessions.log").delete()
        rawLog = RawLogManager(rawLogDir)
    }

    private fun newManager(): RecordingManager =
        RecordingManager(context, rawLog, TripRepository(context))

    /**
     * One OBD answer, in the shape the owner's car produces: `41 0C 0F 28` = 970 rpm,
     * `41 0D 50` = 80 km/h, `41 2F C7` = tank level.
     */
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
            decodedParameter = when (pid) {
                "0C" -> "Engine RPM"
                "0D" -> "Vehicle speed"
                "2F" -> "Fuel tank level input"
                else -> "PID $pid"
            },
            decodedValue = value,
            decodedValueDisplay = "$value $unit",
            unit = unit,
            responseStatus = ResponseStatus.OK
        )

    /** A fragment's worth of frames: a tank reading, rpm/speed polls, a closing tank reading. */
    private fun frames(
        startMs: Long,
        polls: Int,
        startLevelPct: Double,
        endLevelPct: Double
    ): List<TransactionRecord> {
        val out = mutableListOf<TransactionRecord>()
        var t = startMs
        out += tx("2F", "412F${levelByte(startLevelPct)}", startLevelPct, "%", t)
        t += 130
        repeat(polls) {
            out += tx("0C", "410C0F28", 970.0, "rpm", t)
            t += 130
            out += tx("0D", "410D50", 80.0, "km/h", t)
            t += 130
        }
        out += tx("2F", "412F${levelByte(endLevelPct)}", endLevelPct, "%", t)
        return out
    }

    /** The payload byte that decodes to this percentage (`A*100/255`). */
    private fun levelByte(pct: Double) =
        String.format(java.util.Locale.US, "%02X", (pct * 255.0 / 100.0).toInt().coerceIn(0, 255))

    /** What the transport puts on the wire: the PCI byte, a space, then the frame. */
    private fun pciLine(frame: String) =
        String.format(java.util.Locale.US, "%02X %s", frame.length / 2, frame).uppercase(java.util.Locale.US)

    /**
     * Records one fragment the way the app does - journal, wide rows, raw ELM log - and stops it
     * cleanly, so it is a normal saved trip with a real `raw_logs/raw_log_<id>.txt` beside it.
     * That file is the one recovery used to resurrect the fragment after a merge.
     */
    private suspend fun recordFragment(
        manager: RecordingManager,
        startMs: Long,
        polls: Int = 8,
        startLevelPct: Double = 78.4,
        endLevelPct: Double = 61.2
    ): String {
        val meta = manager.startRecording()!!
        frames(startMs, polls, startLevelPct, endLevelPct).forEach { r ->
            manager.recordTransaction(r)
            // What the transport puts on the wire. `RawLogManager` writes
            // `yyyy-MM-dd HH:mm:ss.SSS RX < <canId> <pci> <frame>` - the dated shape recovery reads.
            rawLog.onRawLog(
                timestampUtc = r.timestampUtc,
                timestampMonotonic = 0L,
                isTx = false,
                canId = "7E8",
                rawText = pciLine(r.responseHex),
                status = "OK"
            )
        }
        manager.stopRecording()
        return meta.sessionId
    }

    // ── 1. a clean stitch of one drive stays one tap ──────────────────────────────────

    @Test
    fun twoFragmentsOfOneDriveMergeWithoutBeingAsked() = runBlocking {
        val manager = newManager()
        val a = recordFragment(manager, day1)
        // Cut off for a couple of minutes by a process death, then resumed: one drive, two trips.
        val b = recordFragment(manager, day1 + 20 * 60_000L)

        manager.loadSavedRecordings()
        val review = manager.planMerge(listOf(a, b))
        assertTrue("two fragments of one drive must be mergeable: ${review.headline()}", review.mergeable)
        assertFalse("and must not stop to ask: ${review.bullets()}", review.needsConfirmation)
        assertEquals(listOf("2026-09-21"), review.dates)

        val result = manager.mergeSessions(listOf(a, b))
        assertTrue("expected Merged, got $result", result is RecordingManager.MergeResult.Merged)
        val merged = (result as RecordingManager.MergeResult.Merged).saved
        assertEquals(review.totalTransactions, merged.transactionCount)
        assertTrue(merged.metadata.sessionName.startsWith("Merged Run 2026-09-21"))
        assertTrue(merged.metadata.adapter.contains("merged 2 trips"))
    }

    // ── 2. the two confirmations the owner asked for ──────────────────────────────────

    @Test
    fun aGapOverFortyMinutesAsksBeforeWritingAnything() = runBlocking {
        val manager = newManager()
        val a = recordFragment(manager, day1)
        // An hour of silence: two errands, not one drive cut in two.
        val b = recordFragment(manager, day1 + 80 * 60_000L)
        manager.loadSavedRecordings()
        val before = manager.savedRecordings.value.size

        val review = manager.planMerge(listOf(a, b))
        assertTrue(review.mergeable)
        assertTrue("a 60 min gap must be flagged", review.needsConfirmation)
        assertEquals(1, review.confirmGaps.size)

        val asked = manager.mergeSessions(listOf(a, b))
        assertTrue("expected NeedsConfirmation, got $asked", asked is RecordingManager.MergeResult.NeedsConfirmation)
        assertEquals("nothing may be written before the owner agrees", before, manager.savedRecordings.value.size)
        assertNotNull("both fragments must still exist", TripRepository(context).getTripById(a))
        assertNotNull(TripRepository(context).getTripById(b))

        // He reads it and says yes: the SAME selection now merges.
        val confirmed = manager.mergeSessions(listOf(a, b), confirmed = true)
        assertTrue("expected Merged after confirmation, got $confirmed", confirmed is RecordingManager.MergeResult.Merged)
        assertNull("the fragments are gone once he has agreed", TripRepository(context).getTripById(a))
    }

    @Test
    fun fragmentsOnDifferentDatesAskAreYouSure() = runBlocking {
        val manager = newManager()
        val monday = recordFragment(manager, day1)
        val tuesday = recordFragment(manager, day2)
        manager.loadSavedRecordings()

        val review = manager.planMerge(listOf(monday, tuesday))
        assertTrue(review.spansMultipleDates)
        assertEquals(listOf("2026-09-21", "2026-09-22"), review.dates)
        assertTrue(review.needsConfirmation)
        assertTrue("the question must be the one he asked for: ${review.headline()}", review.headline().contains("different dates"))

        val asked = manager.mergeSessions(listOf(monday, tuesday))
        assertTrue(asked is RecordingManager.MergeResult.NeedsConfirmation)
        assertNotNull(TripRepository(context).getTripById(monday))
        assertNotNull(TripRepository(context).getTripById(tuesday))

        val confirmed = manager.mergeSessions(listOf(monday, tuesday), confirmed = true)
        assertTrue(confirmed is RecordingManager.MergeResult.Merged)
        assertEquals(2, (confirmed as RecordingManager.MergeResult.Merged).review.dates.size)
    }

    @Test
    fun anUnmergeableSelectionIsRefusedEvenWhenConfirmed() = runBlocking {
        val manager = newManager()
        val a = recordFragment(manager, day1)
        manager.loadSavedRecordings()

        val one = manager.mergeSessions(listOf(a))
        assertTrue(one is RecordingManager.MergeResult.Failed)
        assertTrue((one as RecordingManager.MergeResult.Failed).reason.contains("at least 2"))

        val ghost = manager.mergeSessions(listOf(a, "doesnotexist"), confirmed = true)
        assertTrue("confirmation cannot conjure rows: $ghost", ghost is RecordingManager.MergeResult.Failed)
        assertNotNull("and must not have eaten the real trip", TripRepository(context).getTripById(a))
    }

    // ── 3. + 4. the loop is closed: fragments never come back ─────────────────────────

    @Test
    fun mergedFragmentsAreNeverRecoveredAgain() = runBlocking {
        val manager = newManager()
        val a = recordFragment(manager, day1)
        val b = recordFragment(manager, day1 + 20 * 60_000L)

        // Both raw logs are on disk and both hold real telemetry - exactly the state that used to
        // resurrect the fragments on the next launch.
        assertTrue(File(rawLogDir, "raw_log_$a.txt").length() > 64L)
        assertTrue(File(rawLogDir, "raw_log_$b.txt").length() > 64L)

        manager.loadSavedRecordings()
        val merged = (manager.mergeSessions(listOf(a, b)) as RecordingManager.MergeResult.Merged).saved
        val newId = merged.metadata.sessionId

        // ── the fragments are gone everywhere recovery could find them ──
        assertFalse(File(recordingsDir, "session_$a").exists())
        assertFalse(File(recordingsDir, "session_$b").exists())
        assertFalse(
            "the raw log recovery rebuilds from must be out of the scan directory",
            File(rawLogDir, "raw_log_$a.txt").exists()
        )
        assertFalse(File(rawLogDir, "raw_log_$b.txt").exists())
        assertNull(TripRepository(context).getTripById(a))
        assertNull(TripRepository(context).getTripById(b))
        assertTrue(TripRepository(context).getSamplesForTrip(a).isEmpty())

        // ── and tombstoned, so a raw log restored from Drive cannot resurrect them either ──
        assertTrue(manager.mergeLedger.contains(a))
        assertTrue(manager.mergeLedger.contains(b))
        assertEquals(newId, manager.mergeLedger.entries().first { it.mergedAwayId == a }.intoId)

        // ── a FRESH PROCESS: what the next launch actually sees ──
        val restarted = newManager()
        restarted.loadSavedRecordings()
        assertTrue(
            "no fragment may be offered for recovery: ${restarted.findUnsavedRawLogs().map { it.name }}",
            restarted.findUnsavedRawLogs().isEmpty()
        )
        val summary = restarted.recoverUnfinishedSessions()
        assertNull("nothing may be rebuilt after a merge, got ${summary?.notice()}", summary)
        assertEquals(listOf(newId), restarted.savedRecordings.value.map { it.metadata.sessionId })

        // ── merging the same two ids again is refused, not silently doubled ──
        val again = restarted.mergeSessions(listOf(a, b))
        assertTrue("a second merge of consumed fragments must fail: $again", again is RecordingManager.MergeResult.Failed)
        assertEquals(1, restarted.savedRecordings.value.size)
    }

    @Test
    fun aRawLogThatSurvivesTheCleanupIsStillRefusedByTheTombstone() = runBlocking {
        val manager = newManager()
        val a = recordFragment(manager, day1)
        val b = recordFragment(manager, day1 + 20 * 60_000L)
        manager.loadSavedRecordings()
        val merged = (manager.mergeSessions(listOf(a, b)) as RecordingManager.MergeResult.Merged).saved

        // Simulate the cleanup failing - a full disk, a file handle still open, a Drive restore
        // that put the raw log back. The tombstone alone must be enough.
        RawLogManager(rawLogDir).let { fresh ->
            fresh.startFileLogging(a)
            frames(day1, 8, 78.4, 61.2).forEach { r ->
                fresh.onRawLog(
                    r.timestampUtc, 0L, false, "7E8", pciLine(r.responseHex), "OK"
                )
            }
            fresh.stopFileLogging()
        }
        val resurrected = File(rawLogDir, "raw_log_$a.txt")
        assertTrue("the fixture must have written a recoverable raw log", resurrected.length() > 64L)

        val restarted = newManager()
        restarted.loadSavedRecordings()
        assertTrue(
            "a tombstoned id must never be offered again: ${restarted.findUnsavedRawLogs().map { it.name }}",
            restarted.findUnsavedRawLogs().none { it.name == resurrected.name }
        )
        assertNull(restarted.recoverUnfinishedSessions())
        assertEquals(
            "the merged trip must be the only one",
            listOf(merged.metadata.sessionId),
            restarted.savedRecordings.value.map { it.metadata.sessionId }
        )
    }

    @Test
    fun theFragmentsRawLogsAreFoldedIntoTheMergedBundleNotThrownAway() = runBlocking {
        val manager = newManager()
        val a = recordFragment(manager, day1)
        val b = recordFragment(manager, day1 + 20 * 60_000L)
        val aLines = File(rawLogDir, "raw_log_$a.txt").readLines().count { it.contains("RX <") }
        val bLines = File(rawLogDir, "raw_log_$b.txt").readLines().count { it.contains("RX <") }
        assertTrue("the fixture must actually write telemetry", aLines >= 10 && bLines >= 10)

        manager.loadSavedRecordings()
        val merged = (manager.mergeSessions(listOf(a, b)) as RecordingManager.MergeResult.Merged).saved
        val newId = merged.metadata.sessionId

        val folded = File(recordingsDir, "session_$newId/${newId}_raw.txt")
        assertTrue("the merged bundle must carry the fragments' raw logs", folded.isFile)
        val text = folded.readText()
        assertTrue(text.contains("MERGED RAW OBD-II LOG"))
        assertTrue("fragment $a must be named in the folded log", text.contains("FRAGMENT session $a"))
        assertTrue("fragment $b must be named in the folded log", text.contains("FRAGMENT session $b"))
        assertEquals(
            "every telemetry line of both fragments must survive inside the merged trip",
            aLines + bLines,
            text.lines().count { it.contains("RX <") }
        )
        assertNotNull("and the merged trip must expose it", merged.rawLogFile)
        // The working copy used to build it is not left lying in recordings/ - a stray file there
        // is picked up by the backup walk and by nothing else.
        assertFalse(File(recordingsDir, "merge_raw_$newId.txt").exists())
    }

    // ── 5. what the merged trip actually contains ─────────────────────────────────────

    @Test
    fun theMergedTripCarriesTheTankPercentageAtBothEnds() = runBlocking {
        val manager = newManager()
        // 78.4 % when the first fragment started, 55.0 % when the second one ended.
        val a = recordFragment(manager, day1, startLevelPct = 78.4, endLevelPct = 70.0)
        val b = recordFragment(manager, day1 + 20 * 60_000L, startLevelPct = 70.0, endLevelPct = 55.0)

        manager.loadSavedRecordings()
        val merged = (manager.mergeSessions(listOf(a, b)) as RecordingManager.MergeResult.Merged).saved
        val id = merged.metadata.sessionId
        val found = TripRepository(context).getTripById(id)
        assertNotNull("the merged trip must have a Room row", found)
        val trip = found!!
        assertEquals(78.4, trip.startFuelLevelPct!!, 0.001)
        assertEquals(55.0, trip.endFuelLevelPct!!, 0.001)
        assertEquals(78.4, merged.metadata.startFuelLevelPct!!, 0.001)
        assertEquals(55.0, merged.metadata.endFuelLevelPct!!, 0.001)

        // The trip LOG says it too - the session JSON is what a backup carries.
        val meta = File(recordingsDir, "session_$id/$id.json").sessionMeta()
        assertEquals(78.4, meta.getDouble("startFuelLevelPct"), 0.001)
        assertEquals(55.0, meta.getDouble("endFuelLevelPct"), 0.001)

        // And the wide rows carry the level, so the fuel-level trend can be drawn and the pair can
        // be re-derived from the files alone if the database is ever lost.
        val samplesCsv = File(recordingsDir, "session_$id/${id}_samples.csv")
        assertTrue(samplesCsv.readLines().first().endsWith("fuel_level_pct"))
        val levels = CsvExporter.readSamplesFromCsv(samplesCsv).mapNotNull { it.fuelLevelPct }
        assertTrue("the merged samples CSV must carry the tank level: $levels", levels.isNotEmpty())
        assertEquals(78.4, levels.first(), 0.05)
        assertEquals(55.0, levels.last(), 0.05)

        // Derived on read, so a trip whose Room columns predate v14 still reports the pair.
        val rows = TripRepository(context).getSamplesForTrip(id)
        val window = com.example.analysis.TripFuelSummary.fuelLevelWindow(
            rows,
            pid = { it.pid },
            value = { it.numericValue },
            timestamp = { it.instantMs }
        )
        assertEquals(78.4, window.startPct!!, 0.05)
        assertEquals(55.0, window.endPct!!, 0.05)
        assertEquals(-23.4, window.deltaPct!!, 0.1)
    }

    @Test
    fun mergingKeepsTheElevationTheFragmentsRecorded() = runBlocking {
        // Built by hand rather than recorded live: `recordTransaction` stamps altitude from the GPS
        // container, which a JVM test has no fix for. Writing the files directly is also the honest
        // fixture for the owner's real history - trips already on his phone.
        val manager = newManager()
        val a = sessionWithAltitude("aaaa1111", day1, day1 + 10 * 60_000L, 512.0, 540.0, legacyTxCsv = false)
        val b = sessionWithAltitude("bbbb2222", day1 + 12 * 60_000L, day1 + 25 * 60_000L, 540.0, 498.0, legacyTxCsv = false)

        manager.loadSavedRecordings()
        val review = manager.planMerge(listOf(a, b))
        assertFalse("one drive, same date, 2 min apart: ${review.bullets()}", review.needsConfirmation)
        val merged = (manager.mergeSessions(listOf(a, b)) as RecordingManager.MergeResult.Merged).saved

        val trip = TripRepository(context).getTripById(merged.metadata.sessionId)!!
        assertEquals(498.0, trip.minAltitudeM!!, 0.001)
        assertEquals(540.0, trip.maxAltitudeM!!, 0.001)

        val rows = TripRepository(context).getSamplesForTrip(merged.metadata.sessionId)
        assertTrue("every merged row must keep its elevation", rows.all { it.altitudeM != null })
        assertTrue(
            "the merged transactions CSV must carry the altitude column: ${merged.transactionCsvFile.readLines()[0]}",
            merged.transactionCsvFile.readLines()[0].endsWith("altitude_m")
        )
        assertTrue(merged.transactionCsvFile.readLines()[1].endsWith("512.0"))
    }

    @Test
    fun mergingRecoversElevationFromAFragmentWhoseCsvPredatesTheAltitudeColumn() = runBlocking {
        // The owner's existing trips: a transactions CSV with 15 columns (no `altitude_m`) beside a
        // samples CSV that DOES carry elevation, and Room rows that carry it too. Merging must not
        // flatten the drive to "-- m" just because the older schema could not hold it.
        val manager = newManager()
        val a = sessionWithAltitude("cccc3333", day1, day1 + 10 * 60_000L, 600.0, 655.0, legacyTxCsv = true)
        val b = sessionWithAltitude("dddd4444", day1 + 12 * 60_000L, day1 + 25 * 60_000L, 655.0, 610.0, legacyTxCsv = true)

        // Prove the fixture really is the legacy shape.
        val legacyHeader = File(recordingsDir, "session_$a/${a}_transactions.csv").readLines().first()
        assertFalse(legacyHeader.contains("altitude_m"))

        manager.loadSavedRecordings()
        val merged = (manager.mergeSessions(listOf(a, b)) as RecordingManager.MergeResult.Merged).saved

        val trip = TripRepository(context).getTripById(merged.metadata.sessionId)!!
        assertEquals("the fragments' own fixes must be re-associated, not invented", 600.0, trip.minAltitudeM!!, 0.001)
        assertEquals(655.0, trip.maxAltitudeM!!, 0.001)
        val rows = TripRepository(context).getSamplesForTrip(merged.metadata.sessionId)
        assertTrue("every row must have found a fix within the tolerance", rows.all { it.altitudeM != null })
        // And the merged trip now writes the column its sources could not.
        assertTrue(merged.transactionCsvFile.readLines()[0].endsWith("altitude_m"))
    }

    /**
     * Writes a saved trip the way finalize does, with a known elevation on every row - a linear
     * ramp whose first row is exactly [startAltM] and whose last row is exactly [endAltM], so an
     * assertion about the merged window is an assertion about the fixtures, not about rounding.
     *
     * @param legacyTxCsv true = write the pre-2026-09-22 15-column transactions CSV, so the merge
     *   has to recover the elevation from the wide rows and Room instead.
     */
    private suspend fun sessionWithAltitude(
        id: String,
        startMs: Long,
        endMs: Long,
        startAltM: Double,
        endAltM: Double,
        legacyTxCsv: Boolean
    ): String {
        val dir = File(recordingsDir, "session_$id").apply { mkdirs() }
        val rowCount = 24
        val step = (endMs - startMs) / rowCount
        val rows = (0 until rowCount).map { k ->
            val t = startMs + step * k
            val alt = startAltM + (endAltM - startAltM) * k / (rowCount - 1.0)
            val pid = if (k % 2 == 0) "0C" else "0D"
            if (pid == "0C") {
                tx(pid, "410C0F28", 970.0, "rpm", t).copy(altitudeM = alt)
            } else {
                tx(pid, "410D50", 80.0, "km/h", t).copy(altitudeM = alt)
            }
        }
        val wide = rows.map { r ->
            SynchronizedSample(
                timestampUtc = r.timestampUtc,
                timestampMonotonic = r.timestampMonotonic,
                rpm = if (r.pid == "0C") 970.0 else null,
                speedKmh = if (r.pid == "0D") 80.0 else null,
                altitudeM = r.altitudeM,
                fuelLevelPct = 70.0
            )
        }
        val meta = RecordingMetadata(
            sessionId = id,
            sessionName = "Recovered Run ${RecordTime.display(startMs)}",
            startTimeUtc = RecordTime.stamp(startMs),
            endTimeUtc = RecordTime.stamp(endMs),
            adapter = "ELM327 Bluetooth (recovered from raw log)"
        )
        JsonExporter.exportToJson(File(dir, "$id.json"), meta, rows)
        if (legacyTxCsv) {
            val legacyHeader = "session_id,timestamp_utc,timestamp_monotonic_ms,direction,can_id," +
                "request_hex,response_hex,service,pid,parameter,raw_payload,decoded_value,unit," +
                "status,error"
            File(dir, "${id}_transactions.csv").bufferedWriter().use { w ->
                w.write(legacyHeader + "\n")
                rows.forEach { r ->
                    // transactionRow appends the altitude cell; the legacy schema ended at `error`.
                    w.write(CsvExporter.transactionRow(id, r).substringBeforeLast(',') + "\n")
                }
            }
        } else {
            CsvExporter.exportTransactionsToCsv(File(dir, "${id}_transactions.csv"), meta, rows)
        }
        CsvExporter.exportSynchronizedSamplesToCsv(File(dir, "${id}_samples.csv"), wide)
        // Room rows carry the elevation too, which is where a legacy-CSV merge finds it.
        val repo = TripRepository(context)
        repo.insertTrip(
            TripEntity(
                id = id,
                title = meta.sessionName,
                startTimeUtc = meta.startTimeUtc,
                endTimeUtc = meta.endTimeUtc,
                startTimestamp = startMs,
                endTimestamp = endMs,
                durationSeconds = (endMs - startMs) / 1000,
                status = "COMPLETED",
                sampleCount = rows.size,
                maxAltitudeM = maxOf(startAltM, endAltM),
                minAltitudeM = minOf(startAltM, endAltM)
            )
        )
        repo.insertSamples(
            rows.mapIndexed { idx, r ->
                TelemetrySampleEntity(
                    tripId = id,
                    timestamp = r.timestampMonotonic,
                    timestampUtc = r.timestampUtc,
                    ecuCanId = "7E8",
                    pid = r.pid,
                    parameterName = r.decodedParameter,
                    rawHex = r.responseHex,
                    numericValue = r.decodedValue,
                    displayValue = r.decodedValueDisplay,
                    unit = r.unit,
                    altitudeM = r.altitudeM,
                    sequence = idx.toLong()
                )
            }
        )
        return id
    }

    /** `sessionMetadata` out of an exported session JSON, using the real org.json on the JVM. */
    private fun File.sessionMeta(): org.json.JSONObject =
        org.json.JSONObject(readText()).getJSONObject("sessionMetadata")
}
