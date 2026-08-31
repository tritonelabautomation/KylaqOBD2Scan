package com.example.scheduler

import android.os.SystemClock
import com.example.bluetooth.ElmTransport
import com.example.data.PollingSpeedMode
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.example.model.Direction
import com.example.model.PidDefinition
import com.example.model.ResponseStatus
import com.example.model.TransactionRecord
import com.example.protocol.IsoTpParser
import com.example.protocol.PidDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * High precision, non-blocking sequential OBD request scheduler
 */
class ObdScheduler(
    private val recordingManager: RecordingManager,
    private val settingsRepository: SettingsRepository
) {

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()

    private val _lastTransaction = MutableStateFlow<TransactionRecord?>(null)
    val lastTransaction: StateFlow<TransactionRecord?> = _lastTransaction.asStateFlow()

    private val _transactionCount = MutableStateFlow(0L)
    val transactionCount: StateFlow<Long> = _transactionCount.asStateFlow()

    private val _canResponseCount = MutableStateFlow(0L)
    val canResponseCount: StateFlow<Long> = _canResponseCount.asStateFlow()

    private val _errorCount = MutableStateFlow(0L)
    val errorCount: StateFlow<Long> = _errorCount.asStateFlow()

    // Live decoded values mapped by PID ID (e.g. "010C" -> "971 RPM")
    private val _liveDecodedMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveDecodedMap: StateFlow<Map<String, String>> = _liveDecodedMap.asStateFlow()

    // Live numeric values for telemetry gauges (e.g. "010C" -> 971.0)
    private val _liveNumericMap = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveNumericMap: StateFlow<Map<String, Double>> = _liveNumericMap.asStateFlow()

    // Research PID observations: raw history
    private val _pidRawHistory = MutableStateFlow<Map<String, List<TransactionRecord>>>(emptyMap())
    val pidRawHistory: StateFlow<Map<String, List<TransactionRecord>>> = _pidRawHistory.asStateFlow()

    private var pollingJob: Job? = null
    private var currentCanHeader = ""

    fun startPolling(scope: CoroutineScope, transport: ElmTransport) {
        if (_isPolling.value) return
        _isPolling.value = true

        pollingJob = scope.launch(Dispatchers.IO) {
            val lastPollTimeMap = mutableMapOf<String, Long>()

            while (isActive && transport.isConnected) {
                val activePids = settingsRepository.pidDefinitions.value.filter { it.enabled }
                val speedMode = settingsRepository.pollingMode.value

                if (activePids.isEmpty()) {
                    delay(500)
                    continue
                }

                for (pidDef in activePids) {
                    if (!isActive || !transport.isConnected) break

                    val intervalMs = (pidDef.defaultIntervalMs * speedMode.multiplier).toLong().coerceAtLeast(80L)
                    val lastTime = lastPollTimeMap[pidDef.id] ?: 0L
                    val now = SystemClock.elapsedRealtime()

                    if (now - lastTime >= intervalMs) {
                        lastPollTimeMap[pidDef.id] = now
                        executePidQuery(transport, pidDef)
                        // Inter-frame safety delay between sequential OBD commands
                        val interCommandDelay = when (speedMode) {
                            PollingSpeedMode.SAFE -> 120L
                            PollingSpeedMode.NORMAL -> 40L
                            PollingSpeedMode.FAST -> 15L
                        }
                        delay(interCommandDelay)
                    }
                }

                delay(10)
            }
            _isPolling.value = false
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isPolling.value = false
    }

    private suspend fun executePidQuery(transport: ElmTransport, pidDef: PidDefinition) {
        // Set CAN header if changed
        val desiredHeader = if (pidDef.canHeader.isNotBlank()) pidDef.canHeader else settingsRepository.canHeader.value
        if (desiredHeader.isNotBlank() && desiredHeader != currentCanHeader) {
            transport.sendCommand("ATSH $desiredHeader", timeoutMs = 1000L)
            currentCanHeader = desiredHeader
        }

        val requestHex = "${pidDef.service}${pidDef.pid}"
        val txUtc = getNowUtc()
        val txMonotonic = SystemClock.elapsedRealtime()

        // 1. Record TX transaction
        val txRecord = TransactionRecord(
            timestampUtc = txUtc,
            timestampMonotonic = txMonotonic,
            direction = Direction.TX,
            elmCommand = requestHex,
            canTxId = desiredHeader,
            requestHex = requestHex,
            service = pidDef.service,
            pid = pidDef.pid,
            decodedParameter = pidDef.name,
            unit = pidDef.unit
        )
        _transactionCount.value++
        _lastTransaction.value = txRecord
        recordingManager.recordTransaction(txRecord)

        // 2. Transmit over ELM327 and await response
        val elmResponse = transport.sendCommand(requestHex, timeoutMs = 1800L)
        val rxUtc = getNowUtc()
        val rxMonotonic = SystemClock.elapsedRealtime()

        if (elmResponse.status != ResponseStatus.OK) {
            _errorCount.value++
            val errorRecord = TransactionRecord(
                timestampUtc = rxUtc,
                timestampMonotonic = rxMonotonic,
                direction = Direction.ERROR,
                elmCommand = requestHex,
                canTxId = desiredHeader,
                canRxId = pidDef.expectedRxId,
                requestHex = requestHex,
                responseHex = elmResponse.rawText.trim().replace("\r", " ").replace("\n", " "),
                service = pidDef.service,
                pid = pidDef.pid,
                decodedParameter = pidDef.name,
                decodedValueDisplay = elmResponse.status.name,
                unit = pidDef.unit,
                responseStatus = elmResponse.status,
                errorMessage = elmResponse.errorMessage ?: elmResponse.status.name
            )
            _transactionCount.value++
            _lastTransaction.value = errorRecord
            recordingManager.recordTransaction(errorRecord)
            appendRawHistory(pidDef.id, errorRecord)
            return
        }

        // 3. Parse ISO-TP / CAN frames from response lines
        val isoTpMessages = IsoTpParser.reassembleLines(elmResponse.lines)

        if (isoTpMessages.isEmpty()) {
            val emptyRecord = TransactionRecord(
                timestampUtc = rxUtc,
                timestampMonotonic = rxMonotonic,
                direction = Direction.RX,
                elmCommand = requestHex,
                canTxId = desiredHeader,
                canRxId = pidDef.expectedRxId,
                requestHex = requestHex,
                responseHex = elmResponse.rawText.trim(),
                service = pidDef.service,
                pid = pidDef.pid,
                decodedParameter = pidDef.name,
                decodedValueDisplay = "NO CAN FRAMES",
                unit = pidDef.unit,
                responseStatus = ResponseStatus.NO_DATA
            )
            _transactionCount.value++
            _lastTransaction.value = emptyRecord
            recordingManager.recordTransaction(emptyRecord)
            return
        }

        _canResponseCount.value += isoTpMessages.size

        // 4. Decode each reassembled response (e.g. from 7E8)
        for (msg in isoTpMessages) {
            val decoded = PidDecoder.decode(pidDef, msg.reconstructedBytes)
            val rxCanId = msg.canId ?: pidDef.expectedRxId

            val rxRecord = TransactionRecord(
                timestampUtc = rxUtc,
                timestampMonotonic = rxMonotonic,
                direction = Direction.RX,
                elmCommand = requestHex,
                canTxId = desiredHeader,
                canRxId = rxCanId,
                requestHex = requestHex,
                responseHex = msg.reconstructedPayloadHex,
                service = pidDef.service,
                pid = pidDef.pid,
                rawPayload = msg.reconstructedPayloadHex,
                decodedParameter = decoded.parameterName,
                decodedValue = decoded.numericValue,
                decodedValueDisplay = decoded.displayValue,
                unit = decoded.unit,
                responseStatus = ResponseStatus.OK
            )

            _transactionCount.value++
            _lastTransaction.value = rxRecord
            recordingManager.recordTransaction(rxRecord)

            // Update live metrics caches
            val currMap = _liveDecodedMap.value.toMutableMap()
            currMap[pidDef.id] = "${decoded.displayValue} ${decoded.unit}".trim()
            _liveDecodedMap.value = currMap

            if (decoded.numericValue != null) {
                val numMap = _liveNumericMap.value.toMutableMap()
                numMap[pidDef.id] = decoded.numericValue
                _liveNumericMap.value = numMap
            }

            appendRawHistory(pidDef.id, rxRecord)
        }
    }

    private fun appendRawHistory(pidId: String, record: TransactionRecord) {
        val currHistory = _pidRawHistory.value.toMutableMap()
        val list = (currHistory[pidId] ?: emptyList()).toMutableList()
        if (list.size >= 500) {
            list.removeAt(0)
        }
        list.add(record)
        currHistory[pidId] = list
        _pidRawHistory.value = currHistory
    }

    fun resetCounters() {
        _transactionCount.value = 0L
        _canResponseCount.value = 0L
        _errorCount.value = 0L
        _pidRawHistory.value = emptyMap()
        _liveDecodedMap.value = emptyMap()
        _liveNumericMap.value = emptyMap()
    }

    private fun getNowUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
