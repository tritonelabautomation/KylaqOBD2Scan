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

            val transport = BluetoothElmTransport(socket)
            transport.setRawLogListener(rawLogListener)

            _statusMessage.value = "Connecting to $deviceName..."
            val connected = transport.connect()

            if (!connected) {
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "Could not establish connection to $deviceName"
                return@withContext Pair(false, null)
            }

            _connectionState.value = ConnectionState.INITIALIZING
            _statusMessage.value = "Initializing ELM327 adapter (AT commands)..."

            val initResults = transport.initializeAdapter(initSequence)
            val lastInitStatus = initResults.lastOrNull()?.second?.status

            if (lastInitStatus == com.example.model.ResponseStatus.OK || initResults.isNotEmpty()) {
                activeTransport = transport
                isSimulationMode = false
                _connectionState.value = ConnectionState.CONNECTED
                _statusMessage.value = "Connected to $deviceName"
                return@withContext Pair(true, transport)
            } else {
                transport.disconnect()
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "Adapter failed initialization sequence"
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
