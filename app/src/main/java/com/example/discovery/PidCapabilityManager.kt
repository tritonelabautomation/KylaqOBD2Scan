package com.example.discovery

import com.example.model.CapabilityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages standard OBD-II PID support capability discovery across vehicle ECUs.
 *
 * Supports both global/aggregated state and granular per-ECU state (e.g. 7E8, 7E9, 7EA).
 * Strictly prevents confusion between unsupported PIDs and transport/timeout errors.
 */
class PidCapabilityManager {

    // Global aggregated capability map: PID -> Status
    private val capabilityMap = ConcurrentHashMap<String, CapabilityStatus>()

    // Granular per-ECU capability map: ECU CAN ID -> (PID -> Status)
    private val ecuCapabilityMap = ConcurrentHashMap<String, ConcurrentHashMap<String, CapabilityStatus>>()

    private val _capabilitiesFlow = MutableStateFlow<Map<String, CapabilityStatus>>(emptyMap())
    val capabilitiesFlow: StateFlow<Map<String, CapabilityStatus>> = _capabilitiesFlow.asStateFlow()

    private val _ecuCapabilitiesFlow = MutableStateFlow<Map<String, Map<String, CapabilityStatus>>>(emptyMap())
    val ecuCapabilitiesFlow: StateFlow<Map<String, Map<String, CapabilityStatus>>> = _ecuCapabilitiesFlow.asStateFlow()

    private val _discoveryInProgress = MutableStateFlow(false)
    val discoveryInProgress: StateFlow<Boolean> = _discoveryInProgress.asStateFlow()

    fun reset() {
        capabilityMap.clear()
        ecuCapabilityMap.clear()
        _capabilitiesFlow.value = emptyMap()
        _ecuCapabilitiesFlow.value = emptyMap()
        _discoveryInProgress.value = false
    }

    fun isPidSupported(pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        val status = capabilityMap[clean] ?: capabilityMap[pidId.uppercase()]
        return status == CapabilityStatus.SUPPORTED || status == null // If not yet tested, allow initial probe
    }

    fun isPidSupported(ecuId: String, pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        val ecuMap = ecuCapabilityMap[ecuId.uppercase()] ?: return isPidSupported(pidId)
        val status = ecuMap[clean] ?: ecuMap[pidId.uppercase()]
        return status == CapabilityStatus.SUPPORTED
    }

    fun getStatus(pidId: String): CapabilityStatus {
        val clean = pidId.uppercase().removePrefix("01")
        return capabilityMap[clean] ?: capabilityMap[pidId.uppercase()] ?: CapabilityStatus.NOT_TESTED
    }

    fun getStatus(ecuId: String, pidId: String): CapabilityStatus {
        val clean = pidId.uppercase().removePrefix("01")
        val ecuMap = ecuCapabilityMap[ecuId.uppercase()] ?: return CapabilityStatus.NOT_TESTED
        return ecuMap[clean] ?: ecuMap[pidId.uppercase()] ?: CapabilityStatus.NOT_TESTED
    }

    fun getCapabilitiesForEcu(ecuId: String): Map<String, CapabilityStatus> {
        return ecuCapabilityMap[ecuId.uppercase()]?.toMap() ?: emptyMap()
    }

    fun getAllEcuCapabilities(): Map<String, Map<String, CapabilityStatus>> {
        return ecuCapabilityMap.mapValues { it.value.toMap() }
    }

    fun getRespondingEcuIds(): List<String> {
        return ecuCapabilityMap.keys().toList().sorted()
    }

    /**
     * Parses a 4-byte capability bitmap response for a given base PID and associates it with an ECU.
     *
     * @param ecuId The responding CAN ID (e.g. "7E8", "7E9") or null for global.
     * @param basePid e.g. 0x00 for PID 0100, 0x20 for PID 0120 ... 0xE0 for PID 01E0
     * @param dataBytes 4 bytes returned by the ECU
     * @return Boolean indicating if the next range is supported (LSB of byte 3). For basePid >= 0xE0, always returns false.
     */
    fun parseCapabilityBitmap(basePid: Int, dataBytes: List<Int>, ecuId: String? = null): Boolean {
        if (dataBytes.size < 4) return false

        val b0 = dataBytes[0] and 0xFF
        val b1 = dataBytes[1] and 0xFF
        val b2 = dataBytes[2] and 0xFF
        val b3 = dataBytes[3] and 0xFF

        val bitmap32 = ((b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()) and 0xFFFFFFFFL

        val targetEcuMap = if (ecuId != null) {
            ecuCapabilityMap.getOrPut(ecuId.uppercase()) { ConcurrentHashMap() }
        } else null

        for (i in 1..32) {
            val pidNum = basePid + i
            // Critical rule: Standard PID space ends at 0xFF. Never generate PID 100 (256)
            if (pidNum > 0xFF) continue

            val pidHex = "%02X".format(pidNum)
            val fullId = "01$pidHex"

            // Bit 31 is PID 1, Bit 0 is PID 32
            val bitMask = 1L shl (32 - i)
            val isSupported = (bitmap32 and bitMask) != 0L

            val status = if (isSupported) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED

            if (targetEcuMap != null) {
                targetEcuMap[pidHex] = status
                targetEcuMap[fullId] = status
            }

            // In global map, if any ECU supports the PID, it is marked SUPPORTED
            val currentGlobal = capabilityMap[pidHex]
            if (currentGlobal != CapabilityStatus.SUPPORTED) {
                capabilityMap[pidHex] = status
                capabilityMap[fullId] = status
            }
        }

        _capabilitiesFlow.value = capabilityMap.toMap()
        _ecuCapabilitiesFlow.value = ecuCapabilityMap.mapValues { it.value.toMap() }

        // Bit 0 (PID basePid + 32) indicates if next 32-PID range is supported (only for basePid < 0xE0)
        return (basePid < 0xE0) && ((b3 and 0x01) != 0)
    }

    fun markPidStatus(pidId: String, status: CapabilityStatus) {
        val clean = pidId.uppercase().removePrefix("01")
        capabilityMap[clean] = status
        capabilityMap[pidId.uppercase()] = status
        _capabilitiesFlow.value = capabilityMap.toMap()
    }

    fun markPidStatus(ecuId: String, pidId: String, status: CapabilityStatus) {
        val clean = pidId.uppercase().removePrefix("01")
        val ecuMap = ecuCapabilityMap.getOrPut(ecuId.uppercase()) { ConcurrentHashMap() }
        ecuMap[clean] = status
        ecuMap[pidId.uppercase()] = status

        // Update global map if supported
        if (status == CapabilityStatus.SUPPORTED) {
            capabilityMap[clean] = CapabilityStatus.SUPPORTED
            capabilityMap[pidId.uppercase()] = CapabilityStatus.SUPPORTED
        }

        _capabilitiesFlow.value = capabilityMap.toMap()
        _ecuCapabilitiesFlow.value = ecuCapabilityMap.mapValues { it.value.toMap() }
    }

    fun setDiscoveryInProgress(inProgress: Boolean) {
        _discoveryInProgress.value = inProgress
    }
}
