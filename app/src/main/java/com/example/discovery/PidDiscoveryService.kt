package com.example.discovery

import android.os.SystemClock
import com.example.bluetooth.ElmTransport
import com.example.data.SettingsRepository
import com.example.model.CapabilityStatus
import com.example.model.KylaqProtocolProfile
import com.example.model.PidDefinition
import com.example.model.ResponseStatus
import com.example.model.StandardPidCatalog
import com.example.protocol.DiscoveryRangeResult
import com.example.protocol.PidDiscoveryDecoder
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scan state for the Mode 01 PID Availability Scanner.
 */
enum class PidScanStatus {
    IDLE,
    SCANNING,
    VALIDATING,
    COMPLETED,
    CANCELLED,
    ERROR
}

/**
 * Result of direct PID validation query (e.g. sending 01 XX and verifying 41 XX response).
 */
data class PidValidationResult(
    val hexPid: String,
    val name: String,
    val bitmapSupported: Boolean,
    val directStatus: CapabilityStatus,
    val rawResponse: String,
    val latencyMs: Long,
    val decodedValue: String? = null,
    val respondingCanId: String? = null,
    /**
     * Data quality of [decodedValue], reported separately from [directStatus] since
     * 2026-09-17. directStatus answers whether the ECU replied positively, NOT whether
     * the value is usable: the owner export of the 2026-09-16 run carried six rows that
     * read DIRECT_VALIDATED while their decoded value was an implausible-raw sentinel or
     * Not available. Two facts, two fields - never one dressed as the other.
     */
    val dataQuality: String = "NOT_DECODED"
)

/**
 * Service for scanning and validating standard OBD-II Mode 01 PIDs.
 *
 * Implements SAE J1979 / ISO 15031-5 availability queries:
 * 0100, 0120, 0140, 0160, 0180, 01A0, 01C0, 01E0 (8 ranges).
 *
 * Distinguishes strictly between NOT_SUPPORTED and TIMEOUT / NO_DATA / CAN_ERROR.
 * Never produces PID 100 on range E0.
 */
class PidDiscoveryService(
    private val capabilityManager: PidCapabilityManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var scanJob: Job? = null
    private var validationJob: Job? = null

    private val _status = MutableStateFlow(PidScanStatus.IDLE)
    val status: StateFlow<PidScanStatus> = _status.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentRangeText = MutableStateFlow("Ready to scan Standard OBD-II PIDs")
    val currentRangeText: StateFlow<String> = _currentRangeText.asStateFlow()

    private val _discoveredPids = MutableStateFlow<List<PidDefinition>>(emptyList())
    val discoveredPids: StateFlow<List<PidDefinition>> = _discoveredPids.asStateFlow()

    private val _validatedPids = MutableStateFlow<List<PidValidationResult>>(emptyList())
    val validatedPids: StateFlow<List<PidValidationResult>> = _validatedPids.asStateFlow()

    private val _supportedPidsCount = MutableStateFlow(0)
    val supportedPidsCount: StateFlow<Int> = _supportedPidsCount.asStateFlow()

    private val _validatedPidsCount = MutableStateFlow(0)
    val validatedPidsCount: StateFlow<Int> = _validatedPidsCount.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _rawLogEntries = MutableStateFlow<List<String>>(emptyList())
    val rawLogEntries: StateFlow<List<String>> = _rawLogEntries.asStateFlow()

    private val _discoveredRanges = MutableStateFlow<List<DiscoveryRangeResult>>(emptyList())
    val discoveredRanges: StateFlow<List<DiscoveryRangeResult>> = _discoveredRanges.asStateFlow()

    private val _discoveredEcus = MutableStateFlow<List<String>>(emptyList())
    val discoveredEcus: StateFlow<List<String>> = _discoveredEcus.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Standard OBD-II Mode 01 Base Ranges:
     * 01 00 -> PIDs 01 - 20
     * 01 20 -> PIDs 21 - 40
     * 01 40 -> PIDs 41 - 60
     * 01 60 -> PIDs 61 - 80
     * 01 80 -> PIDs 81 - A0
     * 01 A0 -> PIDs A1 - C0
     * 01 C0 -> PIDs C1 - E0
     * 01 E0 -> PIDs E1 - FF (Never generates PID 100)
     */
    val standardBaseRanges = listOf(
        0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0
    )

    fun startScan(transport: ElmTransport, scope: CoroutineScope): Job? {
        if (_isScanning.value || _isValidating.value) return scanJob

        if (!transport.isConnected) {
            _status.value = PidScanStatus.ERROR
            _errorMessage.value = "OBD-II adapter is not connected. Connect via Bluetooth or Simulator before scanning."
            appendLog("ERROR: Cannot start PID discovery - transport not connected")
            return null
        }

        scanJob?.cancel()
        val job = scope.launch(ioDispatcher) {
            _isScanning.value = true
            _status.value = PidScanStatus.SCANNING
            _errorMessage.value = null
            _progress.value = 0f
            _discoveredPids.value = emptyList()
            _validatedPids.value = emptyList()
            _supportedPidsCount.value = 0
            _validatedPidsCount.value = 0
            _discoveredRanges.value = emptyList()
            _discoveredEcus.value = emptyList()
            _rawLogEntries.value = emptyList()

            appendLog("=== Starting Standard OBD-II Mode 01 PID Discovery for Škoda Kylaq ===")
            appendLog("Protocol: ${KylaqProtocolProfile.PROTOCOL_NAME} (11-bit / 500k) | Command: ${KylaqProtocolProfile.ELM_PROTOCOL_COMMAND} | Functional ID: ${KylaqProtocolProfile.FUNCTIONAL_REQUEST_ID}")
            appendLog("Transport: ${transport::class.simpleName} | Connected: ${transport.isConnected}")

            val cumulativePids = mutableMapOf<String, PidDefinition>()
            val rangeResults = mutableListOf<DiscoveryRangeResult>()
            val respondingEcusSet = mutableSetOf<String>()
            val ecuContinuationState = mutableMapOf<String, Boolean>()

            var blockIndex = 0
            val totalBlocks = standardBaseRanges.size

            // Records one decoded range block: ranges list, responding ECUs, per-ECU
            // continuation state, capability marking and the published PID list. Local so
            // a buffer-lag SALVAGE and the retried own-base result can BOTH be recorded in
            // the same iteration (owner's Kylaq run 2026-09-16 lost the whole 0x00 block
            // to ELM327 response lag).
            fun recordRange(result: DiscoveryRangeResult) {
                val baseCmd = "01${"%02X".format(result.basePid)}"
                rangeResults.add(result)
                _discoveredRanges.value = rangeResults.toList()

                // Collect responding ECU CAN IDs
                if (result.ecuResponses.isNotEmpty()) {
                    for (ecuResp in result.ecuResponses) {
                        respondingEcusSet.add(ecuResp.rxCanId)
                        capabilityManager.parseCapabilityBitmap(result.basePid, ecuResp.bitmap.map { it.toInt() and 0xFF }, ecuResp.rxCanId)
                        ecuContinuationState[ecuResp.rxCanId] = ecuResp.hasNextRange
                        appendLog("ECU ${ecuResp.rxCanId} Bitmap for $baseCmd: [${ecuResp.bitmapHex}] (${ecuResp.supportedPids.size} supported)")
                    }
                } else if (result.rxCanId != null) {
                    respondingEcusSet.add(result.rxCanId)
                    capabilityManager.parseCapabilityBitmap(result.basePid, result.bitmap.map { it.toInt() and 0xFF }, result.rxCanId)
                    ecuContinuationState[result.rxCanId] = result.hasNextRange
                }
                _discoveredEcus.value = respondingEcusSet.toList().sorted()

                appendLog("Decoded $baseCmd Combined: [${result.bitmapHex}] (${result.supportedPids.size} supported PIDs)")

                // Map all tested PIDs in this block (never includes PID 100)
                val supportedSet = result.supportedPids.toSet()
                for (pidInt in result.allTestedPids) {
                    val hexPid = "%02X".format(pidInt)
                    val isSupported = supportedSet.contains(pidInt)

                    // Update capability manager with bitmap discovery result
                    val status = if (isSupported) CapabilityStatus.BITMAP_SUPPORTED else CapabilityStatus.NOT_SUPPORTED
                    capabilityManager.markPidStatus("01$hexPid", status)
                    capabilityManager.markPidStatus(hexPid, status)

                    val def = StandardPidCatalog.lookup(hexPid, isSupported = isSupported)
                    cumulativePids[hexPid] = def
                }

                // Publish updated state
                val sortedList = cumulativePids.values.sortedBy { it.hexPid }
                _discoveredPids.value = sortedList
                _supportedPidsCount.value = sortedList.count { it.supported }
            }

            try {
                for (basePid in standardBaseRanges) {
                    if (!isActive) break

                    // If we have discovered ECUs and NONE of them want the next range, we can break early
                    if (ecuContinuationState.isNotEmpty() && ecuContinuationState.values.none { it }) {
                        appendLog("No active ECUs indicate support for next range before 01%02X. Stopping scan.".format(basePid))
                        break
                    }

                    if (!transport.isConnected) {
                        throw IllegalStateException("OBD-II connection lost during PID scan.")
                    }

                    val rangeStart = "%02X".format(basePid + 1)
                    val rangeEnd = "%02X".format(minOf(basePid + 0x20, 0xFF))
                    val cmd = "01%02X".format(basePid)

                    _currentRangeText.value = "Querying $cmd (PIDs $rangeStart–$rangeEnd)..."
                    _progress.value = blockIndex.toFloat() / totalBlocks.toFloat()

                    // Safety verification before sending
                    val validation = SafetyValidator.validateCommand(cmd)
                    if (validation is ValidationResult.Rejected) {
                        appendLog("BLOCKED: Command $cmd failed safety validation: ${validation.reason}")
                        break
                    }

                    appendLog("TX: $cmd")
                    val startMs = SystemClock.elapsedRealtime()
                    val response = transport.sendCommand(cmd, timeoutMs = 3000L)
                    val latencyMs = SystemClock.elapsedRealtime() - startMs
                    val rxSummary = response.lines.joinToString(" / ").ifEmpty { response.rawText.trim() }
                    appendLog("RX (${latencyMs}ms): [${response.status}] $rxSummary")

                    if (response.status == ResponseStatus.TIMEOUT) {
                        appendLog("Range $cmd timed out. Setting status to TIMEOUT (NOT assumed unsupported).")
                        // Mark range PIDs as TIMEOUT instead of NOT_SUPPORTED
                        for (offset in 1..32) {
                            val pidNum = basePid + offset
                            if (pidNum <= 0xFF) {
                                val hexPid = "%02X".format(pidNum)
                                capabilityManager.markPidStatus(hexPid, CapabilityStatus.TIMEOUT)
                            }
                        }
                        // Rule 2: TIMEOUT != NOT_SUPPORTED. Before ECUs known or after, per-ECU continuation state governs.
                        blockIndex++
                        continue
                    } else if (response.status == ResponseStatus.NO_DATA) {
                        appendLog("Range $cmd returned NO DATA.")
                        for (offset in 1..32) {
                            val pidNum = basePid + offset
                            if (pidNum <= 0xFF) {
                                val hexPid = "%02X".format(pidNum)
                                capabilityManager.markPidStatus(hexPid, CapabilityStatus.NO_DATA)
                            }
                        }
                        blockIndex++
                        continue
                    } else if (response.status == ResponseStatus.CAN_ERROR || response.status == ResponseStatus.BUS_INIT_ERROR) {
                        appendLog("CAN communication error on $cmd: ${response.rawText}. Halting scan.")
                        _status.value = PidScanStatus.ERROR
                        _errorMessage.value = "CAN Bus error during $cmd probe: ${response.rawText}"
                        for (offset in 1..32) {
                            val pidNum = basePid + offset
                            if (pidNum <= 0xFF) {
                                val hexPid = "%02X".format(pidNum)
                                capabilityManager.markPidStatus(hexPid, CapabilityStatus.CAN_ERROR)
                            }
                        }
                        break
                    }

                    // Decode bitmap from response lines. ELM327 buffer-lag self-heal
                    // (owner's Kylaq discovery run 2026-09-16 08:57 IST: TX 0100 returned a
                    // garbled stale frame and TX 0120 received the 41 00 bitmap that BELONGED
                    // to 0100 - rejecting the PID mismatch was correct, but the whole base
                    // block (RPM, speed, coolant, MAP, throttle, fuel rate...) vanished from
                    // the report). A late frame is still true car data: attribute it to its
                    // real base, then retry the requested command once.
                    var rangeResult = PidDiscoveryDecoder.decodeFromRawResponse(basePid, response.lines)
                    if (rangeResult == null) {
                        PidDiscoveryDecoder.salvageLaggedBitmap(
                            basePid,
                            response.lines,
                            rangeResults.map { it.basePid }.toSet()
                        )?.let { lagged ->
                            appendLog("RX to $cmd carried a valid 01${"%02X".format(lagged.basePid)} bitmap (ELM buffer lag) - salvaged under its true base.")
                            recordRange(lagged)
                        }
                        appendLog("No 01${"%02X".format(basePid)} bitmap in RX - retrying $cmd once.")
                        val retry = transport.sendCommand(cmd, timeoutMs = 3000L)
                        val retryRx = retry.lines.joinToString(" / ").ifEmpty { retry.rawText.trim() }
                        appendLog("RX ($cmd retry): [${retry.status}] $retryRx")
                        rangeResult = PidDiscoveryDecoder.decodeFromRawResponse(basePid, retry.lines)
                        if (rangeResult == null) {
                            PidDiscoveryDecoder.salvageLaggedBitmap(
                                basePid,
                                retry.lines,
                                rangeResults.map { it.basePid }.toSet()
                            )?.let { laggedRetry ->
                                appendLog("Retry RX carried a valid 01${"%02X".format(laggedRetry.basePid)} bitmap - salvaged under its true base.")
                                recordRange(laggedRetry)
                            }
                        }
                    }
                    if (rangeResult == null) {
                        appendLog("No valid 4-byte capability bitmap found in response to $cmd (even after one retry).")
                        blockIndex++
                        continue
                    }
                    recordRange(rangeResult)

                    // Standard continuation rule: if bit 32 (basePid + 0x20) is NOT supported, stop!
                    // For base 0xE0, standard Mode 01 PID space ends at 0xFF
                    if (basePid >= 0xE0) {
                        appendLog("Completed final standard range (01E0–01FF).")
                        break
                    }
                    if (ecuContinuationState.isNotEmpty() && ecuContinuationState.values.none { it }) {
                        appendLog("Bit 32 of $cmd bitmap is 0 for all responding ECUs. Next PID block (01%02X) is NOT supported according to SAE J1979.".format(basePid + 0x20))
                        break
                    }

                    blockIndex++
                    delay(120) // Bus relaxation interval between discovery queries
                }

                if (_status.value != PidScanStatus.ERROR) {
                    _progress.value = 1f
                    _status.value = PidScanStatus.COMPLETED
                    val totalFound = _discoveredPids.value.count { it.supported }
                    val ecusCount = _discoveredEcus.value.size
                    _currentRangeText.value = "Scan Complete: $totalFound supported PIDs discovered across ${_discoveredRanges.value.size} block(s) from $ecusCount ECU(s)."
                    appendLog("=== PID Discovery Completed: $totalFound supported PIDs found across ${_discoveredRanges.value.size} range block(s) ===")
                }

            } catch (e: CancellationException) {
                _status.value = PidScanStatus.CANCELLED
                _currentRangeText.value = "Scan cancelled by user."
                appendLog("Scan cancelled.")
            } catch (e: Exception) {
                _status.value = PidScanStatus.ERROR
                _errorMessage.value = e.message ?: "Unknown error occurred during PID scan"
                _currentRangeText.value = "Error: ${_errorMessage.value}"
                appendLog("FATAL ERROR: ${e.message}")
            } finally {
                _isScanning.value = false
            }
        }
        scanJob = job
        return job
    }

    /**
     * Optional Direct Validation Phase:
     * Directly queries each supported PID using "01 XX" to confirm live vehicle response.
     */
    fun startDirectValidation(transport: ElmTransport, scope: CoroutineScope): Job? {
        if (_isScanning.value || _isValidating.value) return validationJob

        val supportedToValidate = _discoveredPids.value.filter { it.supported }
        if (supportedToValidate.isEmpty()) {
            _currentRangeText.value = "No supported PIDs to validate. Run PID Discovery first."
            return null
        }

        if (!transport.isConnected) {
            _errorMessage.value = "OBD-II adapter is not connected."
            return null
        }

        validationJob?.cancel()
        val job = scope.launch(ioDispatcher) {
            _isValidating.value = true
            _status.value = PidScanStatus.VALIDATING
            _errorMessage.value = null
            _validatedPids.value = emptyList()
            _validatedPidsCount.value = 0

            appendLog("=== Starting Direct PID Validation Phase (${supportedToValidate.size} PIDs) ===")

            val results = mutableListOf<PidValidationResult>()
            val total = supportedToValidate.size

            try {
                for ((index, def) in supportedToValidate.withIndex()) {
                    if (!isActive) break

                    val cleanPid = def.pid.removePrefix("01").uppercase()
                    val cmd = "01$cleanPid"

                    _currentRangeText.value = "Validating PID $cleanPid (${index + 1}/$total): ${def.shortName}..."
                    _progress.value = index.toFloat() / total.toFloat()

                    appendLog("TX: $cmd")
                    val startMs = SystemClock.elapsedRealtime()
                    val resp = transport.sendCommand(cmd, timeoutMs = 2500L)
                    val latencyMs = SystemClock.elapsedRealtime() - startMs
                    val rxSummary = resp.lines.joinToString(" / ").ifEmpty { resp.rawText.trim() }

                    val expectedService = 0x41
                    val expectedPidNum = cleanPid.toIntOrNull(16) ?: -1

                    val reconstructedMessages = try {
                        com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
                    } catch (_: Exception) { emptyList() }

                    // Structural correlation only (Section 3)
                    val matchingPositiveMsg = reconstructedMessages.firstOrNull { msg ->
                        !msg.isMalformed && msg.reconstructedBytes.size >= 2 &&
                                msg.reconstructedBytes[0] == expectedService &&
                                msg.reconstructedBytes[1] == expectedPidNum
                    }

                    val matchingNegativeMsg = reconstructedMessages.firstOrNull { msg ->
                        !msg.isMalformed && msg.reconstructedBytes.size >= 3 &&
                                msg.reconstructedBytes[0] == 0x7F &&
                                msg.reconstructedBytes[1] == 0x01
                    }

                    val isPositive = resp.status == ResponseStatus.OK && matchingPositiveMsg != null
                    val isNegative = matchingNegativeMsg != null

                    val status = when {
                        isPositive -> CapabilityStatus.DIRECT_VALIDATED
                        resp.status == ResponseStatus.TIMEOUT -> CapabilityStatus.TIMEOUT
                        resp.status == ResponseStatus.NO_DATA -> CapabilityStatus.NO_DATA
                        resp.status == ResponseStatus.CAN_ERROR -> CapabilityStatus.CAN_ERROR
                        isNegative -> CapabilityStatus.NOT_SUPPORTED
                        else -> CapabilityStatus.ERROR
                    }

                    // ECU Ownership rule (Section 4): only associate ECU when correlation establishes ownership
                    val respondingCanId = if (isPositive) matchingPositiveMsg?.canId else null

                    if (respondingCanId != null) {
                        capabilityManager.markPidValidated(respondingCanId, cleanPid, status)
                    } else {
                        capabilityManager.markPidStatus(cleanPid, status)
                    }

                    val payloadBytes = matchingPositiveMsg?.reconstructedBytes ?: emptyList()

                    val decodedVal = if (isPositive && payloadBytes.isNotEmpty()) {
                        try {
                            com.example.protocol.PidDecoder.decode(def, payloadBytes).displayValue
                        } catch (_: Exception) { null }
                    } else null

                    // A POSITIVE REPLY IS NOT A USABLE VALUE. Classify what came back so the
                    // report can never again claim a PID is validated while its payload is a
                    // sentinel the ECU returns for "not implemented / not available now".
                    val dataQuality = when {
                        !isPositive -> "NO_REPLY"
                        decodedVal == null -> "NOT_DECODED"
                        decodedVal.startsWith("implausible") -> "RESPONDED_IMPLAUSIBLE"
                        decodedVal == "NO DATA" -> "RESPONDED_NO_DATA"
                        decodedVal == "INVALID_RESPONSE" -> "RESPONDED_MISMATCHED_FRAME"
                        decodedVal == "Not available" -> "RESPONDED_NOT_AVAILABLE"
                        else -> "PLAUSIBLE"
                    }

                    appendLog("PID $cleanPid Validation: status=$status (${latencyMs}ms) | Decoded: $decodedVal | Data: $dataQuality | Responding ECU: $respondingCanId")

                    val valResult = PidValidationResult(
                        hexPid = cleanPid,
                        name = def.name,
                        bitmapSupported = true,
                        directStatus = status,
                        rawResponse = rxSummary,
                        latencyMs = latencyMs,
                        decodedValue = decodedVal,
                        respondingCanId = respondingCanId,
                        dataQuality = dataQuality
                    )
                    results.add(valResult)
                    _validatedPids.value = results.toList()
                    _validatedPidsCount.value = results.count {
                        it.directStatus == CapabilityStatus.DIRECT_VALIDATED || it.directStatus == CapabilityStatus.SUPPORTED
                    }

                    delay(80)
                }

                _progress.value = 1f
                _status.value = PidScanStatus.COMPLETED
                val validatedCount = _validatedPidsCount.value
                _currentRangeText.value = "Validation Complete: $validatedCount/$total PIDs confirmed active by vehicle."
                appendLog("=== Direct PID Validation Complete: $validatedCount confirmed active ===")

            } catch (e: CancellationException) {
                _status.value = PidScanStatus.CANCELLED
                _currentRangeText.value = "Validation cancelled by user."
            } catch (e: Exception) {
                _status.value = PidScanStatus.ERROR
                _errorMessage.value = e.message ?: "Validation error"
                _currentRangeText.value = "Validation error: ${_errorMessage.value}"
            } finally {
                _isValidating.value = false
            }
        }
        validationJob = job
        return job
    }

    fun stopScan() {
        if (_isScanning.value) {
            scanJob?.cancel()
            _isScanning.value = false
            _status.value = PidScanStatus.CANCELLED
            _currentRangeText.value = "PID Scan stopped"
            appendLog("User requested stop.")
        }
        if (_isValidating.value) {
            validationJob?.cancel()
            _isValidating.value = false
            _status.value = PidScanStatus.CANCELLED
            _currentRangeText.value = "PID Validation stopped"
            appendLog("User requested validation stop.")
        }
    }

    fun clearResults() {
        if (_isScanning.value || _isValidating.value) return
        _discoveredPids.value = emptyList()
        _validatedPids.value = emptyList()
        _discoveredRanges.value = emptyList()
        _discoveredEcus.value = emptyList()
        _supportedPidsCount.value = 0
        _validatedPidsCount.value = 0
        _status.value = PidScanStatus.IDLE
        _errorMessage.value = null
        _progress.value = 0f
        _currentRangeText.value = "Ready to scan Standard OBD-II PIDs"
        _rawLogEntries.value = emptyList()
    }

    /**
     * Integrates discovered supported PIDs into active Polling / Live Data configuration.
     * Updates [SettingsRepository.pidDefinitions] so only supported PIDs are enabled for active polling.
     */
    fun applySupportedPidsToLiveData(settingsRepository: SettingsRepository): Int {
        val discoveredSupported = _discoveredPids.value.filter { it.supported }
        if (discoveredSupported.isEmpty()) return 0

        val currentDefinitions = settingsRepository.pidDefinitions.value.toMutableList()
        val discoveredMap = discoveredSupported.associateBy { it.hexPid.uppercase() }

        var appliedCount = 0

        // 1. Update existing definitions
        val updated = currentDefinitions.map { def ->
            val clean = def.pid.uppercase().removePrefix("01")
            val isSupportedByDiscovery = discoveredMap.containsKey(clean)
            val isTestedUnsupported = capabilityManager.getStatus(def.id) == CapabilityStatus.NOT_SUPPORTED ||
                    capabilityManager.getStatus(clean) == CapabilityStatus.NOT_SUPPORTED

            when {
                isSupportedByDiscovery -> {
                    appliedCount++
                    def.copy(enabled = true, supported = true)
                }
                isTestedUnsupported -> {
                    def.copy(enabled = false, supported = false)
                }
                else -> def
            }
        }.toMutableList()

        // 2. Add newly discovered supported PIDs
        for ((hex, def) in discoveredMap) {
            if (updated.none { it.pid.equals(hex, ignoreCase = true) || it.id.equals("01$hex", ignoreCase = true) }) {
                updated.add(def.copy(enabled = true, supported = true))
                appliedCount++
            }
        }

        settingsRepository.savePidDefinitions(updated)
        appendLog("Applied $appliedCount supported PIDs to Live Polling Configuration.")
        return appliedCount
    }

    /**
     * Exports full discovery report in structured JSON format.
     */
    fun exportDiscoveryReportJson(vehicleVin: String? = null, vehicleName: String = "Škoda Kylaq 1.0 TSI"): String {
        val json = JSONObject()
        json.put("vehicle", vehicleName)
        json.put("vin", vehicleVin ?: "NOT_AVAILABLE")
        json.put("protocol", KylaqProtocolProfile.PROTOCOL_NAME)
        json.put("canIdType", KylaqProtocolProfile.CAN_ID_TYPE)
        json.put("canBitrate", KylaqProtocolProfile.BITRATE_DISPLAY)
        json.put("elmProtocolCommand", KylaqProtocolProfile.ELM_PROTOCOL_COMMAND)
        json.put("functionalRequestId", KylaqProtocolProfile.FUNCTIONAL_REQUEST_ID)
        json.put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))

        // Responding ECUs
        val ecusArr = JSONArray()
        _discoveredEcus.value.forEach { ecusArr.put(it) }
        json.put("respondingEcus", ecusArr)

        // Range Bitmaps
        val rangesArr = JSONArray()
        for (range in _discoveredRanges.value) {
            val rObj = JSONObject()
            rObj.put("basePid", "01%02X".format(range.basePid))
            rObj.put("bitmapHex", range.bitmapHex)
            rObj.put("supportedPidsCount", range.supportedPids.size)
            rObj.put("hasNextRange", range.hasNextRange)
            rangesArr.put(rObj)
        }
        json.put("ranges", rangesArr)

        // Range markers (0x20/0x40/0x60/0x80/0xA0/0xC0) are availability bitmaps, NOT
        // parameters. They are listed explicitly since 2026-09-17 so a reader can never
        // mistake one for a data channel again: the owner export of 2026-09-16 listed PID
        // 60 and PID 80 under supportedPids and then "validated" PID 80 as a diesel
        // particulate filter temperature on a petrol car.
        val markersArr = JSONArray()
        for (range in _discoveredRanges.value) {
            if (range.basePid < 0xE0) markersArr.put("01%02X".format(range.basePid + 0x20))
        }
        json.put("rangeMarkersNotDataPids", markersArr)

        // Supported PIDs
        val supportedArr = JSONArray()
        for (p in _discoveredPids.value.filter { it.supported }) {
            val pObj = JSONObject()
            pObj.put("pid", p.pid)
            pObj.put("name", p.name)
            pObj.put("unit", p.unit)
            supportedArr.put(pObj)
        }
        json.put("supportedPids", supportedArr)

        // Direct Validation Results
        val valArr = JSONArray()
        for (v in _validatedPids.value) {
            val vObj = JSONObject()
            vObj.put("pid", v.hexPid)
            vObj.put("name", v.name)
            vObj.put("directStatus", v.directStatus.name)
            vObj.put("latencyMs", v.latencyMs)
            vObj.put("decodedValue", v.decodedValue ?: "")
            vObj.put("dataQuality", v.dataQuality)
            vObj.put("respondingCanId", v.respondingCanId ?: "")
            valArr.put(vObj)
        }
        json.put("validationResults", valArr)
        json.put("positiveReplies", valArr.length())
        json.put("usableValues", _validatedPids.value.count { it.dataQuality == "PLAUSIBLE" })

        // Raw Logs
        val logsArr = JSONArray()
        _rawLogEntries.value.forEach { logsArr.put(it) }
        json.put("rawLogs", logsArr)

        return json.toString(2)
    }

    /**
     * Exports discovered PIDs as CSV.
     */
    fun exportDiscoveryReportCsv(): String {
        val sb = StringBuilder()
        sb.append("PID,Name,ShortName,Unit,BitmapSupported,ValidationStatus,DataQuality,DecodedValue,LatencyMs\n")
        val valMap = _validatedPids.value.associateBy { it.hexPid.uppercase() }

        for (pid in _discoveredPids.value) {
            val clean = pid.hexPid.uppercase()
            val v = valMap[clean]
            val valStatus = v?.directStatus?.name ?: "NOT_VALIDATED"
            val dataQuality = v?.dataQuality ?: "NOT_VALIDATED"
            val decoded = (v?.decodedValue ?: "").replace(",", ";")
            val lat = v?.latencyMs ?: 0L
            sb.append("${pid.pid},\"${pid.name}\",\"${pid.shortName}\",\"${pid.unit}\",${pid.supported},$valStatus,$dataQuality,\"$decoded\",$lat\n")
        }
        return sb.toString()
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        val current = _rawLogEntries.value.toMutableList()
        current.add(entry)
        _rawLogEntries.value = current
    }
}
