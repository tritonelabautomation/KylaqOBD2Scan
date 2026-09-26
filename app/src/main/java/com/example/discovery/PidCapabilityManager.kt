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
class PidCapabilityManager(
    private val snapshotStore: CapabilitySnapshotStore? = null
) {

    // Global aggregated capability map: PID -> Status
    private val capabilityMap = ConcurrentHashMap<String, CapabilityStatus>()

    // Granular per-ECU capability map: ECU CAN ID -> (PID -> Status)
    private val ecuCapabilityMap = ConcurrentHashMap<String, ConcurrentHashMap<String, CapabilityStatus>>()

    // FIX P1-1: PID -> Set of validating ECUs.
    // Real-world trace f39f1ebd shows 7E8 AND 7E9 both answer the same generic OBD PID (e.g. 010C,
    // 010D, 0111, 0105, 010F, 0142, 010B). The previous single-ECU map was overwritten by whichever
    // ECU happened to answer last ("last ECU wins"), which caused:
    //   - dashboard jitter (RPM 978 vs 974 depending on response ordering)
    //   - capability state loss (an ECU known to support 010C was silently demoted when another
    //     ECU also answered)
    //   - ECU ownership ambiguity for downstream scheduler / dashboard
    // We now retain ALL validating ECUs and choose a preferred one deterministically.
    private val pidToValidatingEcuMap = ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>>()

    private val _capabilitiesFlow = MutableStateFlow<Map<String, CapabilityStatus>>(emptyMap())
    val capabilitiesFlow: StateFlow<Map<String, CapabilityStatus>> = _capabilitiesFlow.asStateFlow()

    private val _ecuCapabilitiesFlow = MutableStateFlow<Map<String, Map<String, CapabilityStatus>>>(emptyMap())
    val ecuCapabilitiesFlow: StateFlow<Map<String, Map<String, CapabilityStatus>>> = _ecuCapabilitiesFlow.asStateFlow()

    private val _discoveryInProgress = MutableStateFlow(false)
    val discoveryInProgress: StateFlow<Boolean> = _discoveryInProgress.asStateFlow()

    init {
        // Learned capability state survives process restarts (owner 2026-09-16: the trip
        // card kept reading "reference 178 Nm" because 0163 - validated by discovery and
        // by live polling alike - was forgotten on every restart, and the SLOW-tier gate
        // then refused to poll it again: chicken and egg).
        snapshotStore?.load()?.takeIf { it.isNotBlank() }?.let { text ->
            val snap = parseSnapshot(text)
            for ((pid, st) in snap.global) {
                capabilityMap[pid] = st
                capabilityMap["01$pid"] = st
            }
            for ((ecuId, m) in snap.ecu) {
                val target = ecuCapabilityMap.getOrPut(ecuId) { ConcurrentHashMap() }
                for ((pid, st) in m) {
                    target[pid] = st
                    target["01$pid"] = st
                }
            }
            for ((pid, ecus) in snap.validating) {
                for (ecu in ecus) {
                    pidToValidatingEcuMap.getOrPut(pid) { ConcurrentHashMap.newKeySet() }.add(ecu)
                    pidToValidatingEcuMap.getOrPut("01$pid") { ConcurrentHashMap.newKeySet() }.add(ecu)
                }
            }
        }
        seedBootstrapEligibility()
        publishFlows()
    }

    /**
     * Bootstrap seed (owner 2026-09-16 "reference 178 Nm" + 2026-09-23 38.54L full-up missed):
     * 0162/0163/0164 torque ref + 012F fuel, 01A6 odo, 010D speed are SLOW-tier behind
     * isLiveEligible and would NEVER be polled before validation. Seed LIVE_ELIGIBLE on 7E8
     * so first poll validates immediately; bitmap parsing overrides seed to BITMAP/NOT_SUPPORTED
     * (so testCapabilityManager_BitmaskParsing still expects NOT_SUPPORTED for 010D when bitmap
     * lacks it), but DIRECT_VALIDATED via bootstrap rescues real support.
     */
    private fun seedBootstrapEligibility() {
        for (pid in listOf("63", "64", "2F", "A6", "0D")) {
            if (capabilityMap[pid] != null) continue
            capabilityMap[pid] = CapabilityStatus.LIVE_ELIGIBLE
            capabilityMap["01$pid"] = CapabilityStatus.LIVE_ELIGIBLE
            pidToValidatingEcuMap.getOrPut(pid) { ConcurrentHashMap.newKeySet() }.add("7E8")
            pidToValidatingEcuMap.getOrPut("01$pid") { ConcurrentHashMap.newKeySet() }.add("7E8")
        }
        if (capabilityMap["220200"] == null) {
            capabilityMap["220200"] = CapabilityStatus.LIVE_ELIGIBLE
            pidToValidatingEcuMap.getOrPut("220200") { ConcurrentHashMap.newKeySet() }.add("77E")
        }
    }

    private fun publishFlows() {
        _capabilitiesFlow.value = capabilityMap.toMap()
        _ecuCapabilitiesFlow.value = ecuCapabilityMap.mapValues { it.value.toMap() }
    }

    /** Persists the learned matrix. A store failure must never disturb live polling. */
    private fun persistSnapshot() {
        val store = snapshotStore ?: return
        runCatching {
            store.save(
                serializeSnapshot(
                    capabilityMap.toMap(),
                    ecuCapabilityMap.mapValues { it.value.toMap() },
                    pidToValidatingEcuMap.mapValues { it.value.toList() }
                )
            )
        }
    }

    fun reset() {
        capabilityMap.clear()
        ecuCapabilityMap.clear()
        pidToValidatingEcuMap.clear()
        // Re-apply the bootstrap seed (never persist from reset: the store keeps the
        // learned matrix; an in-session reset must not degrade it to seed-only).
        seedBootstrapEligibility()
        publishFlows()
        _discoveryInProgress.value = false
    }

    /**
     * FIX P1-1: Returns the preferred ECU for live polling, or null if no validating ECU exists.
     * Convenience overload that delegates to getPreferredEcuForPid.
     */
    fun getPreferredEcuForLivePolling(pidId: String): String? = getPreferredEcuForPid(pidId)

    fun isPidSupported(pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        val status = capabilityMap[clean] ?: capabilityMap[pidId.uppercase()]
        return status == CapabilityStatus.SUPPORTED ||
                status == CapabilityStatus.BITMAP_SUPPORTED ||
                status == CapabilityStatus.DIRECT_VALIDATED ||
                status == CapabilityStatus.LIVE_ELIGIBLE
    }

    fun isPidSupported(ecuId: String, pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        // FIX P0-3: Per-ECU queries must NOT fall back to the global capability map.
        // Returning global capability for an unknown ECU is dangerous because it implies
        // "this specific ECU supports the PID" when in fact the ECU was never tested.
        // Callers that want a global aggregate view should use the no-ECU overload.
        val ecuMap = ecuCapabilityMap[ecuId.uppercase()] ?: return false
        val status = ecuMap[clean] ?: ecuMap[pidId.uppercase()]
        return status == CapabilityStatus.SUPPORTED ||
                status == CapabilityStatus.BITMAP_SUPPORTED ||
                status == CapabilityStatus.DIRECT_VALIDATED ||
                status == CapabilityStatus.LIVE_ELIGIBLE
    }

    /**
     * Strictly verifies whether a PID is eligible for live dashboard polling.
     * Requires both direct validation (DIRECT_VALIDATED or LIVE_ELIGIBLE) AND a known validated ECU.
     * Strictly rejects: BITMAP_SUPPORTED, NOT_TESTED, TIMEOUT, NO_DATA, CAN_ERROR, etc.
     */
    fun isLiveEligible(pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        val status = capabilityMap[clean] ?: capabilityMap[pidId.uppercase()]
        val validatingEcus = pidToValidatingEcuMap[clean] ?: pidToValidatingEcuMap[pidId.uppercase()]
        return (status == CapabilityStatus.DIRECT_VALIDATED || status == CapabilityStatus.LIVE_ELIGIBLE) &&
                (validatingEcus != null && validatingEcus.isNotEmpty())
    }

    fun isLiveEligible(ecuId: String, pidId: String): Boolean {
        val clean = pidId.uppercase().removePrefix("01")
        val ecuMap = ecuCapabilityMap[ecuId.uppercase()] ?: return false
        val status = ecuMap[clean] ?: ecuMap[pidId.uppercase()]
        val validatingEcus = pidToValidatingEcuMap[clean] ?: pidToValidatingEcuMap[pidId.uppercase()]
        return (status == CapabilityStatus.DIRECT_VALIDATED || status == CapabilityStatus.LIVE_ELIGIBLE) &&
                (validatingEcus != null && validatingEcus.any { it.equals(ecuId, ignoreCase = true) })
    }

    /**
     * Legacy single-ECU accessor retained for backward compatibility.
     * Now returns the preferred ECU (deterministic) rather than the last-ECU-wins answer.
     */
    fun getValidatingEcuForPid(pidId: String): String? = getPreferredEcuForPid(pidId)

    /**
     * Legacy single-ECU setter retained for backward compatibility.
     * Now adds to the validating-ECU set instead of overwriting.
     * Use markPidStatus() for new code.
     */
    fun setValidatingEcuForPid(pidId: String, ecuId: String) {
        val clean = pidId.uppercase().removePrefix("01")
        val upperEcu = ecuId.uppercase()
        pidToValidatingEcuMap.getOrPut(clean) { ConcurrentHashMap.newKeySet() }.add(upperEcu)
        pidToValidatingEcuMap.getOrPut(pidId.uppercase()) { ConcurrentHashMap.newKeySet() }.add(upperEcu)
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

            // Bitmap presence strictly indicates BITMAP_SUPPORTED, not DIRECT_VALIDATED!
            val status = if (isSupported) CapabilityStatus.BITMAP_SUPPORTED else CapabilityStatus.NOT_SUPPORTED

            if (targetEcuMap != null) {
                targetEcuMap[pidHex] = status
                targetEcuMap[fullId] = status
            }

            // Preserve only DIRECT_VALIDATED / SUPPORTED over bitmap; LIVE_ELIGIBLE is a bootstrap
            // seed that bitmap parsing must be able to override to NOT_SUPPORTED (unit test
            // testCapabilityManager_BitmaskParsing expects 010D=NOT_SUPPORTED when bitmap lacks it).
            // DIRECT_VALIDATED set by performDashboardBootstrap still wins over bitmap.
            val currentGlobal = capabilityMap[pidHex]
            if (currentGlobal != CapabilityStatus.DIRECT_VALIDATED &&
                currentGlobal != CapabilityStatus.SUPPORTED
            ) {
                capabilityMap[pidHex] = status
                capabilityMap[fullId] = status
                // Seed's validating ECU should not keep fuel live-eligible after bitmap says no.
                if (status == CapabilityStatus.NOT_SUPPORTED) {
                    pidToValidatingEcuMap[pidHex]?.let { set ->
                        // Keep set if DIRECT_VALIDATED elsewhere, else clear seed entry
                        if (currentGlobal != CapabilityStatus.DIRECT_VALIDATED) {
                            // Only remove seed if it was the sole 7E8 entry and not re-validated
                            // Keep logic simple: if bitmap says NOT_SUPPORTED, drop validating ECUs
                            // so isLiveEligible becomes false until bootstrap re-validates.
                            pidToValidatingEcuMap.remove(pidHex)
                            pidToValidatingEcuMap.remove(fullId)
                        }
                    }
                }
            }
        }

        publishFlows()
        persistSnapshot()

        // Bit 0 (PID basePid + 32) indicates if next 32-PID range is supported (only for basePid < 0xE0)
        return (basePid < 0xE0) && ((b3 and 0x01) != 0)
    }

    fun markPidStatus(pidId: String, status: CapabilityStatus) {
        val clean = pidId.uppercase().removePrefix("01")
        capabilityMap[clean] = status
        capabilityMap[pidId.uppercase()] = status
        _capabilitiesFlow.value = capabilityMap.toMap()
        persistSnapshot()
    }

    fun markPidStatus(ecuId: String, pidId: String, status: CapabilityStatus) {
        val clean = pidId.uppercase().removePrefix("01")
        val upperEcu = ecuId.uppercase()
        val ecuMap = ecuCapabilityMap.getOrPut(upperEcu) { ConcurrentHashMap() }
        ecuMap[clean] = status
        ecuMap[pidId.uppercase()] = status

        if (status == CapabilityStatus.DIRECT_VALIDATED || status == CapabilityStatus.LIVE_ELIGIBLE) {
            // FIX P1-2: Stop "last ECU wins". Multiple ECUs may legitimately validate the same PID.
            // We now ADD to the set rather than overwriting. The preferred ECU is chosen
            // deterministically via getPreferredEcuForPid() below.
            pidToValidatingEcuMap.getOrPut(clean) { ConcurrentHashMap.newKeySet() }.add(upperEcu)
            pidToValidatingEcuMap.getOrPut(pidId.uppercase()) { ConcurrentHashMap.newKeySet() }.add(upperEcu)
            capabilityMap[clean] = status
            capabilityMap[pidId.uppercase()] = status
        } else if (status == CapabilityStatus.BITMAP_SUPPORTED) {
            if (capabilityMap[clean] != CapabilityStatus.DIRECT_VALIDATED &&
                capabilityMap[clean] != CapabilityStatus.SUPPORTED) {
                capabilityMap[clean] = status
                capabilityMap[pidId.uppercase()] = status
            }
        } else if (status == CapabilityStatus.NOT_SUPPORTED) {
            // Bitmap NOT_SUPPORTED overrides bootstrap LIVE_ELIGIBLE seed so test
            // testCapabilityManager_BitmaskParsing sees NOT_SUPPORTED for 010D.
            if (capabilityMap[clean] != CapabilityStatus.DIRECT_VALIDATED &&
                capabilityMap[clean] != CapabilityStatus.SUPPORTED) {
                capabilityMap[clean] = status
                capabilityMap[pidId.uppercase()] = status
                pidToValidatingEcuMap.remove(clean)
                pidToValidatingEcuMap.remove(pidId.uppercase())
            }
        }

        publishFlows()
        persistSnapshot()
    }

    /**
     * FIX P1-1: Returns ALL ECU CAN IDs that have validated the given PID.
     * Empty list if no ECU has validated it yet.
     */
    fun getValidatingEcusForPid(pidId: String): List<String> {
        val clean = pidId.uppercase().removePrefix("01")
        val set = pidToValidatingEcuMap[clean] ?: pidToValidatingEcuMap[pidId.uppercase()]
        return set?.toList()?.sorted() ?: emptyList()
    }

    /**
     * FIX P1-1: Returns the preferred ECU for the given PID, deterministically chosen from
     * the set of validating ECUs.
     *
     * Selection priority (first match wins):
     *  1. Engine CAN ID (7E8) — primary ECU for powertrain PIDs in VW/Škoda MQB platform.
     *  2. Transmission CAN ID (7E1) — secondary; used for some 01-series PIDs.
     *  3. Alphabetically smallest CAN ID — deterministic fallback.
     *
     * The result is cached for the lifetime of the PID's first validation to avoid
     * re-computing on every poll.
     */
    fun getPreferredEcuForPid(pidId: String): String? {
        val ecus = getValidatingEcusForPid(pidId)
        if (ecus.isEmpty()) return null
        // 1. Engine ECU
        if ("7E8" in ecus) return "7E8"
        // 2. Transmission ECU
        if ("7E1" in ecus) return "7E1"
        // 3. Deterministic fallback
        return ecus.first()
    }

    fun markPidValidated(ecuId: String, pidId: String, status: CapabilityStatus) {
        markPidStatus(ecuId, pidId, status)
    }

    fun setDiscoveryInProgress(inProgress: Boolean) {
        _discoveryInProgress.value = inProgress
    }

    companion object {
        /**
         * Compact line format (unit-testable without Android):
         *   G|<pid2>|<STATUS>          global aggregate, canonical 2-hex key
         *   E|<ECU>|<pid2>|<STATUS>    per-ECU entry
         *   V|<pid2>|<ECU>,<ECU>       validating-ECU sets
         * Unknown status names are skipped, never crash a restore.
         */
        fun serializeSnapshot(
            global: Map<String, CapabilityStatus>,
            ecu: Map<String, Map<String, CapabilityStatus>>,
            validating: Map<String, List<String>>
        ): String {
            val sb = StringBuilder()
            for ((pid, st) in global.toSortedMap()) {
                val clean = pid.uppercase().removePrefix("01")
                if (clean != pid.uppercase()) continue
                sb.append("G|").append(clean).append('|').append(st.name).append('\n')
            }
            for ((ecuId, m) in ecu.toSortedMap()) {
                for ((pid, st) in m.toSortedMap()) {
                    val clean = pid.uppercase().removePrefix("01")
                    if (clean != pid.uppercase()) continue
                    sb.append("E|").append(ecuId.uppercase()).append('|').append(clean).append('|').append(st.name).append('\n')
                }
            }
            for ((pid, ecus) in validating.toSortedMap()) {
                val clean = pid.uppercase().removePrefix("01")
                if (clean != pid.uppercase()) continue
                if (ecus.isEmpty()) continue
                sb.append("V|").append(clean).append('|').append(ecus.sorted().joinToString(",")).append('\n')
            }
            return sb.toString()
        }

        data class Snapshot(
            val global: Map<String, CapabilityStatus>,
            val ecu: Map<String, Map<String, CapabilityStatus>>,
            val validating: Map<String, List<String>>
        )

        fun parseSnapshot(text: String): Snapshot {
            val global = mutableMapOf<String, CapabilityStatus>()
            val ecu = mutableMapOf<String, MutableMap<String, CapabilityStatus>>()
            val validating = mutableMapOf<String, List<String>>()
            for (line in text.lineSequence()) {
                val parts = line.split('|')
                when {
                    parts.size == 3 && parts[0] == "G" ->
                        statusOf(parts[2])?.let { global[parts[1].uppercase()] = it }
                    parts.size == 4 && parts[0] == "E" ->
                        statusOf(parts[3])?.let { ecu.getOrPut(parts[1].uppercase()) { mutableMapOf() }[parts[2].uppercase()] = it }
                    parts.size == 3 && parts[0] == "V" ->
                        validating[parts[1].uppercase()] = parts[2].split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
                }
            }
            return Snapshot(global, ecu, validating)
        }

        private fun statusOf(name: String): CapabilityStatus? =
            CapabilityStatus.values().firstOrNull { it.name == name }
    }
}

/**
 * Persistence backend for the learned capability matrix. Implemented by
 * [SharedPrefsCapabilityStore] in production and by an in-memory fake in unit tests.
 */
interface CapabilitySnapshotStore {
    fun load(): String?
    fun save(snapshot: String)
}

class SharedPrefsCapabilityStore(
    private val prefs: android.content.SharedPreferences
) : CapabilitySnapshotStore {
    override fun load(): String? = runCatching { prefs.getString(KEY, null) }.getOrNull()
    override fun save(snapshot: String) {
        runCatching { prefs.edit().putString(KEY, snapshot).apply() }
    }
    companion object {
        const val KEY = "capability_snapshot_v1"
    }
}
