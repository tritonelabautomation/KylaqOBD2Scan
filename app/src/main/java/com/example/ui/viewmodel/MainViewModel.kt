package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BluetoothDeviceInfo
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.ConnectionState
import com.example.bluetooth.ElmResponse
import com.example.bluetooth.ElmTransport
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
    val canHeader: StateFlow<String> = settingsRepository.canHeader
    val sppUuid: StateFlow<String> = settingsRepository.sppUuid

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _manualCommandOutput = MutableStateFlow<String?>(null)
    val manualCommandOutput: StateFlow<String?> = _manualCommandOutput.asStateFlow()

    private val _manualCommandError = MutableStateFlow<String?>(null)
    val manualCommandError: StateFlow<String?> = _manualCommandError.asStateFlow()

    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        return bluetoothManager.getPairedDevices()
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
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
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
}
