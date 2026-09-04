package com.example.scheduler

import android.os.SystemClock
import com.example.bluetooth.ElmTransport
import com.example.data.PollingSpeedMode
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.example.discovery.EcuDiscoveryManager
import com.example.discovery.PidCapabilityManager
import com.example.engine.DrivingStateEngine
import com.example.engine.EconomyEngine
import com.example.engine.TransmissionEngine
import com.example.model.CapabilityStatus
import com.example.model.Direction
import com.example.model.DrivingState
import com.example.model.LiveTelemetryValue
import com.example.model.PidDefinition
import com.example.model.PollingPriority
import com.example.model.RealtimeEconomySnapshot
import com.example.model.ResponseStatus
import com.example.model.TransactionRecord
import com.example.model.TransmissionState
import com.example.model.TripEconomyStats
import com.example.model.ValueSource
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Enterprise-grade sequential OBD polling scheduler.
 *
 * Implements:
 * 1. Multi-tier prioritized scheduling (FAST, MEDIUM, SLOW).
 * 2. Strict capability discovery gating (prevents polling unsupported PIDs).
 * 3. High-integrity Trust Model with source provenance (STANDARD_OBD, CALCULATED, ESTIMATED, etc.).
 * 4. Zero fake values: unsupported signals are explicitly "Not available / Not detected".
 * 5. Integrated Real-time Fuel Economy, Riemann Trip Integration, Transmission State, and Driving State Engines.
 */
class ObdScheduler(
    private val recordingManager: RecordingManager,
    private val settingsRepository: SettingsRepository,
    val capabilityManager: PidCapabilityManager = PidCapabilityManager(),
    val economyEngine: EconomyEngine = EconomyEngine(),
    val drivingStateEngine: DrivingStateEngine = DrivingStateEngine(),
    val transmissionEngine: TransmissionEngine = TransmissionEngine()
) {

    val ecuDiscoveryManager = EcuDiscoveryManager(capabilityManager)

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

    // High-fidelity telemetry items conforming to the Trust Model
    private val _liveTelemetryMap = MutableStateFlow<Map<String, LiveTelemetryValue>>(emptyMap())
    val liveTelemetryMap: StateFlow<Map<String, LiveTelemetryValue>> = _liveTelemetryMap.asStateFlow()

    // Backward-compatible display and numeric maps for existing UI widgets
    private val _liveDecodedMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveDecodedMap: StateFlow<Map<String, String>> = _liveDecodedMap.asStateFlow()

    private val _liveNumericMap = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveNumericMap: StateFlow<Map<String, Double>> = _liveNumericMap.asStateFlow()

    // Research PID observations: raw history
    private val _pidRawHistory = MutableStateFlow<Map<String, List<TransactionRecord>>>(emptyMap())
    val pidRawHistory: StateFlow<Map<String, List<TransactionRecord>>> = _pidRawHistory.asStateFlow()

    // Powertrain Intelligence StateFlows
    private val _realtimeEconomy = MutableStateFlow(
        RealtimeEconomySnapshot(
            instantKmL = null,
            instantKmLDisplay = "—",
            instantL100km = null,
            instantL100kmDisplay = "—",
            smoothedKmL = null,
            smoothedKmLDisplay = "—",
            smoothedL100km = null,
            smoothedL100kmDisplay = "—",
            idleConsumptionLh = null,
            isIdle = false,
            source = ValueSource.UNKNOWN
        )
    )
    val realtimeEconomy: StateFlow<RealtimeEconomySnapshot> = _realtimeEconomy.asStateFlow()

    private val _tripEconomy = MutableStateFlow(TripEconomyStats())
    val tripEconomy: StateFlow<TripEconomyStats> = _tripEconomy.asStateFlow()

    private val _drivingState = MutableStateFlow(
        DrivingStateEngine.DrivingStateResult(
            state = DrivingState.UNKNOWN,
            brakeStatusDisplay = "Not available / Not detected",
            isBrakeActive = null,
            reason = "Awaiting initial vehicle telemetry",
            isFuelCut = false,
            isCoasting = false
        )
    )
    val drivingState: StateFlow<DrivingStateEngine.DrivingStateResult> = _drivingState.asStateFlow()

    private val _transmissionState = MutableStateFlow(
        TransmissionState(
            selectedRange = "—",
            actualGear = null,
            actualGearDisplay = "Not available / Not detected",
            estimatedGear = null,
            estimatedGearDisplay = "—",
            targetGearDisplay = "Not available",
            inputRpm = null,
            outputRpm = null,
            torqueConverterSlipRpm = null,
            torqueConverterLockup = "Not available",
            atfTemperatureC = null,
            isEstimatedGearConfident = false,
            source = ValueSource.UNKNOWN
        )
    )
    val transmissionState: StateFlow<TransmissionState> = _transmissionState.asStateFlow()

    private var pollingJob: Job? = null
    private var stalenessJob: Job? = null
    private var currentCanHeader = ""

    // In-memory telemetry cache
    private val telemetryValues = ConcurrentHashMap<String, LiveTelemetryValue>()

    fun startPolling(scope: CoroutineScope, transport: ElmTransport) {
        if (_isPolling.value) return
        _isPolling.value = true

        // Launch periodic staleness check supervisor
        stalenessJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isPolling.value) {
                delay(1000L)
                val nowMonotonic = SystemClock.elapsedRealtime()
                var updated = false
                telemetryValues.forEach { (id, item) ->
                    val staleThresholdMs = when (id) {
                        "010C", "010D", "0111", "0149", "0162" -> 2500L // Fast items
                        "015E", "019D", "0104", "010B", "0110", "0105" -> 5000L // Medium items
                        else -> 15000L // Slow items
                    }
                    if (!item.isStale && (nowMonotonic - item.timestampMonotonic > staleThresholdMs)) {
                        telemetryValues[id] = item.copy(isStale = true)
                        updated = true
                    }
                }
                if (updated) {
                    _liveTelemetryMap.value = telemetryValues.toMap()
                }
            }
        }

        pollingJob = scope.launch(Dispatchers.IO) {
            val lastPollTimeMap = mutableMapOf<String, Long>()

            while (isActive && transport.isConnected) {
                val activePids = settingsRepository.pidDefinitions.value.filter { it.enabled }
                val speedMode = settingsRepository.pollingMode.value

                if (activePids.isEmpty()) {
                    delay(500)
                    continue
                }

                // Sort: FAST first, then MEDIUM, then SLOW
                val prioritizedPids = activePids.sortedBy {
                    when (it.priority) {
                        PollingPriority.FAST -> 0
                        PollingPriority.MEDIUM -> 1
                        PollingPriority.SLOW -> 2
                    }
                }

                for (pidDef in prioritizedPids) {
                    if (!isActive || !transport.isConnected) break

                    // Rule 5: Live polling is allowed ONLY for PIDs that have been directly validated
                    // Reject: NOT_TESTED, BITMAP_SUPPORTED, TIMEOUT, NO_DATA, CAN_ERROR, NOT_SUPPORTED
                    if (!capabilityManager.isLiveEligible(pidDef.id)) {
                        continue
                    }

                    val intervalMs = (pidDef.defaultIntervalMs * speedMode.multiplier).toLong().coerceAtLeast(60L)
                    val lastTime = lastPollTimeMap[pidDef.id] ?: 0L
                    val now = SystemClock.elapsedRealtime()

                    if (now - lastTime >= intervalMs) {
                        lastPollTimeMap[pidDef.id] = now
                        executePidQuery(transport, pidDef)

                        val interCommandDelay = when (speedMode) {
                            PollingSpeedMode.SAFE -> 100L
                            PollingSpeedMode.NORMAL -> 30L
                            PollingSpeedMode.FAST -> 10L
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
        stalenessJob?.cancel()
        stalenessJob = null
        _isPolling.value = false
    }

    private suspend fun executePidQuery(transport: ElmTransport, pidDef: PidDefinition) {
        // Resolve target ECU CAN addressing:
        // Use verified validating ECU physical request address if known, else def.canHeader, else settings, else 7DF
        val validatingEcu = capabilityManager.getValidatingEcuForPid(pidDef.id)
        val desiredHeader = when {
            validatingEcu != null -> com.example.model.KylaqProtocolProfile.getPhysicalRequestId(validatingEcu)
            pidDef.canHeader.isNotBlank() -> pidDef.canHeader
            settingsRepository.canHeader.value.isNotBlank() -> settingsRepository.canHeader.value
            else -> com.example.model.KylaqProtocolProfile.FUNCTIONAL_REQUEST_ID
        }
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

            // Rule 6: NEVER interpret timeout or NO DATA as NOT_SUPPORTED!
            val failureStatus = when (elmResponse.status) {
                ResponseStatus.TIMEOUT -> CapabilityStatus.TIMEOUT
                ResponseStatus.NO_DATA -> CapabilityStatus.NO_DATA
                ResponseStatus.CAN_ERROR, ResponseStatus.BUS_INIT_ERROR -> CapabilityStatus.CAN_ERROR
                ResponseStatus.UNABLE_TO_CONNECT -> CapabilityStatus.TIMEOUT
                ResponseStatus.MALFORMED -> CapabilityStatus.MALFORMED_RESPONSE
                else -> CapabilityStatus.ERROR
            }
            if (validatingEcu != null) {
                capabilityManager.markPidStatus(validatingEcu, pidDef.id, failureStatus)
            } else {
                capabilityManager.markPidStatus(pidDef.id, failureStatus)
            }

            val telemetryItem = LiveTelemetryValue(
                parameterName = pidDef.name,
                numericValue = null,
                displayValue = "Not available",
                unit = pidDef.unit,
                source = ValueSource.STANDARD_OBD,
                timestampMonotonic = rxMonotonic,
                isValid = false,
                isStale = false,
                sourcePid = pidDef.id,
                rawBytes = null
            )
            updateTelemetry(pidDef.id, telemetryItem, "Not available")
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

            val telemetryItem = LiveTelemetryValue(
                parameterName = pidDef.name,
                numericValue = null,
                displayValue = "Not available",
                unit = pidDef.unit,
                source = ValueSource.STANDARD_OBD,
                timestampMonotonic = rxMonotonic,
                isValid = false,
                isStale = false,
                sourcePid = pidDef.id,
                rawBytes = null
            )
            updateTelemetry(pidDef.id, telemetryItem, "Not available")
            return
        }

        _canResponseCount.value += isoTpMessages.size

        // 4. Decode each reassembled response (prioritizing expected ECU e.g. 7E8)
        val sortedMessages = isoTpMessages.sortedBy { msg ->
            if (msg.canId.equals(pidDef.expectedRxId, ignoreCase = true) || msg.canId.equals("7E8", ignoreCase = true)) 1 else 0
        }

        for (msg in sortedMessages) {
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

            if (rxCanId != null) {
                capabilityManager.markPidValidated(rxCanId, pidDef.id, CapabilityStatus.DIRECT_VALIDATED)
            } else {
                capabilityManager.markPidStatus(pidDef.id, CapabilityStatus.DIRECT_VALIDATED)
            }

            val source = if (pidDef.isResearch) ValueSource.RAW_OBSERVED else ValueSource.STANDARD_OBD
            val telemetryItem = LiveTelemetryValue(
                parameterName = pidDef.name,
                numericValue = decoded.numericValue,
                displayValue = decoded.displayValue,
                unit = decoded.unit,
                source = source,
                timestampMonotonic = rxMonotonic,
                isValid = decoded.isKnown,
                isStale = false,
                sourcePid = pidDef.id,
                rawBytes = msg.reconstructedBytes
            )

            val displayString = "${decoded.displayValue} ${decoded.unit}".trim()
            updateTelemetry(pidDef.id, telemetryItem, displayString, decoded.numericValue)
            appendRawHistory(pidDef.id, rxRecord)

            // Trigger powertrain cross-signal synthesis
            onTelemetrySignalUpdated(rxMonotonic)
        }
    }

    private fun updateTelemetry(
        pidId: String,
        telemetryItem: LiveTelemetryValue,
        displayString: String,
        numericValue: Double? = null
    ) {
        telemetryValues[pidId] = telemetryItem
        _liveTelemetryMap.value = telemetryValues.toMap()

        val currDecoded = _liveDecodedMap.value.toMutableMap()
        currDecoded[pidId] = displayString
        _liveDecodedMap.value = currDecoded

        if (numericValue != null) {
            val currNum = _liveNumericMap.value.toMutableMap()
            currNum[pidId] = numericValue
            _liveNumericMap.value = currNum
        }
    }

    /**
     * Cross-signal correlation engine: Evaluates Driving State, Transmission State, and Trip Economy.
     */
    private fun onTelemetrySignalUpdated(timestampMonotonic: Long) {
        val speedKmh = telemetryValues["010D"]?.numericValue
        val engineRpm = telemetryValues["010C"]?.numericValue
        val throttlePct = telemetryValues["0111"]?.numericValue
        val pedalPct = telemetryValues["0149"]?.numericValue ?: telemetryValues["014A"]?.numericValue
        val fuelRateVolLh = telemetryValues["015E"]?.numericValue
        val fuelRateMassGs = telemetryValues["019D"]?.numericValue

        // Resolve effective fuel rate in L/h (from 015E volume or 019D mass with 745 g/L density)
        val effectiveFuelRateLh = when {
            fuelRateVolLh != null -> fuelRateVolLh
            fuelRateMassGs != null -> (fuelRateMassGs * 3600.0) / 745.0
            else -> null
        }

        // 1. Driving State Evaluation
        val drivingResult = drivingStateEngine.evaluate(
            timestampMonotonic = timestampMonotonic,
            speedKmh = speedKmh,
            engineRpm = engineRpm,
            acceleratorPct = pedalPct,
            throttlePct = throttlePct,
            fuelRateLh = effectiveFuelRateLh
        )
        _drivingState.value = drivingResult

        // 2. Real-time Fuel Economy
        val economySnapshot = economyEngine.computeInstantEconomy(
            speedKmh = speedKmh,
            fuelRateLh = effectiveFuelRateLh,
            engineRpm = engineRpm
        )
        _realtimeEconomy.value = economySnapshot

        // 3. Trip Economy Riemann Integration
        val tripStats = economyEngine.processTripSample(
            timestampMonotonic = timestampMonotonic,
            speedKmh = speedKmh,
            fuelRateLh = effectiveFuelRateLh,
            engineRpm = engineRpm,
            isFuelCut = drivingResult.isFuelCut,
            isCoasting = drivingResult.isCoasting
        )
        _tripEconomy.value = tripStats

        // 4. Transmission State (6-speed AT, NO DSG logic)
        val transState = transmissionEngine.evaluate(
            speedKmh = speedKmh,
            engineRpm = engineRpm,
            validatedActualGear = null, // Set if authoritative TCU response received
            rawGearRatio = telemetryValues["01A4"]?.numericValue
        )
        _transmissionState.value = transState
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
        telemetryValues.clear()
        _liveTelemetryMap.value = emptyMap()
        economyEngine.resetTrip()
        _tripEconomy.value = TripEconomyStats()
    }

    private fun getNowUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
