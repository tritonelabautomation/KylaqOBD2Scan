package com.example.scheduler

import android.os.SystemClock
import com.example.bluetooth.ElmTransport
import com.example.data.PollingSpeedMode
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.example.discovery.EcuDiscoveryManager
import com.example.discovery.PidCapabilityManager
import com.example.analysis.DriveAnalytics
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
    val driveAnalytics: DriveAnalytics = DriveAnalytics(),
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

    /**
     * FIX (dead dashboard regression): all live telemetry now flows through a single
     * store that publishes BOTH the ECU-aware view ("7E8_010C") and the plain-PID view
     * ("010C") that every UI widget and the powertrain engines read.
     */
    private val telemetryStore = LiveTelemetryStore()

    // High-fidelity telemetry items conforming to the Trust Model (keyed "ECU_PID")
    val liveTelemetryMap: StateFlow<Map<String, LiveTelemetryValue>> = telemetryStore.telemetryMap

    // Display and numeric maps for UI widgets (keyed by plain PID id, e.g. "010C")
    val liveDecodedMap: StateFlow<Map<String, String>> = telemetryStore.decodedMap

    val liveNumericMap: StateFlow<Map<String, Double>> = telemetryStore.numericMap

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

    /**
     * Last CAN header pushed to the adapter with `ATSH`.
     *
     * FIX: this cache used to survive `stopPolling()` / reconnects. Because the ELM327
     * is reset (`ATZ`) and re-initialised on every connection, the adapter's header is
     * *not* what we cached, so the first request of a new session went out on the wrong
     * CAN ID and returned NO DATA. The cache is therefore reset whenever polling starts
     * or stops.
     */
    private var currentCanHeader = ""

    fun startPolling(scope: CoroutineScope, transport: ElmTransport) {
        if (_isPolling.value) return
        _isPolling.value = true

        // The adapter was (re)initialised outside this scheduler: its ATSH header is
        // unknown, so force the first query to re-send it.
        currentCanHeader = ""

        // Launch periodic staleness check supervisor.
        // Per-ECU-PID staleness tracking: "7E8_010C" and "7E9_010C" age independently,
        // and the plain-PID view falls back to a still-fresh secondary ECU.
        stalenessJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isPolling.value) {
                delay(1000L)
                telemetryStore.markStale(
                    nowMonotonic = SystemClock.elapsedRealtime(),
                    thresholdFor = { pidId -> staleThresholdMsFor(pidId) },
                    preferredEcuFor = { pidId -> capabilityManager.getPreferredEcuForPid(pidId) }
                )
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
        // FIX: drop the cached ATSH header. The next session may talk to a different
        // adapter (or the same one after an ATZ reset), so the header must be re-sent.
        currentCanHeader = ""
    }

    /**
     * Staleness budget per PID tier. Mirrors the polling intervals in
     * [com.example.model.DefaultPidDefinitions]: fast signals go stale after 2.5 s,
     * medium after 5 s, slow/research signals after 15 s.
     */
    private fun staleThresholdMsFor(pidId: String): Long = when (pidId.uppercase()) {
        "010C", "010D", "0111", "0149", "0162" -> 2500L // Fast items
        "015E", "019D", "0104", "010B", "0110", "0105" -> 5000L // Medium items
        else -> 15000L // Slow items
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
                sourceEcuId = validatingEcu,
                rawBytes = null
            )
            // FIX: attribute the failure to the ECU we actually queried so a healthy
            // secondary ECU (7E8/7E9 both answer most PIDs on this vehicle) keeps the
            // dashboard alive instead of being blanked by one timeout.
            telemetryStore.publishUnavailable(
                pidId = pidDef.id,
                ecuId = validatingEcu,
                item = telemetryItem,
                preferredEcu = capabilityManager.getPreferredEcuForPid(pidDef.id)
            )
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
                sourceEcuId = validatingEcu,
                rawBytes = null
            )
            telemetryStore.publishUnavailable(
                pidId = pidDef.id,
                ecuId = validatingEcu,
                item = telemetryItem,
                preferredEcu = capabilityManager.getPreferredEcuForPid(pidDef.id)
            )
            return
        }

        _canResponseCount.value += isoTpMessages.size

        // 4. Decode each reassembled response (prioritizing evidence-based preferred ECU)
        // FIX P0-2: Sort by evidence-based ECU selection, not hardcoded 7E8.
        // The preferred ECU is now determined by the capability manager based on actual
        // discovery evidence (engine > transmission > alphabetical), not a global default.
        val preferredEcu = capabilityManager.getPreferredEcuForPid(pidDef.id)
        val sortedMessages = isoTpMessages.sortedBy { msg ->
            val msgEcu = msg.canId?.uppercase() ?: ""
            // Priority 1: Evidence-based preferred ECU (from capability discovery)
            // Priority 2: Canonical engine ECU 7E8 (MQB standard)
            // Priority 3: Any other responding ECU
            when {
                preferredEcu != null && msgEcu == preferredEcu.uppercase() -> 0
                msgEcu == "7E8" -> 1
                else -> 2
            }
        }

        for (msg in sortedMessages) {
            // FIX P0-2: Malformed response trust gate.
            // Any ISO-TP message flagged as structurally malformed must NOT be used for:
            //   - telemetry display (avoids garbage values like corrupted coolant temperature)
            //   - capability promotion (a malformed response is not a confirmed VALIDATED state)
            //   - LIVE_ELIGIBLE promotion
            // The raw evidence is still recorded in the transaction log for diagnostics,
            // but the PID retains its prior capability state. This prevents a single bad frame
            // from incorrectly elevating a PID to LIVE_ELIGIBLE.
            if (msg.isMalformed) {
                val malformedRecord = TransactionRecord(
                    timestampUtc = rxUtc,
                    timestampMonotonic = rxMonotonic,
                    direction = Direction.RX,
                    elmCommand = requestHex,
                    canTxId = desiredHeader,
                    canRxId = msg.canId ?: pidDef.expectedRxId,
                    requestHex = requestHex,
                    responseHex = msg.reconstructedPayloadHex,
                    service = pidDef.service,
                    pid = pidDef.pid,
                    rawPayload = msg.reconstructedPayloadHex,
                    decodedParameter = pidDef.name,
                    decodedValue = null,
                    decodedValueDisplay = "MALFORMED: ${msg.malformedReason ?: "unknown"}",
                    unit = pidDef.unit,
                    responseStatus = ResponseStatus.MALFORMED
                )
                _transactionCount.value++
                _lastTransaction.value = malformedRecord
                recordingManager.recordTransaction(malformedRecord)
                continue // Skip this message; do NOT mark as VALIDATED or promote to LIVE_ELIGIBLE
            }

            // FIX (late / unsolicited frames): the ELM327 can still deliver the answer to
            // the *previous* request while we read the current one — the reference trace
            // shows coolant frames (41 05 6F) arriving inside a 010C read window. Decoding
            // such a frame against the requested PID returns INVALID_RESPONSE, which used to
            // overwrite the good sample for that ECU and blank the dashboard. Log and skip:
            // a PID-mismatched frame must not touch telemetry or capability state.
            if (pidDef.isLateFrameForOtherPid(msg.reconstructedBytes)) {
                val framePid = msg.reconstructedBytes.getOrNull(1)
                val lateRecord = TransactionRecord(
                    timestampUtc = rxUtc,
                    timestampMonotonic = rxMonotonic,
                    direction = Direction.RX,
                    elmCommand = requestHex,
                    canTxId = desiredHeader,
                    canRxId = msg.canId ?: pidDef.expectedRxId,
                    requestHex = requestHex,
                    responseHex = msg.reconstructedPayloadHex,
                    service = pidDef.service,
                    pid = pidDef.pid,
                    rawPayload = msg.reconstructedPayloadHex,
                    decodedParameter = pidDef.name,
                    decodedValue = null,
                    decodedValueDisplay =
                        "IGNORED: late frame for PID ${framePid?.toHexByte() ?: "?"}",
                    unit = pidDef.unit,
                    responseStatus = ResponseStatus.IGNORED_LATE_FRAME,
                    errorMessage = "Response PID ${framePid?.toHexByte()} != requested ${pidDef.pid}"
                )
                _transactionCount.value++
                _lastTransaction.value = lateRecord
                recordingManager.recordTransaction(lateRecord)
                appendRawHistory(pidDef.id, lateRecord)
                continue
            }

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
            // FIX P0-3: Include ECU source ID for multi-ECU telemetry isolation
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
                sourceEcuId = rxCanId,
                rawBytes = msg.reconstructedBytes
            )

            // ECU-aware storage: 7E8 and 7E9 keep separate samples, the plain-PID view
            // is driven by the deterministic primary ECU (see LiveTelemetryStore).
            updateTelemetry(pidDef.id, telemetryItem, rxCanId, preferredEcu)
            appendRawHistory(pidDef.id, rxRecord)

            // Trigger powertrain cross-signal synthesis
            onTelemetrySignalUpdated(rxMonotonic)
        }
    }

    /** Formats a byte as two uppercase hex digits (e.g. 12 -> "0C"). */
    private fun Int.toHexByte(): String =
        Integer.toHexString(this and 0xFF).uppercase().padStart(2, '0')

    /**
     * Publishes one decoded sample.
     *
     * FIX (dead dashboard regression): the previous implementation only wrote composite
     * `"ECU_PID"` keys, so every consumer that reads plain PID keys
     * (`TelemetryDashboardContent`, `DrivingDashboardScreen`, `AiDoctorScreen`,
     * `PidDetailScreen`, Android Auto's `ObdDashboardScreen`, the AI diagnostic context
     * and `onTelemetrySignalUpdated`) always saw `null`. Delegation to
     * [LiveTelemetryStore] keeps both views in sync and stops the last-responding ECU
     * from overwriting the primary one.
     */
    private fun updateTelemetry(
        pidId: String,
        telemetryItem: LiveTelemetryValue,
        ecuId: String? = null,
        preferredEcu: String? = null
    ) {
        telemetryStore.publish(
            pidId = pidId,
            ecuId = ecuId,
            item = telemetryItem,
            preferredEcu = preferredEcu
        )
    }

    /**
     * Cross-signal correlation engine: Evaluates Driving State, Transmission State, and Trip Economy.
     *
     * FIX (always-null inputs): these lookups used the plain PID keys against a map that
     * was keyed `"ECU_PID"`, so every input was `null` and the driving state stayed
     * `UNKNOWN`, fuel economy stayed "—" and the gear estimate never moved. Values are
     * now resolved through [LiveTelemetryStore], which returns the primary ECU's sample
     * and skips stale data.
     */
    private fun onTelemetrySignalUpdated(timestampMonotonic: Long) {
        val speedKmh = primaryNumeric("010D")
        val engineRpm = primaryNumeric("010C")
        val throttlePct = primaryNumeric("0111")
        val pedalPct = primaryNumeric("0149") ?: primaryNumeric("014A")
        val fuelRateVolLh = primaryNumeric("015E")
        val fuelRateMassGs = primaryNumeric("019D")

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
            rawGearRatio = primaryNumeric("01A4")
        )
        _transmissionState.value = transState

        // 5. Derived drive intelligence: power/torque curves, efficiency sweet spot,
        //    coasting-in-neutral events, turbo behaviour and per-tank fuel comparison.
        driveAnalytics.onSignals(
            timestampMonotonicMs = timestampMonotonic,
            speedKmh = speedKmh,
            rpm = engineRpm,
            throttlePct = throttlePct,
            pedalPct = pedalPct,
            fuelRateLh = effectiveFuelRateLh,
            mapKpa = primaryNumeric("010B"),
            baroKpa = primaryNumeric("0133"),
            timingDeg = primaryNumeric("010E"),
            stftPct = primaryNumeric("0106"),
            ltftPct = primaryNumeric("0107"),
            loadPct = primaryNumeric("0104"),
            fuelLevelPct = primaryNumeric("012F"),
            chargeTempC = primaryNumeric("0166"),
            wastegatePct = primaryNumeric("016E"),
            brakeActive = drivingResult.isBrakeActive,
            actualTorquePct = primaryNumeric("0162"),
            demandTorquePct = primaryNumeric("0161"),
            referenceTorqueNm = primaryNumeric("0163") ?: primaryNumeric("0164")
        )
    }

    /**
     * Resolves the numeric value published for [pidId] from its primary (evidence-based)
     * ECU, ignoring stale samples. Never invents a value.
     */
    private fun primaryNumeric(pidId: String): Double? =
        telemetryStore.numericValue(pidId, capabilityManager.getPreferredEcuForPid(pidId))

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
        telemetryStore.clear()
        currentCanHeader = ""
        economyEngine.resetTrip()
        _tripEconomy.value = TripEconomyStats()
        _realtimeEconomy.value = RealtimeEconomySnapshot(source = ValueSource.UNKNOWN)
        _drivingState.value = DrivingStateEngine.DrivingStateResult(
            state = DrivingState.UNKNOWN,
            brakeStatusDisplay = "Not available / Not detected",
            isBrakeActive = null,
            reason = "Awaiting initial vehicle telemetry",
            isFuelCut = false,
            isCoasting = false
        )
    }

    private fun getNowUtc(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
