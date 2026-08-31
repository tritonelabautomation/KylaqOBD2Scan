package com.example.data

import com.example.bluetooth.RawLogListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Raw log entry for communication tracking
 */
data class RawLogEntry(
    val id: Long,
    val timestampUtc: String,
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
 * High-performance raw log manager with ring buffer and continuous file logging
 */
class RawLogManager(
    private val logDirectory: File,
    private val maxBufferCapacity: Int = 1000
) : RawLogListener {

    private val _logs = MutableStateFlow<List<RawLogEntry>>(emptyList())
    val logs: StateFlow<List<RawLogEntry>> = _logs.asStateFlow()

    private val _rawTextFlow = MutableStateFlow("")
    val rawTextFlow: StateFlow<String> = _rawTextFlow.asStateFlow()

    private var entryCounter: Long = 0
    private val internalBuffer = ArrayDeque<RawLogEntry>(maxBufferCapacity + 10)
    private var currentSessionLogFile: File? = null
    private var fileWriter: FileWriter? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

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
        val timeDisplay = timeFormatter.format(Date())
        val entry = RawLogEntry(
            id = ++entryCounter,
            timestampUtc = timestampUtc,
            timestampFormatted = timeDisplay,
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

        // Write to file if recording is active
        try {
            fileWriter?.write(entry.toFormattedLine() + "\n")
            fileWriter?.flush()
        } catch (_: Exception) {}
    }

    fun startFileLogging(sessionId: String) {
        try {
            val file = File(logDirectory, "raw_log_$sessionId.txt")
            currentSessionLogFile = file
            fileWriter = FileWriter(file, true)
            fileWriter?.write("--- RAW OBD-II LOG SESSION: $sessionId ---\n")
            fileWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopFileLogging(): File? {
        try {
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
        } catch (_: Exception) {}
        val file = currentSessionLogFile
        currentSessionLogFile = null
        return file
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
