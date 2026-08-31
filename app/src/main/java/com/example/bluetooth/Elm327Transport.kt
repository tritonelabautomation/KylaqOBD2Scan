package com.example.bluetooth

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Listener for raw communication logging
 */
interface RawLogListener {
    fun onRawLog(
        timestampUtc: String,
        timestampMonotonic: Long,
        isTx: Boolean,
        canId: String?,
        rawText: String,
        status: String
    )
}

/**
 * Transport interface for communicating with ELM327 hardware or simulator
 */
interface ElmTransport {
    val isConnected: Boolean
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun sendCommand(command: String, timeoutMs: Long = 1500L): ElmResponse
    suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>>
    fun setRawLogListener(listener: RawLogListener?)
}

/**
 * Implementation of ElmTransport over Bluetooth Classic RFCOMM Socket
 */
class BluetoothElmTransport(
    private val socket: BluetoothSocket
) : ElmTransport {

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var rawLogListener: RawLogListener? = null

    @Volatile
    private var connected: Boolean = false

    override val isConnected: Boolean
        get() = connected && socket.isConnected

    override fun setRawLogListener(listener: RawLogListener?) {
        this.rawLogListener = listener
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!socket.isConnected) {
                socket.connect()
            }
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            connected = true
            true
        } catch (e: Exception) {
            connected = false
            logRaw(isTx = false, canId = null, text = "Connection failed: ${e.localizedMessage}", status = "ERROR")
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        connected = false
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket.close()
        } catch (_: Exception) {}
        logRaw(isTx = false, canId = null, text = "Bluetooth connection closed", status = "INFO")
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse = withContext(Dispatchers.IO) {
        // Enforce safety validator on all outgoing commands
        val validation = SafetyValidator.validateCommand(command)
        if (validation is ValidationResult.Rejected) {
            val err = "SAFETY BLOCKED: ${validation.reason}"
            logRaw(isTx = true, canId = null, text = "$command [BLOCKED]", status = "BLOCKED")
            return@withContext ElmResponse(
                rawText = "",
                lines = emptyList(),
                status = com.example.model.ResponseStatus.MALFORMED,
                isPromptReceived = false,
                durationMs = 0L,
                errorMessage = err
            )
        }

        val cleanCmd = command.trim()
        val startMonotonic = SystemClock.elapsedRealtime()
        logRaw(isTx = true, canId = null, text = cleanCmd, status = "TX")

        val out = outputStream
        val inStream = inputStream
        if (out == null || inStream == null || !isConnected) {
            return@withContext ElmResponse(
                rawText = "",
                lines = emptyList(),
                status = com.example.model.ResponseStatus.UNABLE_TO_CONNECT,
                isPromptReceived = false,
                durationMs = 0L,
                errorMessage = "Socket is not connected"
            )
        }

        try {
            // Write command with carriage return
            val cmdBytes = (cleanCmd + "\r").toByteArray(Charsets.US_ASCII)
            out.write(cmdBytes)
            out.flush()

            // Read until prompt '>' or timeout
            val buffer = StringBuilder()
            val byteBuffer = ByteArray(256)
            var promptFound = false

            while (SystemClock.elapsedRealtime() - startMonotonic < timeoutMs) {
                if (inStream.available() > 0) {
                    val bytesRead = inStream.read(byteBuffer)
                    if (bytesRead > 0) {
                        val chunk = String(byteBuffer, 0, bytesRead, Charsets.US_ASCII)
                        buffer.append(chunk)
                        if (chunk.contains(">")) {
                            promptFound = true
                            break
                        }
                    }
                } else {
                    Thread.sleep(10)
                }
            }

            val rawOutput = buffer.toString()
            val duration = SystemClock.elapsedRealtime() - startMonotonic
            val parsed = Elm327Parser.parse(rawOutput, duration)

            // Log RX lines
            if (parsed.lines.isNotEmpty()) {
                for (line in parsed.lines) {
                    logRaw(isTx = false, canId = null, text = line, status = parsed.status.name)
                }
            } else if (rawOutput.isNotBlank()) {
                logRaw(isTx = false, canId = null, text = rawOutput.trim(), status = parsed.status.name)
            } else {
                logRaw(isTx = false, canId = null, text = "[TIMEOUT / NO RX]", status = "TIMEOUT")
            }

            return@withContext parsed
        } catch (e: Exception) {
            val duration = SystemClock.elapsedRealtime() - startMonotonic
            logRaw(isTx = false, canId = null, text = "IO Error: ${e.localizedMessage}", status = "ERROR")
            return@withContext ElmResponse(
                rawText = "",
                lines = emptyList(),
                status = com.example.model.ResponseStatus.CAN_ERROR,
                isPromptReceived = false,
                durationMs = duration,
                errorMessage = e.localizedMessage
            )
        }
    }

    override suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>> {
        val results = mutableListOf<Pair<String, ElmResponse>>()
        for (cmd in initSequence) {
            val resp = sendCommand(cmd, timeoutMs = 2500L)
            results.add(Pair(cmd, resp))
            // Brief pause between init commands for ELM327 microcontrollers
            Thread.sleep(100)
        }
        return results
    }

    private fun logRaw(isTx: Boolean, canId: String?, text: String, status: String) {
        val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        rawLogListener?.onRawLog(
            timestampUtc = nowUtc,
            timestampMonotonic = SystemClock.elapsedRealtime(),
            isTx = isTx,
            canId = canId,
            rawText = text,
            status = status
        )
    }
}
