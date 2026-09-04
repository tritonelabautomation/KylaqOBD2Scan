package com.example.discovery

import com.example.bluetooth.ElmTransport
import com.example.data.SettingsRepository
import com.example.model.CapabilityStatus
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-level state of the PID Scanner.
 */
enum class PidScanStatus {
    IDLE,
    SCANNING,
    COMPLETED,
    ERROR,
    CANCELLED
}

/**
 * Real OBD-II Mode 01 PID Scanner & Discovery Engine.
 *
 * Implements real SAE J1979 Mode 01 PID capability discovery against the active transport layer.
 * Strictly adheres to safety validation (read-only queries) and proper range continuation logic.
 */
class PidDiscoveryService(
    private val capabilityManager: PidCapabilityManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var scanJob: Job? = null

    private val _status = MutableStateFlow(PidScanStatus.IDLE)
    val status: StateFlow<PidScanStatus> = _status.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _currentRangeText = MutableStateFlow("Ready to scan Standard OBD-II PIDs")
    val currentRangeText: StateFlow<String> = _currentRangeText.asStateFlow()

    private val _discoveredPids = MutableStateFlow<List<PidDefinition>>(emptyList())
    val discoveredPids: StateFlow<List<PidDefinition>> = _discoveredPids.asStateFlow()

    private val _supportedPidsCount = MutableStateFlow(0)
    val supportedPidsCount: StateFlow<Int> = _supportedPidsCount.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _rawLogEntries = MutableStateFlow<List<String>>(emptyList())
    val rawLogEntries: StateFlow<List<String>> = _rawLogEntries.asStateFlow()

    private val _discoveredRanges = MutableStateFlow<List<DiscoveryRangeResult>>(emptyList())
    val discoveredRanges: StateFlow<List<DiscoveryRangeResult>> = _discoveredRanges.asStateFlow()

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
     * 01 E0 -> PIDs E1 - FF
     */
    private val standardBaseRanges = listOf(
        0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0
    )

    fun startScan(transport: ElmTransport, scope: CoroutineScope): Job? {
        if (_isScanning.value) return scanJob

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
            _supportedPidsCount.value = 0
            _discoveredRanges.value = emptyList()
            _rawLogEntries.value = emptyList()

            appendLog("=== Starting Standard OBD-II Mode 01 PID Scanner ===")
            appendLog("Transport: ${transport::class.simpleName} | Connected: ${transport.isConnected}")

            val cumulativePids = mutableMapOf<String, PidDefinition>()
            val rangeResults = mutableListOf<DiscoveryRangeResult>()

            var blockIndex = 0
            val totalBlocks = standardBaseRanges.size

            try {
                for (basePid in standardBaseRanges) {
                    if (!isActive) break

                    if (!transport.isConnected) {
                        throw IllegalStateException("OBD-II connection lost during PID scan.")
                    }

                    val rangeStart = "%02X".format(basePid + 1)
                    val rangeEnd = "%02X".format(basePid + 0x20)
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
                    val response = transport.sendCommand(cmd, timeoutMs = 3000L)
                    val rxSummary = response.lines.joinToString(" / ").ifEmpty { response.rawText.trim() }
                    appendLog("RX: [${response.status}] $rxSummary")

                    if (response.status == ResponseStatus.TIMEOUT) {
                        appendLog("Range $cmd timed out. Assuming end of supported range.")
                        break
                    } else if (response.status == ResponseStatus.NO_DATA) {
                        appendLog("Range $cmd returned NO DATA. Terminating discovery loop.")
                        break
                    } else if (response.status == ResponseStatus.CAN_ERROR || response.status == ResponseStatus.BUS_INIT_ERROR) {
                        appendLog("CAN communication error on $cmd: ${response.rawText}. Halting scan.")
                        _status.value = PidScanStatus.ERROR
                        _errorMessage.value = "CAN Bus error during $cmd probe: ${response.rawText}"
                        break
                    }

                    // Decode bitmap from response lines
                    val rangeResult = PidDiscoveryDecoder.decodeFromRawResponse(basePid, response.lines)
                    if (rangeResult == null) {
                        appendLog("No valid 4-byte capability bitmap found in response to $cmd. Halting discovery.")
                        break
                    }

                    rangeResults.add(rangeResult)
                    _discoveredRanges.value = rangeResults.toList()

                    appendLog("Decoded $cmd Bitmap: [${rangeResult.bitmapHex}] (${rangeResult.supportedPids.size} supported)")

                    // Map all 32 tested PIDs in this block
                    val supportedSet = rangeResult.supportedPids.toSet()
                    for (pidInt in rangeResult.allTestedPids) {
                        val hexPid = "%02X".format(pidInt)
                        val isSupported = supportedSet.contains(pidInt)

                        // Update global capability manager
                        capabilityManager.markPidStatus(
                            "01$hexPid",
                            if (isSupported) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
                        )
                        capabilityManager.markPidStatus(
                            hexPid,
                            if (isSupported) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
                        )

                        val def = StandardPidCatalog.lookup(hexPid, isSupported = isSupported)
                        cumulativePids[hexPid] = def
                    }

                    // Publish updated state
                    val sortedList = cumulativePids.values.sortedBy { it.hexPid }
                    _discoveredPids.value = sortedList
                    val currentSupportedCount = sortedList.count { it.supported }
                    _supportedPidsCount.value = currentSupportedCount

                    // Standard continuation rule: if bit 32 (basePid + 0x20) is NOT supported, stop!
                    if (!rangeResult.hasNextRange) {
                        appendLog("Bit 32 of $cmd bitmap is 0. Next PID block (01%02X) is NOT supported according to SAE J1979.".format(basePid + 0x20))
                        break
                    }

                    blockIndex++
                    delay(120) // Bus relaxation interval between discovery queries
                }

                if (_status.value != PidScanStatus.ERROR) {
                    _progress.value = 1f
                    _status.value = PidScanStatus.COMPLETED
                    val totalFound = _discoveredPids.value.count { it.supported }
                    _currentRangeText.value = "Scan Complete: $totalFound supported PIDs discovered across ${_discoveredRanges.value.size} block(s)."
                    appendLog("=== PID Discovery Completed Successfully: $totalFound supported PIDs found ===")
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

    fun stopScan() {
        if (_isScanning.value) {
            scanJob?.cancel()
            _isScanning.value = false
            _status.value = PidScanStatus.CANCELLED
            _currentRangeText.value = "PID Scan stopped"
            appendLog("User requested stop.")
        }
    }

    fun clearResults() {
        if (_isScanning.value) return
        _discoveredPids.value = emptyList()
        _discoveredRanges.value = emptyList()
        _supportedPidsCount.value = 0
        _status.value = PidScanStatus.IDLE
        _errorMessage.value = null
        _progress.value = 0f
        _currentRangeText.value = "Ready to scan Standard OBD-II PIDs"
        _rawLogEntries.value = emptyList()
    }

    /**
     * Integrates discovered supported PIDs into the active Polling / Live Data configuration.
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

        // 2. Add any newly discovered supported PIDs that were not already in the repository
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

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        val current = _rawLogEntries.value.toMutableList()
        current.add(entry)
        _rawLogEntries.value = current
    }
}
