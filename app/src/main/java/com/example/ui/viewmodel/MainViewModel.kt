package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BluetoothDeviceInfo
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.ConnectionState
import com.example.bluetooth.ElmResponse
import com.example.bluetooth.ElmTransport
import kotlinx.coroutines.flow.firstOrNull
import com.example.data.PollingSpeedMode
import com.example.data.RawLogEntry
import com.example.data.RawLogManager
import com.example.data.RecordingManager
import com.example.data.SavedRecording
import com.example.data.SettingsRepository
import com.example.model.BytePositionStats
import com.example.model.PidDefinition
import com.example.model.RecordingMetadata
import com.example.model.TransactionRecord
import com.example.protocol.PidDecoder
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.scheduler.ObdScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logDir = File(application.filesDir, "raw_logs")
    val rawLogManager = RawLogManager(logDir)
    val settingsRepository = SettingsRepository(application)
    val recordingManager = RecordingManager(application, rawLogManager)
    val bluetoothManager = BluetoothManager(application)
    val obdScheduler = ObdScheduler(recordingManager, settingsRepository)

    var activeTransport: ElmTransport? = null
        private set
    private var recordingTimerJob: Job? = null

    val connectionState: StateFlow<ConnectionState> = bluetoothManager.connectionState
    val connectedDeviceName: StateFlow<String?> = bluetoothManager.connectedDeviceName
    val connectionStatusMessage: StateFlow<String> = bluetoothManager.statusMessage

    val isPolling: StateFlow<Boolean> = obdScheduler.isPolling

    private val _selectedCanProtocol = MutableStateFlow(com.example.model.CanProtocol.AUTO)
    val selectedCanProtocol: StateFlow<com.example.model.CanProtocol> = _selectedCanProtocol.asStateFlow()

    private val _protocolHealth = MutableStateFlow(com.example.model.ProtocolHealth.UNKNOWN)
    val protocolHealth: StateFlow<com.example.model.ProtocolHealth> = _protocolHealth.asStateFlow()
    
    private val _protocolVerificationResult = MutableStateFlow<com.example.model.ProtocolVerificationResult?>(null)
    val protocolVerificationResult: StateFlow<com.example.model.ProtocolVerificationResult?> = _protocolVerificationResult.asStateFlow()

    private val _adapterVoltage = MutableStateFlow<String?>(null)
    val adapterVoltage: StateFlow<String?> = _adapterVoltage.asStateFlow()

    private val _adapterFirmware = MutableStateFlow<String?>(null)
    val adapterFirmware: StateFlow<String?> = _adapterFirmware.asStateFlow()

    private val _vehicleVin = MutableStateFlow<String?>(null)
    val vehicleVin: StateFlow<String?> = _vehicleVin.asStateFlow()

    fun fetchVehicleVin() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return

        viewModelScope.launch {
            val resp = transport.sendCommand("0902", 3000)
            if (resp.status == com.example.model.ResponseStatus.OK && resp.lines.isNotEmpty()) {
                // Decode VIN (simplified decoder, actual Mode 0902 decoding requires parsing multi-frame ASCII)
                val cleanLines = resp.lines.map { it.replace(" ", "") }
                val hexString = cleanLines.joinToString("")
                try {
                    val ascii = StringBuilder()
                    var i = 0
                    while (i < hexString.length - 1) {
                        val str = hexString.substring(i, i + 2)
                        val num = str.toIntOrNull(16)
                        if (num != null && num in 32..126) {
                            ascii.append(num.toChar())
                        }
                        i += 2
                    }
                    val vinMatch = Regex("[A-HJ-NPR-Z0-9]{17}").find(ascii.toString())
                    _vehicleVin.value = vinMatch?.value ?: "VIN Decoded: $ascii"
                } catch (e: Exception) {
                    _vehicleVin.value = "Failed to parse VIN"
                }
            } else {
                _vehicleVin.value = "VIN Unavailable"
            }
        }
    }

    val transactionCount: StateFlow<Long> = obdScheduler.transactionCount
    val canResponseCount: StateFlow<Long> = obdScheduler.canResponseCount
    val errorCount: StateFlow<Long> = obdScheduler.errorCount
    val liveDecodedMap: StateFlow<Map<String, String>> = obdScheduler.liveDecodedMap
    val liveNumericMap: StateFlow<Map<String, Double>> = obdScheduler.liveNumericMap
    val pidRawHistory: StateFlow<Map<String, List<TransactionRecord>>> = obdScheduler.pidRawHistory
    val lastTransaction: StateFlow<TransactionRecord?> = obdScheduler.lastTransaction

    val isRecording: StateFlow<Boolean> = recordingManager.isRecording
    val currentSessionMetadata: StateFlow<RecordingMetadata?> = recordingManager.currentSessionMetadata
    val currentTransactions: StateFlow<List<TransactionRecord>> = recordingManager.currentTransactions
    val savedRecordings: StateFlow<List<SavedRecording>> = recordingManager.savedRecordings

    val rawLogs: StateFlow<List<RawLogEntry>> = rawLogManager.logs

    val pollingMode: StateFlow<PollingSpeedMode> = settingsRepository.pollingMode
    val pidDefinitions: StateFlow<List<PidDefinition>> = settingsRepository.pidDefinitions
    val vehicleName: StateFlow<String> = settingsRepository.vehicleName

    fun setVehicleName(name: String) {
        settingsRepository.setVehicleName(name)
    }

    val canHeader: StateFlow<String> = settingsRepository.canHeader
    val sppUuid: StateFlow<String> = settingsRepository.sppUuid

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _manualCommandOutput = MutableStateFlow<String?>(null)
    val manualCommandOutput: StateFlow<String?> = _manualCommandOutput.asStateFlow()

    private val _manualCommandError = MutableStateFlow<String?>(null)
    val manualCommandError: StateFlow<String?> = _manualCommandError.asStateFlow()

    fun selectCanProtocol(protocol: com.example.model.CanProtocol) {
        if (_selectedCanProtocol.value != protocol) {
            _selectedCanProtocol.value = protocol
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            _protocolHealth.value = com.example.model.ProtocolHealth.UNKNOWN
            _protocolVerificationResult.value = null
        }
    }


    private val _isBatchTesting = MutableStateFlow(false)
    val isBatchTesting = _isBatchTesting.asStateFlow()

    private val _batchTestResults = MutableStateFlow<List<com.example.model.ProtocolVerificationResult>>(emptyList())
    val batchTestResults = _batchTestResults.asStateFlow()
    companion object {
        const val PROTOCOL_SETTLE_DELAY_MS = 400L
    }

    fun verifySelectedProtocol() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            _protocolHealth.value = com.example.model.ProtocolHealth.TESTING
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            
            transport.sendCommand("ATPC", 1000)
            kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
            
            val proto = _selectedCanProtocol.value
            transport.sendCommand(proto.atCommand, 1500)
            kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
            
            val result = executeProtocolVerification(transport, proto)
            
            _protocolVerificationResult.value = result
            _protocolHealth.value = result.health
            
            if (result.health == com.example.model.ProtocolHealth.WORKING || result.health == com.example.model.ProtocolHealth.PARTIAL) {
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private suspend fun executeProtocolVerification(transport: com.example.bluetooth.ElmTransport, proto: com.example.model.CanProtocol): com.example.model.ProtocolVerificationResult {
        val pidsToTest = listOf("0100", "010C", "010D", "0105", "010B", "0111", "010F", "0142")
        var success = 0
        var timeout = 0
        var invalid = 0
        var unsupported = 0
        var canErrorCount = 0
        var totalTime = 0L
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        
        val pidResults = mutableListOf<com.example.model.PidTestResult>()
        
        for (pid in pidsToTest) {
            val resp = transport.sendCommand(pid, 2000L)
            val duration = resp.durationMs
            
            if (duration > 0) {
                totalTime += duration
                if (duration < minTime) minTime = duration
                if (duration > maxTime) maxTime = duration
            }
            
            val expectedService = "41"
            val expectedPid = pid.substring(2)
            
            val hasEcuResponse = resp.lines.any { line -> 
                val cleanLine = line.replace(" ", "").uppercase()
                val expected = expectedService + expectedPid
                val idx = cleanLine.indexOf(expected)
                idx in 0..8
            }
            
            val status = when {
                hasEcuResponse -> {
                    success++
                    com.example.model.PidTestStatus.ECU_RESPONSE
                }
                resp.status == com.example.model.ResponseStatus.NO_DATA -> {
                    unsupported++
                    com.example.model.PidTestStatus.NO_DATA
                }
                resp.status == com.example.model.ResponseStatus.TIMEOUT -> {
                    timeout++
                    com.example.model.PidTestStatus.TIMEOUT
                }
                resp.status == com.example.model.ResponseStatus.CAN_ERROR || resp.status == com.example.model.ResponseStatus.BUS_INIT_ERROR -> {
                    canErrorCount++
                    com.example.model.PidTestStatus.CAN_ERROR
                }
                resp.status == com.example.model.ResponseStatus.UNABLE_TO_CONNECT || resp.status == com.example.model.ResponseStatus.MALFORMED -> {
                    invalid++
                    com.example.model.PidTestStatus.ADAPTER_ERROR
                }
                else -> {
                    invalid++
                    com.example.model.PidTestStatus.MALFORMED
                }
            }
            
            pidResults.add(
                com.example.model.PidTestResult(
                    txCommand = pid,
                    rxResponse = if (resp.lines.isNotEmpty()) resp.lines.joinToString(" ") else resp.rawText.trim(),
                    status = status,
                    latencyMs = duration
                )
            )
            
            kotlinx.coroutines.delay(100)
        }
        
        val avgTime = if (pidsToTest.isNotEmpty()) totalTime / pidsToTest.size else 0L
        
        // Protocol health categorization
        val health = when {
            success > 1 -> com.example.model.ProtocolHealth.WORKING
            success == 1 -> com.example.model.ProtocolHealth.PARTIAL
            canErrorCount > 0 || invalid > 0 || timeout == pidsToTest.size -> com.example.model.ProtocolHealth.NO_RESPONSE
            else -> com.example.model.ProtocolHealth.ADAPTER_ERROR
        }
        
        val appVer = com.example.BuildConfig.VERSION_NAME
        val appBuild = com.example.BuildConfig.VERSION_CODE
        val commit = com.example.BuildConfig.GIT_COMMIT
        
        return com.example.model.ProtocolVerificationResult(
            protocol = proto,
            successCount = success,
            timeoutCount = timeout,
            unsupportedCount = unsupported,
            invalidCount = invalid,
            canErrorCount = canErrorCount,
            totalRequests = pidsToTest.size,
            avgResponseTimeMs = avgTime,
            minResponseTimeMs = if (minTime == Long.MAX_VALUE) 0L else minTime,
            maxResponseTimeMs = if (maxTime == Long.MIN_VALUE) 0L else maxTime,
            health = health,
            pidResults = pidResults,
            appVersion = appVer,
            buildNumber = appBuild,
            commitHash = commit
        )
    }

    fun testAllCanProtocols() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            _isBatchTesting.value = true
            _batchTestResults.value = emptyList()
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            
            val protocolsToTest = listOf(
                com.example.model.CanProtocol.ISO_15765_11B_500K,
                com.example.model.CanProtocol.ISO_15765_29B_500K,
                com.example.model.CanProtocol.ISO_15765_11B_250K,
                com.example.model.CanProtocol.ISO_15765_29B_250K
            )
            
            val results = mutableListOf<com.example.model.ProtocolVerificationResult>()
            
            for (proto in protocolsToTest) {
                transport.sendCommand("ATPC", 1000)
                kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
                transport.sendCommand(proto.atCommand, 1500)
                kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
                
                val result = executeProtocolVerification(transport, proto)
                results.add(result)
                _batchTestResults.value = results.toList()
                
                // Save to database
                val entity = com.example.data.db.entities.ProtocolTestResultEntity(
                    timestamp = System.currentTimeMillis(),
                    protocol = result.protocol.displayName,
                    atCommand = result.protocol.atCommand,
                    resultStatus = result.health.name,
                    ecuResponses = result.successCount,
                    canErrors = result.canErrorCount,
                    timeouts = result.timeoutCount,
                    averageLatency = result.avgResponseTimeMs,
                    appVersion = result.appVersion,
                    buildNumber = result.buildNumber,
                    gitCommit = result.commitHash
                )
                recordingManager.tripRepository.insertProtocolTestResult(entity)
                
                // Safety delay before testing next protocol
                kotlinx.coroutines.delay(500)
            }
            
            _isBatchTesting.value = false
            
            // Auto-select best protocol
            val best = rankProtocols(results).firstOrNull()
            if (best != null && (best.health == com.example.model.ProtocolHealth.WORKING || best.health == com.example.model.ProtocolHealth.PARTIAL)) {
                _selectedCanProtocol.value = best.protocol
                _protocolVerificationResult.value = best
                _protocolHealth.value = best.health
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private fun rankProtocols(results: List<com.example.model.ProtocolVerificationResult>): List<com.example.model.ProtocolVerificationResult> {
        return results.sortedWith(
            compareBy<com.example.model.ProtocolVerificationResult> { 
                when (it.health) {
                    com.example.model.ProtocolHealth.WORKING -> 0
                    com.example.model.ProtocolHealth.PARTIAL -> 1
                    com.example.model.ProtocolHealth.NO_RESPONSE -> 2
                    com.example.model.ProtocolHealth.ADAPTER_ERROR -> 3
                    else -> 4
                }
            }
            .thenByDescending { it.successCount }
            .thenBy { it.canErrorCount + it.invalidCount + it.timeoutCount }
            .thenBy { it.avgResponseTimeMs }
        )
    }

    fun connectDevice(deviceAddress: String) {
        viewModelScope.launch {
            obdScheduler.stopPolling()
            val uuid = try {
                UUID.fromString(sppUuid.value)
            } catch (_: Exception) {
                BluetoothManager.DEFAULT_SPP_UUID
            }
            val (success, transport) = bluetoothManager.connectToDevice(
                deviceAddress = deviceAddress,
                sppUuid = uuid,
                initSequence = settingsRepository.initCommands.value,
                rawLogListener = rawLogManager
            )
            if (success && transport != null) {
                activeTransport = transport
                fetchAdapterInfo(transport)
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private suspend fun fetchAdapterInfo(transport: ElmTransport) {
        val voltageResp = transport.sendCommand("ATRV", 1000)
        _adapterVoltage.value = if (voltageResp.status == com.example.model.ResponseStatus.OK) voltageResp.rawText.trim() else null

        val firmwareResp = transport.sendCommand("ATI", 1000)
        _adapterFirmware.value = if (firmwareResp.status == com.example.model.ResponseStatus.OK) firmwareResp.rawText.trim() else null
    }

    fun startSimulationMode() {
        viewModelScope.launch {
            obdScheduler.stopPolling()
            val (success, transport) = bluetoothManager.startSimulationMode(
                initSequence = settingsRepository.initCommands.value,
                rawLogListener = rawLogManager
            )
            if (success) {
                activeTransport = transport
                fetchAdapterInfo(transport)
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            obdScheduler.stopPolling()
            bluetoothManager.disconnect()
            activeTransport = null
        }
    }

    fun togglePolling() {
        val transport = activeTransport ?: return
        if (isPolling.value) {
            obdScheduler.stopPolling()
        } else {
            obdScheduler.startPolling(viewModelScope, transport)
        }
    }

    fun startRecording() {
        val meta = recordingManager.startRecording(
            vehicleName = vehicleName.value,
            profileName = "India-Market 1.0 TSI (EA211)",
            adapterName = connectedDeviceName.value ?: "ELM327 v1.5",
            protocolName = "ISO 15765-4 CAN 11/500"
        )
        _recordingDurationSeconds.value = 0L
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (recordingManager.isRecording.value) {
                delay(1000)
                _recordingDurationSeconds.value++
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            recordingTimerJob?.cancel()
            recordingManager.stopRecording()
        }
    }

    fun renameRecording(sessionId: String, newName: String) {
        recordingManager.renameRecording(sessionId, newName)
    }

    fun deleteRecording(sessionId: String) {
        recordingManager.deleteRecording(sessionId)
    }

    fun setPollingSpeedMode(mode: PollingSpeedMode) {
        settingsRepository.setPollingMode(mode)
    }

    fun togglePid(pidId: String) {
        settingsRepository.togglePidEnabled(pidId)
    }

    fun savePid(pidDef: PidDefinition) {
        val current = settingsRepository.pidDefinitions.value.toMutableList()
        val index = current.indexOfFirst { it.id == pidDef.id }
        if (index >= 0) {
            current[index] = pidDef
        } else {
            current.add(pidDef)
        }
        settingsRepository.savePidDefinitions(current)
    }

    fun deletePid(pidId: String) {
        val current = settingsRepository.pidDefinitions.value.filterNot { it.id == pidId }
        settingsRepository.savePidDefinitions(current)
    }

    fun resetPidDefaults() {
        settingsRepository.resetPidDefaults()
    }

    fun sendManualCommand(command: String) {
        val transport = activeTransport
        if (transport == null || !transport.isConnected) {
            _manualCommandError.value = "Not connected to adapter"
            _manualCommandOutput.value = null
            return
        }

        viewModelScope.launch {
            val validation = SafetyValidator.validateCommand(command)
            if (validation is ValidationResult.Rejected) {
                _manualCommandError.value = "SAFETY BLOCK: ${validation.reason}"
                _manualCommandOutput.value = null
                return@launch
            }

            _manualCommandError.value = null
            val resp = transport.sendCommand(command, timeoutMs = 2500L)
            _manualCommandOutput.value = resp.rawText.ifBlank { resp.lines.joinToString("\n") }
        }
    }

    fun clearRawLog() {
        rawLogManager.clear()
    }

    fun getAllRawLogText(): String {
        return rawLogManager.getAllAsText()
    }

    /**
     * Reverse engineering byte-level statistics for a specific PID
     */
    fun getByteStatisticsForPid(pidId: String): List<BytePositionStats> {
        val history = pidRawHistory.value[pidId] ?: emptyList()
        val byteLists = history.mapNotNull { tx ->
            if (tx.rawPayload.isNotBlank() && tx.rawPayload.length % 2 == 0) {
                tx.rawPayload.chunked(2).mapNotNull { it.toIntOrNull(16) }
            } else null
        }
        return PidDecoder.analyzeBytePositions(byteLists)
    }

    fun get16BitWordStatisticsForPid(pidId: String): List<String> {
        val history = pidRawHistory.value[pidId] ?: emptyList()
        val byteLists = history.mapNotNull { tx ->
            if (tx.rawPayload.isNotBlank() && tx.rawPayload.length % 2 == 0) {
                tx.rawPayload.chunked(2).mapNotNull { it.toIntOrNull(16) }
            } else null
        }
        return PidDecoder.analyze16BitWords(byteLists)
    }

    // AI Doctor State and Methods
    private val _aiChatHistory = MutableStateFlow<List<com.example.model.ChatMessage>>(listOf(
        com.example.model.ChatMessage(
            sender = com.example.model.MessageSender.CAR_DOCTOR,
            text = "Hello! I am your AI Car Doctor. Ask me anything regarding your engine telemetry, coolant thresholds, boost pressure, or diagnostic codes."
        )
    ))
    val aiChatHistory: StateFlow<List<com.example.model.ChatMessage>> = _aiChatHistory.asStateFlow()

    private val aiProvider: com.example.ai.AiDoctorProvider = com.example.ai.FirebaseAiDoctorProvider()

    fun sendAiMessage(query: String) {
        val userMsg = com.example.model.ChatMessage(sender = com.example.model.MessageSender.USER, text = query)
        val loadingMsg = com.example.model.ChatMessage(sender = com.example.model.MessageSender.CAR_DOCTOR, text = "Analyzing...")
        _aiChatHistory.value = _aiChatHistory.value + userMsg + loadingMsg

        viewModelScope.launch {
            val context = buildDiagnosticContext()
            
            // Map history to AiMessage format
            val historyForAi = _aiChatHistory.value
                .dropLast(1) // Remove the "Analyzing..." message
                .map {
                    com.example.ai.AiMessage(
                        role = if (it.sender == com.example.model.MessageSender.USER) "user" else "model",
                        text = it.text
                    )
                }

            val request = com.example.ai.AiDoctorRequest(
                context = context,
                chatHistory = historyForAi,
                latestQuery = query
            )

            val response = aiProvider.analyze(request)
            
            // Replace the loading message with the actual response
            _aiChatHistory.value = _aiChatHistory.value.dropLast(1) + com.example.model.ChatMessage(
                sender = com.example.model.MessageSender.CAR_DOCTOR,
                text = response.responseText,
                isEcuFact = response.isEcuFact
            )
        }
    }

    private suspend fun buildDiagnosticContext(): com.example.ai.VehicleDiagnosticContext {
        val dtcRecords = try {
            recordingManager.tripRepository.dtcRecordsFlow.firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val dtcString = if (dtcRecords.isEmpty()) "No active DTCs were detected by the application." 
            else dtcRecords.joinToString("\n") { "${it.code}: ${it.description} (${it.status})" }

        val verificationState = protocolVerificationResult.value?.health?.name ?: "Not verified"
        
        return com.example.ai.VehicleDiagnosticContext(
            vehicleName = vehicleName.value,
            vin = vehicleVin.value ?: "UNAVAILABLE",
            connectionStatus = connectionState.value.name,
            adapterName = adapterFirmware.value ?: "UNAVAILABLE",
            protocol = selectedCanProtocol.value.displayName,
            verificationState = verificationState,
            ecuResponses = protocolVerificationResult.value?.successCount?.toLong() ?: 0L,
            canErrors = protocolVerificationResult.value?.canErrorCount?.toLong() ?: 0L,
            timeouts = protocolVerificationResult.value?.timeoutCount?.toLong() ?: 0L,
            liveData = liveDecodedMap.value,
            dtcs = dtcString,
            appVersion = com.example.BuildConfig.VERSION_NAME,
            buildNumber = com.example.BuildConfig.VERSION_CODE,
            gitCommit = "Unknown" // Could be injected via BuildConfig if configured
        )
    }
}
