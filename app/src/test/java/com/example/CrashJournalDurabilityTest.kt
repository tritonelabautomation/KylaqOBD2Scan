package com.example

import com.example.data.CsvExporter
import com.example.data.RecordTime
import com.example.data.RecordingManager
import com.example.data.SessionJournal
import com.example.data.SessionRecoveryPolicy
import com.example.model.Direction
import com.example.model.RecordingMetadata
import com.example.model.ResponseStatus
import com.example.model.SynchronizedSample
import com.example.model.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Kill-proof persistence (owner 2026-09-17: *"again today logs not saved unable to recover it why
 * the hell kylaq TSI coach app is killed in background it supposed to be service right even in
 * background all ways it should run and record it never ever loose the logs."*).
 *
 * The old design held the whole drive in two RAM lists and wrote its first byte inside
 * `stopRecording()`, so a process kill cost the entire trip and recovery had to re-parse raw hex.
 * These tests pin the replacement contract:
 *
 *  - a row is on disk the moment `appendTransaction` returns, before any STOP;
 *  - a session with no `.finished` marker is detected as cut off;
 *  - a journal cut off MID-ROW still yields every complete row (worst case one row, not one trip);
 *  - the journal file is byte-for-byte the export schema, so the same reader serves both;
 *  - recovery replays the identical wide sample rows the live recorder would have written.
 *
 * Simulating the kill is deliberately crude and honest: a NEW [SessionJournal] instance over the
 * same directory is what a restarted process sees. Nothing is carried over in memory.
 */
class CrashJournalDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sessionId = "2df90142"

    private fun metadata(startMs: Long) = RecordingMetadata(
        sessionId = sessionId,
        sessionName = "Kylaq Run ${RecordTime.display(startMs)}",
        startTimeUtc = RecordTime.stamp(startMs)
    )

    /** A real frame from the owner's car: `41 0C 0F 28` = 970 rpm (A*256+B)/4. */
    private fun rpmTx(stamp: String, mono: Long) = TransactionRecord(
        timestampUtc = stamp,
        timestampMonotonic = mono,
        direction = Direction.RX,
        canRxId = "7E8",
        requestHex = "010C",
        responseHex = "410C0F28",
        service = "01",
        pid = "0C",
        rawPayload = "0F28",
        decodedParameter = "Engine RPM",
        decodedValue = 970.0,
        decodedValueDisplay = "970.0 rpm",
        unit = "rpm",
        responseStatus = ResponseStatus.OK
    )

    /** `41 0D 50` = 80 km/h - and a comma in the parameter name to prove CSV quoting survives. */
    private fun speedTx(stamp: String, mono: Long) = rpmTx(stamp, mono).copy(
        pid = "0D",
        requestHex = "010D",
        responseHex = "410D50",
        rawPayload = "50",
        decodedParameter = "Vehicle speed, km/h (computed)",
        decodedValue = 80.0,
        decodedValueDisplay = "80 km/h",
        unit = "km/h",
        altitudeM = 512.5
    )

    // ── the rows are on disk before STOP ─────────────────────────────────────────────

    @Test
    fun openingTheJournalWritesHeaderAndIstMetadataImmediately() {
        val journal = SessionJournal(tmp.root)
        val startMs = 1_789_635_425_123L
        assertTrue(journal.open(metadata(startMs)))

        val txFile = journal.txFile(sessionId)
        assertTrue("journal must exist before the first OBD line arrives", txFile.exists())
        assertEquals(CsvExporter.TRANSACTIONS_HEADER, txFile.readLines()[0])
        assertEquals(CsvExporter.SAMPLES_HEADER, journal.sampleFile(sessionId).readLines()[0])

        val meta = journal.readMeta(sessionId)
        assertEquals(sessionId, meta["sessionId"])
        assertEquals(startMs.toString(), meta["startEpochMillis"])
        assertTrue("journal meta must state IST: ${meta["startTime"]}", meta["startTime"]!!.endsWith("+05:30"))
        assertTrue("journal meta must name the zone: ${meta["timeZone"]}", meta["timeZone"]!!.contains("+05:30"))
    }

    @Test
    fun everyRowIsOnDiskBeforeStopIsEverCalled() {
        val startMs = 1_789_635_425_123L
        val meta = metadata(startMs)
        val journal = SessionJournal(tmp.root)
        journal.open(meta)

        journal.appendTransaction(meta, rpmTx(RecordTime.stamp(startMs), startMs))
        journal.appendTransaction(meta, speedTx(RecordTime.stamp(startMs + 130), startMs + 130))
        // No markFinished, no close: this is the state a killed process leaves behind.

        // What a RESTARTED process sees, reading only the disk.
        val afterKill = SessionJournal(tmp.root)
        val rows = CsvExporter.readTransactionsFromCsv(afterKill.txFile(sessionId))
        assertEquals("both rows must have survived the kill", 2, rows.size)
        assertEquals("0C", rows[0].pid)
        assertEquals(970.0, rows[0].decodedValue!!, 0.0001)
        assertEquals("0D", rows[1].pid)
        assertEquals(80.0, rows[1].decodedValue!!, 0.0001)
        assertEquals(startMs, rows[0].timestampMonotonic)
        assertEquals(startMs + 130, rows[1].timestampMonotonic)
    }

    @Test
    fun aKilledSessionIsDetectedAsUnfinishedAndAFinishedOneIsNot() {
        val journal = SessionJournal(tmp.root)
        journal.open(metadata(1_789_635_425_123L))
        journal.appendTransaction(metadata(1_789_635_425_123L), rpmTx(RecordTime.stamp(), System.currentTimeMillis()))

        assertEquals(listOf(sessionId), journal.unfinishedSessions())
        assertTrue(SessionRecoveryPolicy.isUnfinished(hasJournal = true, hasFinishedMarker = false))

        journal.markFinished(sessionId, System.currentTimeMillis())
        assertTrue("a clean STOP must never be rebuilt", journal.unfinishedSessions().isEmpty())
        assertFalse(SessionRecoveryPolicy.isUnfinished(hasJournal = true, hasFinishedMarker = true))
    }

    @Test
    fun aHeaderOnlyJournalIsNotOfferedForRecovery() {
        // The session never received a single ECU response: nothing to rebuild, and inventing an
        // empty trip would look like a successful recovery of a drive that never happened.
        val journal = SessionJournal(tmp.root)
        journal.open(metadata(System.currentTimeMillis()))
        assertTrue(journal.unfinishedSessions().isEmpty())
    }

    @Test
    fun aRowCutOffMidWriteCostsThatRowAndNothingElse() {
        val startMs = 1_789_635_425_123L
        val meta = metadata(startMs)
        val journal = SessionJournal(tmp.root)
        journal.open(meta)
        repeat(5) { i -> journal.appendTransaction(meta, rpmTx(RecordTime.stamp(startMs + i * 130L), startMs + i * 130L)) }

        // Kill in the middle of the sixth write: the file ends with half a row and no newline.
        val file = journal.txFile(sessionId)
        val complete = file.readText().substringBeforeLast("\n")
        file.writeText(complete + "\n2df90142,2026-09-17T14:27:06.000+05:30,1789635426000,RX,7E8,010C,41")

        val rows = CsvExporter.readTransactionsFromCsv(file)
        assertEquals("the five complete rows must survive", 5, rows.size)
        assertEquals(startMs, rows.first().timestampMonotonic)
        assertEquals(startMs + 4 * 130L, rows.last().timestampMonotonic)
    }

    @Test
    fun theJournalIsByteForByteTheExportSchema() {
        // One header definition, one row builder: a journal row and a finalized export row must be
        // indistinguishable, or the importer that reads one cannot read the other.
        val startMs = 1_789_635_425_123L
        val meta = metadata(startMs)
        val txs = listOf(rpmTx(RecordTime.stamp(startMs), startMs), speedTx(RecordTime.stamp(startMs + 130), startMs + 130))

        val journal = SessionJournal(tmp.root)
        journal.open(meta)
        txs.forEach { journal.appendTransaction(meta, it) }
        journal.close()

        val exported = tmp.newFile("exported_transactions.csv")
        CsvExporter.exportTransactionsToCsv(exported, meta, txs)

        assertEquals(exported.readText(), journal.txFile(sessionId).readText())
    }

    @Test
    fun commasInADecodedParameterSurviveTheRoundTrip() {
        val startMs = 1_789_635_425_123L
        val meta = metadata(startMs)
        val journal = SessionJournal(tmp.root)
        journal.open(meta)
        journal.appendTransaction(meta, speedTx(RecordTime.stamp(startMs), startMs))
        journal.close()

        val rows = CsvExporter.readTransactionsFromCsv(journal.txFile(sessionId))
        assertEquals(1, rows.size)
        assertEquals("Vehicle speed, km/h (computed)", rows[0].decodedParameter)
        assertEquals("km/h", rows[0].unit)
        assertEquals(Direction.RX, rows[0].direction)
        assertEquals("7E8", rows[0].canRxId)
        assertEquals(ResponseStatus.OK, rows[0].responseStatus)
    }

    // ── the wide sample rows ─────────────────────────────────────────────────────────

    @Test
    fun sampleRowsRoundTripWithTheirAltitudeAndIstStamps() {
        val startMs = 1_789_635_425_123L
        val sample = SynchronizedSample(
            timestampUtc = RecordTime.stamp(startMs),
            timestampMonotonic = startMs,
            rpm = 970.0,
            speedKmh = 80.0,
            engineLoadPct = 43.14,
            coolantC = 89.0,
            fuelRateLh = 5.432,
            voltageV = 13.812,
            fuelPressureRaw = "02 00 00 05 91 28",
            altitudeM = 512.5
        )
        val journal = SessionJournal(tmp.root)
        journal.open(metadata(startMs))
        journal.appendSample(sample)
        journal.close()

        val rows = CsvExporter.readSamplesFromCsv(journal.sampleFile(sessionId))
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(970.0, r.rpm!!, 0.05)
        assertEquals(80.0, r.speedKmh!!, 0.05)
        assertEquals(43.14, r.engineLoadPct!!, 0.005)
        assertEquals(89.0, r.coolantC!!, 0.05)
        assertEquals(5.432, r.fuelRateLh!!, 0.0005)
        assertEquals(13.812, r.voltageV!!, 0.0005)
        assertEquals("02 00 00 05 91 28", r.fuelPressureRaw)
        assertEquals(512.5, r.altitudeM!!, 0.05)
        // The samples schema has no monotonic column, so the IST stamp must supply the ordering
        // key exactly - a five-hour shift here would reorder a recovered trip's curves.
        assertEquals(startMs, r.timestampMonotonic)
        assertNull("a channel never sampled must stay absent, not become 0", r.mapKpa)
        assertNull(r.throttlePct)
    }

    @Test
    fun replayingJournaledTransactionsRebuildsTheSameSampleRowsTheLiveRecorderWrote() {
        // This is what makes a recovered trip's curves identical to a stopped one's: recovery
        // replays the SAME pure fold, not an approximation of it.
        val startMs = 1_789_635_425_123L
        val txs = listOf(
            rpmTx(RecordTime.stamp(startMs), startMs),
            speedTx(RecordTime.stamp(startMs + 130), startMs + 130),
            rpmTx(RecordTime.stamp(startMs + 260), startMs + 260).copy(
                pid = "05", decodedParameter = "Coolant", decodedValue = 89.0, unit = "°C"
            )
        )

        var live = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L)
        val liveRows = txs.map { live = RecordingManager.mergeSample(live, it); live }

        val replayed = SessionRecoveryPolicy.replaySamples(
            transactions = txs,
            seed = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L),
            merge = { acc, tx -> RecordingManager.mergeSample(acc, tx) }
        )

        assertEquals(liveRows.size, replayed.size)
        for (i in liveRows.indices) {
            assertEquals(liveRows[i].rpm, replayed[i].rpm)
            assertEquals(liveRows[i].speedKmh, replayed[i].speedKmh)
            assertEquals(liveRows[i].coolantC, replayed[i].coolantC)
            assertEquals(liveRows[i].timestampMonotonic, replayed[i].timestampMonotonic)
        }
        // The third row keeps the speed carried forward and adds coolant - a wide row, not a
        // per-PID row. That carry-forward is the whole point of the merge.
        assertEquals(89.0, replayed[2].coolantC!!, 0.0001)
        assertEquals(80.0, replayed[2].speedKmh!!, 0.0001)
        assertEquals(startMs + 260, replayed[2].timestampMonotonic)
    }

    // ── the source-of-truth rules ────────────────────────────────────────────────────

    @Test
    fun atStopTheLongerSourceWinsSoARestartedProcessCannotTruncateTheDrive() {
        // Auto-reconnect after a kill starts a NEW process that only ever saw the rows recorded
        // since it came up; the journal still holds the whole drive.
        val whole = List(500) { it }
        val sinceRestart = List(80) { it }
        assertEquals(500, SessionRecoveryPolicy.preferLonger(sinceRestart, whole).size)
        assertEquals(500, SessionRecoveryPolicy.preferLonger(whole, sinceRestart).size)
    }

    @Test
    fun equalLengthsKeepRamBecauseRamCarriesWhatTheCsvDrops() {
        val ram = listOf(rpmTx(RecordTime.stamp(), 1L).copy(altitudeM = 512.5))
        val fromCsv = listOf(rpmTx(RecordTime.stamp(), 1L).copy(altitudeM = null))
        val chosen = SessionRecoveryPolicy.preferLonger(ram, fromCsv)
        assertNotNull(chosen[0].altitudeM)
    }

    @Test
    fun theJournalWinsWheneverItHoldsRowsAndTheRawLogIsOnlyTheFallback() {
        assertEquals(
            SessionRecoveryPolicy.RecoverySource.JOURNAL,
            SessionRecoveryPolicy.chooseSource(journalRowCount = 1200, rawLogBytes = 400_000L)
        )
        assertEquals(
            SessionRecoveryPolicy.RecoverySource.RAW_LOG,
            SessionRecoveryPolicy.chooseSource(journalRowCount = 0, rawLogBytes = 400_000L)
        )
        assertEquals(
            SessionRecoveryPolicy.RecoverySource.NONE,
            SessionRecoveryPolicy.chooseSource(journalRowCount = 0, rawLogBytes = 12L)
        )
    }

    @Test
    fun samplesAreReusedOnlyWhenTheyCoverEveryTransaction() {
        assertTrue(SessionRecoveryPolicy.samplesUsable(sampleRowCount = 10, transactionRowCount = 10))
        assertFalse(SessionRecoveryPolicy.samplesUsable(sampleRowCount = 4, transactionRowCount = 10))
        assertFalse(SessionRecoveryPolicy.samplesUsable(sampleRowCount = 0, transactionRowCount = 0))
    }

    @Test
    fun recoveryIsSilentOnlyWhenNothingWasPending() {
        assertFalse(SessionRecoveryPolicy.shouldAnnounce(0, 0, 0))
        assertTrue(SessionRecoveryPolicy.shouldAnnounce(1, 0, 0))
        assertTrue(SessionRecoveryPolicy.shouldAnnounce(0, 1, 0))
        assertTrue(SessionRecoveryPolicy.shouldAnnounce(0, 0, 1))
    }

    @Test
    fun aWrittenFailureIsCountedNotSwallowed() {
        // A full disk used to produce a log that simply stopped growing, with nothing anywhere to
        // say so. The recorder reports journal failures to the owner at STOP.
        val journal = SessionJournal(tmp.root)
        journal.open(metadata(System.currentTimeMillis()))
        assertEquals(0, journal.writeFailures)
        assertNull(journal.lastFailureReason)
        // Writing into a directory that cannot be created must be reported, not hidden.
        val blocked = SessionJournal(java.io.File(tmp.root, "blocked").apply {
            // A regular file where a directory is needed: mkdirs() fails, so open() must fail too.
            java.io.File(tmp.root, "blocked").writeText("not a directory")
        })
        assertFalse(blocked.open(metadata(System.currentTimeMillis())))
        assertEquals(1, blocked.writeFailures)
        assertNotNull(blocked.lastFailureReason)
    }

    @Test
    fun discardRemovesEveryArtefactSoASessionIsNeverRebuiltTwice() {
        val journal = SessionJournal(tmp.root)
        val meta = metadata(System.currentTimeMillis())
        journal.open(meta)
        journal.appendTransaction(meta, rpmTx(RecordTime.stamp(), System.currentTimeMillis()))
        journal.close()
        assertEquals(1, journal.unfinishedSessions().size)

        journal.discard(sessionId)
        assertTrue(journal.unfinishedSessions().isEmpty())
        assertFalse(journal.txFile(sessionId).exists())
        assertFalse(journal.sampleFile(sessionId).exists())
        assertFalse(journal.metaFile(sessionId).exists())
    }

    @Test
    fun journalFileNamesMapBackToTheirSessionId() {
        assertEquals("2df90142", SessionJournal.sessionIdOf("2df90142_transactions.csv"))
        assertEquals("2df90142", SessionJournal.sessionIdOf("2df90142_samples.csv"))
        assertEquals("2df90142", SessionJournal.sessionIdOf("2df90142.meta"))
        assertEquals("2df90142", SessionJournal.sessionIdOf("2df90142.finished"))
        assertNull(SessionJournal.sessionIdOf("notes.txt"))
        assertNull(SessionJournal.sessionIdOf("_transactions.csv"))
    }
}
