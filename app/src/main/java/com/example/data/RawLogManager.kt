package com.example.data

import com.example.bluetooth.RawLogListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter

/**
 * Raw log entry for communication tracking
 */
data class RawLogEntry(
    val id: Long,
    /** Record stamp as it arrived from the transport. IST with offset since 1.0.337. */
    val timestampUtc: String,
    /** What the log line prints: `yyyy-MM-dd HH:mm:ss.SSS` in IST ([RecordTime.logStamp]). */
    val timestampFormatted: String,
    val timestampMonotonic: Long,
    val isTx: Boolean,
    val canId: String?,
    val rawText: String,
    val status: String
) {
    fun toFormattedLine(): String {
        val dirSymbol = if (isTx) "TX >" else "RX <"
        val header = if (canId != null) "$canId " else ""
        return "$timestampFormatted $dirSymbol $header$rawText"
    }
}

/**
 * High-performance raw log manager with ring buffer and continuous file logging.
 *
 * ## Durability rules (owner 2026-09-17: "it never ever loose the logs")
 *
 * Three defects here used to cost data, and all three are fixed:
 *
 *  1. **Lines carried time-of-day only** (`14:27:05.123`). Recovery therefore had to GUESS the
 *     calendar day from the file's last-modified time. Every line now carries its full IST date,
 *     so [com.example.analysis.RawLogRecovery] reads the day the line states instead of
 *     inferring one.
 *  2. **The stamp printed was `Date()` - the moment the log listener ran - not the moment the
 *     frame went on the wire.** Under load those differ, which quietly corrupts every duration
 *     measured from the log. The line now uses the transport's own [timestampMonotonic].
 *  3. **`catch (_: Exception) {}` swallowed every write failure.** A full disk or an unwritable
 *     directory produced a log file that simply stopped growing, with nothing anywhere to say
 *     so. Failures are now counted and exposed as [writeFailureCount] / [lastWriteFailure], and
 *     the recorder surfaces them to the owner.
 *
 * The ring buffer is still RAM-only and still lost on a kill - that is what the on-disk file and
 * [SessionJournal] are for.
 */
class RawLogManager(
    private val logDirectory: File,
    private val maxBufferCapacity: Int = 1000
) : RawLogListener {

    companion object {
        /** Connection-log prefix. NOT `raw_log_`, so recovery never reads it as a session. */
        const val CONN_LOG_PREFIX = "conn_log_"

        /** How long connection logs are kept. Session logs are kept forever. */
        const val CONN_LOG_RETENTION_DAYS = 7L

        /**
         * Pure retention rule, exposed for tests: a connection log is prunable when it is older
         * than the window; a session log never is, whatever its age.
         */
        fun isPrunableConnectionLog(fileName: String, lastModifiedMs: Long, nowMs: Long): Boolean =
            fileName.startsWith(CONN_LOG_PREFIX) &&
                nowMs - lastModifiedMs > CONN_LOG_RETENTION_DAYS * 24L * 3_600_000L
    }

    private val _logs = MutableStateFlow<List<RawLogEntry>>(emptyList())
    val logs: StateFlow<List<RawLogEntry>> = _logs.asStateFlow()

    private val _rawTextFlow = MutableStateFlow("")
    val rawTextFlow: StateFlow<String> = _rawTextFlow.asStateFlow()

    private var entryCounter: Long = 0
    private val internalBuffer = ArrayDeque<RawLogEntry>(maxBufferCapacity + 10)
    private var currentSessionLogFile: File? = null
    private var fileWriter: FileWriter? = null

    /** Lines that could not be written. Non-zero means the log is INCOMPLETE - say so. */
    private var failures: Int = 0
    private var lastFailureText: String? = null
    val writeFailureCount: Int get() = failures
    val lastWriteFailure: String? get() = lastFailureText

    /** True while a file is open and taking lines. */
    val isFileLogging: Boolean get() = fileWriter != null

    /** The file lines are going to right now, or null. */
    val currentLogFile: File? get() = currentSessionLogFile

    init {
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }

    override fun onRawLog(
        timestampUtc: String,
        timestampMonotonic: Long,
        isTx: Boolean,
        canId: String?,
        rawText: String,
        status: String
    ) {
        // The frame's own instant, in IST, with its date - not the moment this listener ran.
        //
        // Careful: the transports pass `SystemClock.elapsedRealtime()` as timestampMonotonic here
        // (millis since BOOT), while `TransactionRecord.timestampMonotonic` elsewhere in the app is
        // epoch millis. Same field name, two different clocks. So the raw log takes its wall time
        // from the transport's own record stamp and never from the monotonic argument, which would
        // otherwise print 1970.
        val now = System.currentTimeMillis()
        val eventMillis = RecordTime.parseMillis(timestampUtc)?.takeIf { it in 1_000_000_000_000L..(now + 86_400_000L) }
            ?: timestampMonotonic.takeIf { it in 1_000_000_000_000L..(now + 86_400_000L) }
            ?: now
        val entry = RawLogEntry(
            id = ++entryCounter,
            timestampUtc = timestampUtc,
            timestampFormatted = RecordTime.logStamp(eventMillis),
            timestampMonotonic = timestampMonotonic,
            isTx = isTx,
            canId = canId,
            rawText = rawText,
            status = status
        )

        synchronized(internalBuffer) {
            if (internalBuffer.size >= maxBufferCapacity) {
                internalBuffer.removeFirst()
            }
            internalBuffer.addLast(entry)
            _logs.value = internalBuffer.toList()
        }

        // Write to file if a log is open. Flushed per line: a kill after this call keeps it.
        val w = fileWriter
        if (w != null) {
            try {
                synchronized(this) {
                    w.write(entry.toFormattedLine() + "\n")
                    w.flush()
                }
            } catch (e: Exception) {
                failures++
                lastFailureText = e.message ?: e.javaClass.simpleName
                android.util.Log.e("RawLogManager", "raw log write failed", e)
            }
        }
    }

    /**
     * Logs everything the adapter says from the moment it CONNECTS, not only while a trip is being
     * recorded (owner 2026-09-17: "even in background all ways it should run and record it never
     * ever loose the logs").
     *
     * The gap this closes is real: the socket is open and frames are flowing before auto-record
     * decides to start, and if the process is killed in that window the RAM ring buffer was the
     * only copy. A connection log is written to `conn_log_<yyyy-MM-dd>.txt` - a deliberately
     * DIFFERENT prefix from `raw_log_<sessionId>.txt`, so [com.example.analysis.RawLogRecovery]
     * never mistakes it for a session and never invents a trip out of an idle connection.
     *
     * Idempotent per day, and a recording session's log always takes precedence: opening a
     * connection log while a session log is live is a no-op.
     */
    fun startConnectionLogging() {
        synchronized(this) {
            if (fileWriter != null) return      // a session (or today's connection log) is already open
            try {
                val day = RecordTime.logStamp().substringBefore(' ')
                val file = File(logDirectory, "$CONN_LOG_PREFIX$day.txt")
                currentSessionLogFile = file
                fileWriter = FileWriter(file, true).apply {
                    if (file.length() == 0L) {
                        write(
                            "--- OBD CONNECTION LOG $day | opened ${RecordTime.stamp()} " +
                                "(${RecordTime.zoneLabel()} ${RecordTime.offsetLabel()}) | " +
                                "adapter traffic outside a recorded trip ---\n"
                        )
                    }
                    flush()
                }
                pruneConnectionLogs()
            } catch (e: Exception) {
                failures++
                lastFailureText = "conn open: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.e("RawLogManager", "could not open connection log", e)
            }
        }
    }

    /**
     * Keeps connection logs from growing without bound: they are a safety net for the window
     * between connecting and recording, not a permanent archive. Anything older than
     * [CONN_LOG_RETENTION_DAYS] is deleted - session logs (`raw_log_*`) are NEVER touched, because
     * those are trips.
     */
    fun pruneConnectionLogs(nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val cutoff = nowMs - CONN_LOG_RETENTION_DAYS * 24L * 3_600_000L
            logDirectory.listFiles()?.forEach { f ->
                if (f.name.startsWith(CONN_LOG_PREFIX) && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    /**
     * Opens `raw_log_<sessionId>.txt` and appends a dated session header.
     *
     * Idempotent: a second call for the session already being logged is a no-op, so an
     * auto-reconnect that restarts recording cannot truncate the log of the drive in progress.
     */
    fun startFileLogging(sessionId: String) {
        try {
            synchronized(this) {
                val already = currentSessionLogFile
                if (fileWriter != null && already != null && already.name == "raw_log_$sessionId.txt") return
                closeWriterLocked()
                val file = File(logDirectory, "raw_log_$sessionId.txt")
                currentSessionLogFile = file
                fileWriter = FileWriter(file, true).apply {
                    // The header states the clock every line below is written in, so a log read
                    // on another machine or years later cannot be misinterpreted.
                    write(
                        "--- RAW OBD-II LOG SESSION: $sessionId | started ${RecordTime.stamp()} " +
                            "(${RecordTime.zoneLabel()} ${RecordTime.offsetLabel()}) ---\n"
                    )
                    flush()
                }
            }
        } catch (e: Exception) {
            failures++
            lastFailureText = "open: ${e.message ?: e.javaClass.simpleName}"
            android.util.Log.e("RawLogManager", "could not open raw log", e)
        }
    }

    fun stopFileLogging(): File? {
        synchronized(this) { closeWriterLocked() }
        val file = currentSessionLogFile
        currentSessionLogFile = null
        return file
    }

    /**
     * Closes a connection log without returning it as a session log.
     *
     * [stopFileLogging] hands its file to `RecordingManager`, which copies it into the trip bundle
     * - correct for a session log, wrong for a connection log, which belongs to no trip.
     */
    fun stopConnectionLogging() {
        synchronized(this) {
            val f = currentSessionLogFile
            if (f != null && f.name.startsWith(CONN_LOG_PREFIX)) {
                closeWriterLocked()
                currentSessionLogFile = null
            }
        }
    }

    private fun closeWriterLocked() {
        try {
            fileWriter?.flush()
            fileWriter?.close()
        } catch (e: Exception) {
            failures++
            lastFailureText = "close: ${e.message ?: e.javaClass.simpleName}"
        }
        fileWriter = null
    }

    fun clear() {
        synchronized(internalBuffer) {
            internalBuffer.clear()
            _logs.value = emptyList()
        }
    }

    fun getAllAsText(): String {
        synchronized(internalBuffer) {
            return internalBuffer.joinToString("\n") { it.toFormattedLine() }
        }
    }
}
