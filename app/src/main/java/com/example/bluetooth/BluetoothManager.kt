package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isBonded: Boolean
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    CONNECTED,
    ERROR
}

/**
 * Robust Bluetooth Classic manager for ELM327 RFCOMM/SPP connection
 */
class BluetoothManager(private val context: Context) {

    companion object {
        val DEFAULT_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val androidBtManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
        androidBtManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to connect")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var activeTransport: ElmTransport? = null
    var isSimulationMode: Boolean = false
        private set

    /**
     * The transport of the currently open adapter connection, or null when disconnected.
     *
     * A second entry point into the app - the Android Auto dashboard - needs this so it can resume
     * live polling on a socket the phone UI already opened instead of racing it for the single
     * RFCOMM channel the ELM327 exposes.
     */
    fun currentTransport(): ElmTransport? = activeTransport

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    /**
     * Retrieves list of paired/bonded Bluetooth devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.map { device ->
                BluetoothDeviceInfo(
                    name = device.name ?: "Unknown ELM327",
                    address = device.address,
                    isBonded = true
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Connects to a physical Bluetooth device using RFCOMM SPP socket
     */
    @SuppressLint("MissingPermission")
    suspend fun connectToDevice(
        deviceAddress: String,
        sppUuid: UUID = DEFAULT_SPP_UUID,
        initSequence: List<String>,
        rawLogListener: RawLogListener?
    ): Pair<Boolean, ElmTransport?> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = ConnectionState.ERROR
            _statusMessage.value = "Bluetooth is not available on this device"
            return@withContext Pair(false, null)
        }

        _connectionState.value = ConnectionState.CONNECTING
        _statusMessage.value = "Opening RFCOMM socket to $deviceAddress..."

        try {
            // Cancel discovery as it slows down connection
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {}

            val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
            val deviceName = try { device.name ?: deviceAddress } catch (_: SecurityException) { deviceAddress }
            _connectedDeviceName.value = deviceName

            // Create RFCOMM socket
            val socket = try {
                device.createRfcommSocketToServiceRecord(sppUuid)
            } catch (e: Exception) {
                // Fallback using hidden createRfcommSocket method if standard fails on some clones
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as android.bluetooth.BluetoothSocket
            }

            var transport = BluetoothElmTransport(socket)
            transport.setRawLogListener(rawLogListener)

            _statusMessage.value = "Connecting to $deviceName..."
            var connected = transport.connect()

            // QA 2026-09-09 (owner question: adapter held by another app): the ELM327
            // exposes ONE RFCOMM channel. When the standard-UUID socket fails (channel
            // occupied, half-open peer, quirky clone), retry once on a fresh socket via
            // the hidden raw-channel API before declaring failure.
            if (!connected) {
                // Close the dead socket first so the retry starts with a fresh RFCOMM fd.
                runCatching { socket.close() }
                try {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val fallbackSocket = method.invoke(device, 1) as android.bluetooth.BluetoothSocket
                    transport = BluetoothElmTransport(fallbackSocket)
                    transport.setRawLogListener(rawLogListener)
                    _statusMessage.value = "Retrying $deviceName on raw RFCOMM channel 1..."
                    connected = transport.connect()
                } catch (_: Exception) { }
            }

            if (!connected) {
                val kind = ConnectionFailureClassifier.classify(transport.lastConnectError)
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = ConnectionFailureClassifier.guidance(kind, deviceName, transport.lastConnectError)
                return@withContext Pair(false, null)
            }

            _connectionState.value = ConnectionState.INITIALIZING
            _statusMessage.value = "Initializing ELM327 adapter (AT commands)..."

            val initResults = transport.initializeAdapter(initSequence)
            val lastInitStatus = initResults.lastOrNull()?.second?.status

            // FIX: Properly validate adapter initialization. Previous check accepted
            // `initResults.isNotEmpty()` as a pass condition even if ALL AT commands
            // failed. We now require:
            //  - ATZ (reset) responded OK
            //  - ATE0 (echo off) responded OK
            //  - the configured protocol command responded OK
            //
            // FIX (2nd pass): the protocol command is user configurable — see
            // SettingsRepository "init_commands" and CanProtocol: ATSP0 (auto detect),
            // ATSP6/ATSP7 (11/29-bit @ 500k), ATSP8/ATSP9 (11/29-bit @ 250k). Matching the
            // literal string "ATSP6" declared every other selection a failure
            // ("Adapter failed initialization: ATSP6 failed") even when the adapter answered
            // OK to everything it was actually sent. Any ATSP* command is now accepted, and
            // a sequence without one is not treated as a protocol failure.
            val resetOk = initResults.firstOrNull { it.first.equals("ATZ", ignoreCase = true) }
                ?.second?.status == com.example.model.ResponseStatus.OK
            val echoOffOk = initResults.firstOrNull { it.first.equals("ATE0", ignoreCase = true) }
                ?.second?.status == com.example.model.ResponseStatus.OK
            val protocolResult = initResults.firstOrNull {
                it.first.trim().uppercase().startsWith("ATSP")
            }
            val protocolCommand = protocolResult?.first?.trim()?.uppercase()
            val protocolOk = protocolCommand == null ||
                protocolResult?.second?.status == com.example.model.ResponseStatus.OK

            val initSuccessful = (lastInitStatus == com.example.model.ResponseStatus.OK ||
                    initResults.any { it.second.status == com.example.model.ResponseStatus.OK }) &&
                    resetOk && echoOffOk && protocolOk

            if (initSuccessful) {
                activeTransport = transport
                isSimulationMode = false
                _connectionState.value = ConnectionState.CONNECTED
                _statusMessage.value = "Connected to $deviceName"
                return@withContext Pair(true, transport)
            } else {
                val failureReasons = buildList {
                    if (!resetOk) add("ATZ failed")
                    if (!echoOffOk) add("ATE0 failed")
                    if (!protocolOk) add((protocolCommand ?: "ATSP") + " failed")
                }.joinToString(", ")
                transport.disconnect()
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "Adapter failed initialization: $failureReasons. Check ELM327 clone compatibility."
                return@withContext Pair(false, null)
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _statusMessage.value = "Connection error: ${e.localizedMessage}"
            return@withContext Pair(false, null)
        }
    }

    /**
     * Starts simulation mode for EA211 testing and demo
     */
    suspend fun startSimulationMode(
        initSequence: List<String>,
        rawLogListener: RawLogListener?
    ): Pair<Boolean, ElmTransport> = withContext(Dispatchers.Default) {
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDeviceName.value = "Škoda Kylaq Simulator (EA211)"
        _statusMessage.value = "Starting EA211 simulation transport..."

        val simTransport = SimulationElmTransport()
        simTransport.setRawLogListener(rawLogListener)
        simTransport.connect()

        _connectionState.value = ConnectionState.INITIALIZING
        _statusMessage.value = "Initializing simulated ELM327 protocol (ATSP6)..."
        simTransport.initializeAdapter(initSequence)

        activeTransport = simTransport
        isSimulationMode = true
        _connectionState.value = ConnectionState.CONNECTED
        _statusMessage.value = "Connected (EA211 Sim Mode)"
        Pair(true, simTransport)
    }

    /**
     * Disconnects current transport
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        activeTransport?.disconnect()
        activeTransport = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _statusMessage.value = "Disconnected"
    }
}
