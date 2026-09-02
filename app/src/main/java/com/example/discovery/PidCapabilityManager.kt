package com.example.discovery

import com.example.model.CapabilityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages standard OBD-II PID support capability discovery (PIDs 0100, 0120, 0140, 0160, 0180, 01A0).
 * Prevents unnecessary polling of unsupported PIDs.
 */
class PidCapabilityManager {

    private val capabilityMap = ConcurrentHashMap<String, CapabilityStatus>()

    private val _capabilitiesFlow = MutableStateFlow<Map<String, CapabilityStatus>>(emptyMap())
    val capabilitiesFlow: StateFlow<Map<String, CapabilityStatus>> = _capabilitiesFlow.asStateFlow()

    private val _discoveryInProgress = MutableStateFlow(false)
    val discoveryInProgress: StateFlow<Boolean> = _discoveryInProgress.asStateFlow()

    fun reset() {
        capabilityMap.clear()
        _capabilitiesFlow.value = emptyMap()
        _discoveryInProgress.value = false
    }

    fun isPidSupported(pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        val status = capabilityMap[clean] ?: capabilityMap[pidId.uppercase()]
        return status == CapabilityStatus.SUPPORTED || status == null // If not yet tested, allow initial probe
    }

    fun getStatus(pidId: String): CapabilityStatus {
        val clean = pidId.uppercase().removePrefix("01")
        return capabilityMap[clean] ?: capabilityMap[pidId.uppercase()] ?: CapabilityStatus.NOT_TESTED
    }

    /**
     * Parses a 4-byte capability bitmap response for a given base PID (0x00, 0x20, 0x40, etc.).
     *
     * @param basePid e.g. 0x00 for PID 0100, 0x20 for PID 0120
     * @param dataBytes 4 bytes returned by the ECU
     * @return Boolean indicating if the next range is supported (LSB of byte 3)
     */
    fun parseCapabilityBitmap(basePid: Int, dataBytes: List<Int>): Boolean {
        if (dataBytes.size < 4) return false

        val b0 = dataBytes[0] and 0xFF
        val b1 = dataBytes[1] and 0xFF
        val b2 = dataBytes[2] and 0xFF
        val b3 = dataBytes[3] and 0xFF

        val bitmap32 = ((b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()) and 0xFFFFFFFFL

        for (i in 1..32) {
            val pidNum = basePid + i
            val pidHex = "%02X".format(pidNum)
            val fullId = "01$pidHex"

            // Bit 31 is PID 1, Bit 0 is PID 32
            val bitMask = 1L shl (32 - i)
            val isSupported = (bitmap32 and bitMask) != 0L

            val status = if (isSupported) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
            capabilityMap[pidHex] = status
            capabilityMap[fullId] = status
        }

        _capabilitiesFlow.value = capabilityMap.toMap()

        // Bit 0 (PID basePid + 32) indicates if the next 32-PID range is supported
        return (b3 and 0x01) != 0
    }

    fun markPidStatus(pidId: String, status: CapabilityStatus) {
        val clean = pidId.uppercase().removePrefix("01")
        capabilityMap[clean] = status
        capabilityMap[pidId.uppercase()] = status
        _capabilitiesFlow.value = capabilityMap.toMap()
    }

    fun setDiscoveryInProgress(inProgress: Boolean) {
        _discoveryInProgress.value = inProgress
    }
}
