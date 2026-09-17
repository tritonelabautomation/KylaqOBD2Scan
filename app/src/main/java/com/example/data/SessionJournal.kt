package com.example.data

import com.example.model.RecordingMetadata
import com.example.model.SynchronizedSample
import com.example.model.TransactionRecord
import java.io.File
import java.io.FileWriter

/**
 * Append-only, flushed-as-it-goes journal of a recording session.
 *
 * ## Why this class exists
 *
 * Owner, 2026-09-17: *"again today logs not saved unable to recover it why the hell kylaq TSI
 * coach app is killed in background it supposed to be service right even in background all ways
 * it should run and record it never ever loose the logs."*
 *
 * The recorder kept the entire drive in two RAM lists (`activeTransactionList`,
 * `activeSampleList`) and wrote its FIRST byte to the trip files inside `stopRecording()`.
 * Everything that makes a trip - both CSVs, the session JSON, the ZIP bundle, the Room row, the
 * sample rows, the AI analysis - happened after STOP. So any of these lost the whole drive:
 *
 *  - the process is killed. Motorola's battery optimisation is among the most aggressive on
 *    sale; a `connectedDevice` foreground service with a partial wake lock raises the threshold
 *    but does NOT make the process unkillable, and no app can promise that it will not be;
 *  - the app is swiped away and the OS takes the process with it;
 *  - the watchdog's auto-stop coroutine is cancelled mid-finalisation;
 *  - the app crashes;
 *  - `startRecording()` runs a second time, which cleared both lists and threw the previous
 *    session away without writing a byte of it.
 *
 * A foreground service lowers the odds. It cannot make them zero, and a design that NEEDS the
 * process to survive in order to keep its data is a design that loses data. So the trip is now
 * written **while it happens**: one CSV row per OBD transaction, flushed before the call
 * returns, in the exact format [CsvExporter] produces. After a kill the journal on disk already
 * IS the transactions CSV of that drive - recovery reads it back instead of re-parsing raw hex.
 *
 * Worst-case loss is the row still in flight, not the trip.
 *
 * ## Files
 *
 * ```
 * recordings/journal/<sessionId>_transactions.csv   live, append-only, export schema
 * recordings/journal/<sessionId>_samples.csv        live, append-only, export schema
 * recordings/journal/<sessionId>.meta               what/when, in IST with its offset
 * recordings/journal/<sessionId>.finished           written ONLY by a clean stopRecording()
 * ```
 *
 * A journal with no `.finished` marker is a session the process died during, and
 * [RecordingManager.recoverUnfinishedSessions] rebuilds it automatically on the next app start -
 * no banner to find, no button to tap, no adb.
 */
class SessionJournal(private val journalDir: File) {

    companion object {
        const val TX_SUFFIX = "_transactions.csv"
        const val SAMPLE_SUFFIX = "_samples.csv"
        const val META_SUFFIX = ".meta"
        const val FINISHED_SUFFIX = ".finished"

        private val SUFFIXES = listOf(TX_SUFFIX, SAMPLE_SUFFIX, META_SUFFIX, FINISHED_SUFFIX)

        /** `<sessionId>_transactions.csv` -> `<sessionId>`; null for anything else. */
        fun sessionIdOf(fileName: String): String? {
            for (suffix in SUFFIXES) {
                if (fileName.endsWith(suffix)) {
                    return fileName.removeSuffix(suffix).takeIf { it.isNotBlank() }
                }
            }
            return null
        }
    }

    private var txWriter: FileWriter? = null
    private var sampleWriter: FileWriter? = null

    private var failures: Int = 0
    private var lastFailure: String? = null

    /**
     * Journal writes that threw. Non-zero means the phone is out of space, the directory is not
     * writable or the storage died - and the owner must be TOLD. A silent `catch {}` here is
     * precisely how a drive goes missing without a trace, which is the bug this class exists to
     * end. [RecordingManager] surfaces it in the stop path and the recovery notice.
     */
    val writeFailures: Int get() = failures
    val lastFailureReason: String? get() = lastFailure

    val isOpen: Boolean get() = txWriter != null

    fun txFile(id: String) = File(journalDir, "$id$TX_SUFFIX")
    fun sampleFile(id: String) = File(journalDir, "$id$SAMPLE_SUFFIX")
    fun metaFile(id: String) = File(journalDir, "$id$META_SUFFIX")
    fun finishedFile(id: String) = File(journalDir, "$id$FINISHED_SUFFIX")

    /** Reads a `.meta` file written by [open] into a flat map. Values are trimmed: the file is
     *  line-oriented and a value must never carry its own newline into a trip title. */
    fun readMeta(id: String): Map<String, String> {
        val f = metaFile(id)
        if (!f.exists()) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * Opens a journal for a new session, writing the headers and the metadata immediately, so a
     * kill one second later still leaves a file that says what it is and when it started.
     */
    fun open(metadata: RecordingMetadata): Boolean {
        close()
        return try {
            journalDir.mkdirs()
            val id = metadata.sessionId
            val startMs = RecordTime.parseMillis(metadata.startTimeUtc) ?: System.currentTimeMillis()
            txWriter = FileWriter(txFile(id), false).apply {
                write(CsvExporter.TRANSACTIONS_HEADER + "\n"); flush()
            }
            sampleWriter = FileWriter(sampleFile(id), false).apply {
                write(CsvExporter.SAMPLES_HEADER + "\n"); flush()
            }
            FileWriter(metaFile(id), false).use { w ->
                w.write("sessionId=$id\n")
                w.write("sessionName=${metadata.sessionName}\n")
                w.write("vehicle=${metadata.vehicle}\n")
                w.write("vehicleId=${metadata.vehicleId ?: ""}\n")
                w.write("profile=${metadata.profile}\n")
                w.write("adapter=${metadata.adapter}\n")
                w.write("protocol=${metadata.protocol}\n")
                w.write("canBitrate=${metadata.canBitrate}\n")
                // IST with its offset, per the 2026-09-17 mandate - and the epoch beside it, so
                // a recovered session never has to guess which clock a string meant.
                w.write("startTime=${RecordTime.stamp(startMs)}\n")
                w.write("startEpochMillis=$startMs\n")
                w.write("timeZone=${RecordTime.zoneLabel()} ${RecordTime.offsetLabel(startMs)}\n")
                w.flush()
            }
            true
        } catch (e: Exception) {
            failures++
            lastFailure = "open: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** Appends one OBD transaction and flushes. A kill after this call returns still keeps it. */
    fun appendTransaction(metadata: RecordingMetadata, tx: TransactionRecord) {
        val w = txWriter ?: return
        try {
            val row = CsvExporter.transactionRow(metadata.sessionId, tx)
            synchronized(this) { w.write(row + "\n"); w.flush() }
        } catch (e: Exception) {
            failures++
            lastFailure = "tx: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Appends one synchronized sample row and flushes. */
    fun appendSample(sample: SynchronizedSample) {
        val w = sampleWriter ?: return
        try {
            val row = CsvExporter.sampleRow(sample)
            synchronized(this) { w.write(row + "\n"); w.flush() }
        } catch (e: Exception) {
            failures++
            lastFailure = "sample: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Marks the session cleanly finished. Only `stopRecording()` calls this, so the ABSENCE of
     * the marker is what identifies a session the process died during.
     */
    fun markFinished(id: String, endMillis: Long) {
        try {
            FileWriter(finishedFile(id), false).use { w ->
                w.write("endTime=${RecordTime.stamp(endMillis)}\n")
                w.write("endEpochMillis=$endMillis\n")
                w.write("timeZone=${RecordTime.zoneLabel()} ${RecordTime.offsetLabel(endMillis)}\n")
                w.write("writeFailures=$failures\n")
                w.flush()
            }
        } catch (e: Exception) {
            failures++
            lastFailure = "finish: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun close() {
        synchronized(this) {
            try { txWriter?.flush(); txWriter?.close() } catch (_: Exception) {}
            try { sampleWriter?.flush(); sampleWriter?.close() } catch (_: Exception) {}
            txWriter = null
            sampleWriter = null
        }
    }

    /**
     * Sessions on disk that were cut off: journal present, `.finished` marker absent.
     * Newest first, and ignoring files too small to hold a row (header only = the session never
     * received a single OBD response, so there is nothing to rebuild).
     */
    fun unfinishedSessions(minBytes: Long = SessionRecoveryPolicy.MIN_RAW_LOG_BYTES): List<String> {
        val files = journalDir.listFiles() ?: return emptyList()
        return files.mapNotNull { sessionIdOf(it.name) }
            .distinct()
            .filter { id -> txFile(id).exists() && txFile(id).length() > minBytes && !finishedFile(id).exists() }
            .sortedByDescending { txFile(it).lastModified() }
    }

    /** Deletes every journal artefact of a finalized session (the trip files already exist). */
    fun discard(id: String) {
        for (f in listOf(txFile(id), sampleFile(id), metaFile(id), finishedFile(id))) {
            runCatching { if (f.exists()) f.delete() }
        }
    }
}
