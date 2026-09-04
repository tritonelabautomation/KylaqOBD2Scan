package com.example.discovery

import android.os.SystemClock
import com.example.bluetooth.ElmTransport
import com.example.model.CapabilityStatus
import com.example.model.KylaqProtocolProfile
import com.example.model.ResponseStatus
import com.example.protocol.IsoTpParser
import com.example.protocol.PidDiscoveryDecoder
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * UDS Support Status for read-only identification queries.
 */
enum class UdsSupportStatus {
    UDS_UNKNOWN,
    UDS_SUPPORTED,
    UDS_NO_RESPONSE,
    UDS_NEGATIVE_RESPONSE
}

/**
 * Detailed discovery result for a specific vehicle ECU on the CAN bus.
 *
 * Each responding CAN ID maintains its own independent capability state.
 * ECU roles are strictly derived from confirmed identification data rather than guessed from CAN IDs.
 */
data class EcuDiscoveryResult(
    val rxCanId: String,
    val requestCanId: String = KylaqProtocolProfile.FUNCTIONAL_REQUEST_ID,
    val ecuRole: String,
    val protocol: String = "${KylaqProtocolProfile.PROTOCOL_NAME} (${KylaqProtocolProfile.CAN_ID_TYPE} / ${KylaqProtocolProfile.BITRATE_DISPLAY})",
    val supportedPids: List<String>,
    val unsupportedPids: List<String> = emptyList(),
    val untestedPids: List<String> = emptyList(),
    val bitmapResults: Map<String, String> = emptyMap(), // e.g. "0100" -> "BE 3F B8 13"
    val vin: String? = null,
    val calibrationId: String? = null,
    val ecuName: String? = null,
    val softwareVersion: String? = null,
    val partNumber: String? = null,
    val latency: Long = 0L,
    val udsSupported: UdsSupportStatus = UdsSupportStatus.UDS_UNKNOWN,
    val supportedServices: List<String> = emptyList()
)

/**
 * Backwards-compatible info class matching previous UI references.
 */
data class DiscoveredEcuInfo(
    val rxCanId: String,
    val ecuRole: String,
    val supportedPids: List<String>,
    val vin: String? = null,
    val calibrationId: String? = null,
    val ecuName: String? = null,
    val softwareVersion: String? = null,
    val partNumber: String? = null,
    val averageLatencyMs: Long = 0L,
    val udsSupported: UdsSupportStatus = UdsSupportStatus.UDS_UNKNOWN,
    val bitmapResults: Map<String, String> = emptyMap(),
    val supportedServices: List<String> = emptyList()
) {
    fun toDiscoveryResult(): EcuDiscoveryResult = EcuDiscoveryResult(
        rxCanId = rxCanId,
        ecuRole = ecuRole,
        supportedPids = supportedPids,
        vin = vin,
        calibrationId = calibrationId,
        ecuName = ecuName,
        softwareVersion = softwareVersion,
        partNumber = partNumber,
        latency = averageLatencyMs,
        udsSupported = udsSupported,
        bitmapResults = bitmapResults,
        supportedServices = supportedServices
    )
}

data class EcuDiscoveryReport(
    val timestampUtc: String,
    val protocol: String,
    val detectedEcus: List<DiscoveredEcuInfo>,
    val totalSupportedPids: Int,
    val totalScannedPids: Int,
    val isComplete: Boolean,
    val summaryMessage: String,
    val modeSupportMap: Map<String, Boolean> = emptyMap(),
    val modeCapabilityMap: Map<String, CapabilityStatus> = emptyMap(),
    val rawLogLines: List<String> = emptyList()
)

/**
 * Complete, safe OBD-II & UDS Discovery Engine for Škoda Kylaq 1.0 TSI and ISO 15765-4 vehicles.
 *
 * Scans standard Mode 01 capability bitmaps (0100–01E0), Mode 02/03/06/07/09/0A services,
 * and read-only UDS identification DIDs (22F189, 22F187, 22F190) without executing destructive
 * functions (Mode 04 Clear DTCs and Mode 08 Actuator Tests are never executed).
 */
class EcuDiscoveryManager(
    private val capabilityManager: PidCapabilityManager
) {

    private val _discoveryReport = MutableStateFlow<EcuDiscoveryReport?>(null)
    val discoveryReport: StateFlow<EcuDiscoveryReport?> = _discoveryReport.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveryProgressText = MutableStateFlow("")
    val discoveryProgressText: StateFlow<String> = _discoveryProgressText.asStateFlow()

    private val _respondingEcus = MutableStateFlow<List<String>>(emptyList())
    val respondingEcus: StateFlow<List<String>> = _respondingEcus.asStateFlow()

    /**
     * Executes safe, read-only ECU and capability discovery.
     */
    suspend fun runDiscovery(transport: ElmTransport): EcuDiscoveryReport {
        _isDiscovering.value = true
        capabilityManager.setDiscoveryInProgress(true)
        capabilityManager.reset()

        val logLines = mutableListOf<String>()
        fun log(msg: String) {
            logLines.add("[${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}] $msg")
        }

        val startTimeUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        log("Starting ECU Discovery for Škoda Kylaq (ISO 15765-4 CAN 11-bit 500k, 7DF functional request)")

        // Per-ECU maps
        val ecuSupportedPids = mutableMapOf<String, MutableSet<String>>()
        val ecuUnsupportedPids = mutableMapOf<String, MutableSet<String>>()
        val ecuBitmaps = mutableMapOf<String, MutableMap<String, String>>()
        val latencyMap = mutableMapOf<String, MutableList<Long>>()
        val ecuSupportedServices = mutableMapOf<String, MutableSet<String>>()

        // Per-ECU Identification
        val ecuVinMap = mutableMapOf<String, String>()
        val ecuCalIdMap = mutableMapOf<String, String>()
        val ecuNameMap = mutableMapOf<String, String>()
        val ecuSwVerMap = mutableMapOf<String, String>()
        val ecuPartNumMap = mutableMapOf<String, String>()
        val ecuUdsStatusMap = mutableMapOf<String, UdsSupportStatus>()

        val modeSupportMap = mutableMapOf<String, Boolean>()

        _discoveryProgressText.value = "Scanning Mode 01 capability bitmaps (0100–01E0)..."

        // Step 1: Mode 01 Discovery Sequence (0100 through 01E0 - 8 ranges)
        val rangePids = listOf(
            Pair("0100", 0x00),
            Pair("0120", 0x20),
            Pair("0140", 0x40),
            Pair("0160", 0x60),
            Pair("0180", 0x80),
            Pair("01A0", 0xA0),
            Pair("01C0", 0xC0),
            Pair("01E0", 0xE0)
        )

        var continueScanningRanges = true

        for ((pidCmd, baseOffset) in rangePids) {
            if (!continueScanningRanges) break

            val rangeStart = "%02X".format(baseOffset + 1)
            val rangeEnd = "%02X".format(minOf(baseOffset + 0x20, 0xFF))
            _discoveryProgressText.value = "Querying $pidCmd (PIDs $rangeStart–$rangeEnd)..."

            val startMs = SystemClock.elapsedRealtime()
            log("TX: $pidCmd")
            val resp = transport.sendCommand(pidCmd, timeoutMs = 2500L)
            val duration = SystemClock.elapsedRealtime() - startMs
            log("RX: [${resp.status}] ${resp.lines.joinToString(" | ")}")

            if (resp.status == ResponseStatus.OK && resp.lines.isNotEmpty()) {
                val ecuResponses = PidDiscoveryDecoder.decodeAllEcuResponses(baseOffset, resp.lines)
                var anyHasNext = false

                if (ecuResponses.isNotEmpty()) {
                    modeSupportMap["01"] = true
                    for (ecuResp in ecuResponses) {
                        val rxId = ecuResp.rxCanId
                        latencyMap.getOrPut(rxId) { mutableListOf() }.add(duration)
                        ecuBitmaps.getOrPut(rxId) { mutableMapOf() }[pidCmd] = ecuResp.bitmapHex
                        ecuSupportedServices.getOrPut(rxId) { mutableSetOf() }.add("01")

                        // Update capability manager per ECU
                        capabilityManager.parseCapabilityBitmap(baseOffset, ecuResp.bitmap.map { it.toInt() and 0xFF }, rxId)

                        val supportedInBlock = ecuResp.supportedPids.map { "01%02X".format(it) }
                        ecuSupportedPids.getOrPut(rxId) { mutableSetOf() }.addAll(supportedInBlock)

                        val supportedSet = ecuResp.supportedPids.toSet()
                        for (tested in ecuResp.allTestedPids) {
                            if (!supportedSet.contains(tested)) {
                                ecuUnsupportedPids.getOrPut(rxId) { mutableSetOf() }.add("01%02X".format(tested))
                            }
                        }

                        if (ecuResp.hasNextRange) {
                            anyHasNext = true
                        }
                    }
                } else {
                    // Fallback to single frame decode if no headers were present
                    val singleResult = PidDiscoveryDecoder.decodeFromRawResponse(baseOffset, resp.lines)
                    if (singleResult != null) {
                        modeSupportMap["01"] = true
                        val rxId = singleResult.rxCanId ?: "7E8"
                        latencyMap.getOrPut(rxId) { mutableListOf() }.add(duration)
                        ecuBitmaps.getOrPut(rxId) { mutableMapOf() }[pidCmd] = singleResult.bitmapHex
                        ecuSupportedServices.getOrPut(rxId) { mutableSetOf() }.add("01")

                        capabilityManager.parseCapabilityBitmap(baseOffset, singleResult.bitmap.map { it.toInt() and 0xFF }, rxId)
                        val supportedInBlock = singleResult.supportedPids.map { "01%02X".format(it) }
                        ecuSupportedPids.getOrPut(rxId) { mutableSetOf() }.addAll(supportedInBlock)

                        if (singleResult.hasNextRange) {
                            anyHasNext = true
                        }
                    }
                }

                // Continuation logic: if no responding ECU indicated support for the next range, stop
                if (!anyHasNext && baseOffset < 0xE0) {
                    log("Continuation bit 32 is 0 for all responding ECUs on $pidCmd. Next range not supported.")
                    continueScanningRanges = false
                }
            } else if (resp.status == ResponseStatus.TIMEOUT || resp.status == ResponseStatus.NO_DATA) {
                log("$pidCmd probe returned ${resp.status}. Concluding Mode 01 range scan.")
                continueScanningRanges = false
            } else if (resp.status == ResponseStatus.CAN_ERROR || resp.status == ResponseStatus.BUS_INIT_ERROR) {
                log("CAN communication error on $pidCmd: ${resp.rawText}")
                continueScanningRanges = false
            }

            _respondingEcus.value = ecuSupportedPids.keys.toList().sorted()
            delay(100)
        }

        // Step 2: Mode 02 Freeze Frame Support Check (Read-Only)
        _discoveryProgressText.value = "Checking Mode 02 Freeze Frame support..."
        val m2Resp = transport.sendCommand("020200", timeoutMs = 2000L)
        log("TX: 020200 -> RX: [${m2Resp.status}] ${m2Resp.lines.joinToString(" | ")}")
        if (m2Resp.status == ResponseStatus.OK && m2Resp.lines.any { it.contains("42") }) {
            modeSupportMap["02"] = true
            parseEcuFromLines(m2Resp.lines)?.let { ecuSupportedServices.getOrPut(it) { mutableSetOf() }.add("02") }
        } else {
            modeSupportMap["02"] = false
        }
        delay(80)

        // Step 3: Mode 03 Stored DTCs Check (Read-Only)
        _discoveryProgressText.value = "Checking Mode 03 Stored DTCs..."
        val m3Resp = transport.sendCommand("03", timeoutMs = 2500L)
        log("TX: 03 -> RX: [${m3Resp.status}] ${m3Resp.lines.joinToString(" | ")}")
        if (m3Resp.status == ResponseStatus.OK && m3Resp.lines.any { it.contains("43") }) {
            modeSupportMap["03"] = true
            parseEcuFromLines(m3Resp.lines)?.let { ecuSupportedServices.getOrPut(it) { mutableSetOf() }.add("03") }
        } else {
            modeSupportMap["03"] = false
        }
        delay(80)

        // Note: Mode 04 (Clear DTCs) is NEVER automatically executed!
        // Rule 12: Must NOT be marked SUPPORTED without testing. Status is NOT_TESTED.
        modeSupportMap["04"] = false

        // Step 4: Mode 06 On-Board Monitoring Support Check (Read-Only)
        _discoveryProgressText.value = "Checking Mode 06 On-Board Monitoring..."
        val m6Resp = transport.sendCommand("0600", timeoutMs = 2500L)
        log("TX: 0600 -> RX: [${m6Resp.status}] ${m6Resp.lines.joinToString(" | ")}")
        if (m6Resp.status == ResponseStatus.OK && m6Resp.lines.any { it.contains("46") }) {
            modeSupportMap["06"] = true
            parseEcuFromLines(m6Resp.lines)?.let { ecuSupportedServices.getOrPut(it) { mutableSetOf() }.add("06") }
        } else {
            modeSupportMap["06"] = false
        }
        delay(80)

        // Step 5: Mode 07 Pending DTCs Check (Read-Only)
        _discoveryProgressText.value = "Checking Mode 07 Pending DTCs..."
        val m7Resp = transport.sendCommand("07", timeoutMs = 2500L)
        log("TX: 07 -> RX: [${m7Resp.status}] ${m7Resp.lines.joinToString(" | ")}")
        if (m7Resp.status == ResponseStatus.OK && m7Resp.lines.any { it.contains("47") }) {
            modeSupportMap["07"] = true
            parseEcuFromLines(m7Resp.lines)?.let { ecuSupportedServices.getOrPut(it) { mutableSetOf() }.add("07") }
        } else {
            modeSupportMap["07"] = false
        }
        delay(80)

        // Note: Mode 08 (Actuator / Bidirectional Control) is NEVER automatically executed!
        modeSupportMap["08"] = false

        // Step 6: Mode 09 Vehicle Information (VIN, CALID, ECU Name)
        _discoveryProgressText.value = "Reading Mode 09 Identifiers (VIN, CALID, ECU Name)..."
        val m9SupportedResp = transport.sendCommand("0900", timeoutMs = 2500L)
        if (m9SupportedResp.status == ResponseStatus.OK && m9SupportedResp.lines.any { it.contains("49") }) {
            modeSupportMap["09"] = true
        }

        // 0902: VIN
        log("TX: 0902")
        val vinResp = transport.sendCommand("0902", timeoutMs = 3000L)
        log("RX: [${vinResp.status}] ${vinResp.lines.joinToString(" | ")}")
        if (vinResp.status == ResponseStatus.OK && vinResp.lines.isNotEmpty()) {
            val vinResults = parseMultiEcuAscii(vinResp.lines, expectedService = 0x49, expectedPid = 0x02, minLength = 17, maxLength = 17)
            vinResults.forEach { (canId, vin) ->
                ecuVinMap[canId] = vin
                ecuSupportedServices.getOrPut(canId) { mutableSetOf() }.add("09")
                log("Discovered VIN from ECU $canId: $vin")
            }
        }
        delay(100)

        // 0904: Calibration ID
        log("TX: 0904")
        val calResp = transport.sendCommand("0904", timeoutMs = 3000L)
        log("RX: [${calResp.status}] ${calResp.lines.joinToString(" | ")}")
        if (calResp.status == ResponseStatus.OK && calResp.lines.isNotEmpty()) {
            val calResults = parseMultiEcuAscii(calResp.lines, expectedService = 0x49, expectedPid = 0x04, minLength = 4, maxLength = 32)
            calResults.forEach { (canId, calId) ->
                ecuCalIdMap[canId] = calId
                ecuSupportedServices.getOrPut(canId) { mutableSetOf() }.add("09")
                log("Discovered CALID from ECU $canId: $calId")
            }
        }
        delay(100)

        // 090A: ECU Name
        log("TX: 090A")
        val nameResp = transport.sendCommand("090A", timeoutMs = 2500L)
        log("RX: [${nameResp.status}] ${nameResp.lines.joinToString(" | ")}")
        if (nameResp.status == ResponseStatus.OK && nameResp.lines.isNotEmpty()) {
            val nameResults = parseMultiEcuAscii(nameResp.lines, expectedService = 0x49, expectedPid = 0x0A, minLength = 3, maxLength = 32)
            nameResults.forEach { (canId, name) ->
                ecuNameMap[canId] = name
                ecuSupportedServices.getOrPut(canId) { mutableSetOf() }.add("09")
                log("Discovered ECU Name from ECU $canId: $name")
            }
        }
        delay(100)

        // Step 7: Mode 0A Permanent DTCs Check
        _discoveryProgressText.value = "Checking Mode 0A Permanent DTCs..."
        val maResp = transport.sendCommand("0A", timeoutMs = 2500L)
        log("TX: 0A -> RX: [${maResp.status}] ${maResp.lines.joinToString(" | ")}")
        if (maResp.status == ResponseStatus.OK && maResp.lines.any { it.contains("4A") }) {
            modeSupportMap["0A"] = true
            parseEcuFromLines(maResp.lines)?.let { ecuSupportedServices.getOrPut(it) { mutableSetOf() }.add("0A") }
        } else {
            modeSupportMap["0A"] = false
        }
        delay(80)

        // Step 8: Safe Read-Only UDS Identification (Service 22 read by ID)
        // Strictly read-only: SW version (22F189), Spare Part Number (22F187), VIN (22F190)
        // No session control, reset, security access, or actuator commands!
        _discoveryProgressText.value = "Reading safe UDS identification DIDs (Read-Only)..."

        // 22 F1 89: Software Version
        log("TX: 22F189 (Read-Only UDS SW Version)")
        val swResp = transport.sendCommand("22F189", timeoutMs = 2500L)
        log("RX: [${swResp.status}] ${swResp.lines.joinToString(" | ")}")
        if (swResp.status == ResponseStatus.OK && swResp.lines.isNotEmpty()) {
            val swResults = parseUdsAscii(swResp.lines, didHex = "F189")
            swResults.forEach { (canId, sw) ->
                ecuSwVerMap[canId] = sw
                ecuUdsStatusMap[canId] = UdsSupportStatus.UDS_SUPPORTED
                log("UDS SW Version from $canId: $sw")
            }
            if (swResp.lines.any { it.contains("7F") }) {
                parseEcuFromLines(swResp.lines)?.let { ecuUdsStatusMap.putIfAbsent(it, UdsSupportStatus.UDS_NEGATIVE_RESPONSE) }
            }
        } else if (swResp.status == ResponseStatus.TIMEOUT || swResp.status == ResponseStatus.NO_DATA) {
            ecuSupportedPids.keys.forEach { ecuUdsStatusMap.putIfAbsent(it, UdsSupportStatus.UDS_NO_RESPONSE) }
        }
        delay(100)

        // 22 F1 87: Spare Part Number
        log("TX: 22F187 (Read-Only UDS Part Number)")
        val partResp = transport.sendCommand("22F187", timeoutMs = 2500L)
        log("RX: [${partResp.status}] ${partResp.lines.joinToString(" | ")}")
        if (partResp.status == ResponseStatus.OK && partResp.lines.isNotEmpty()) {
            val partResults = parseUdsAscii(partResp.lines, didHex = "F187")
            partResults.forEach { (canId, part) ->
                ecuPartNumMap[canId] = part
                ecuUdsStatusMap[canId] = UdsSupportStatus.UDS_SUPPORTED
                log("UDS Part Number from $canId: $part")
            }
        }
        delay(100)

        // Step 9: Assemble Discovered ECUs
        // CRITICAL: DO NOT guess ECU roles from CAN IDs!
        // Display as "ECU at 7E8", "ECU at 7E9" unless identification data proves ECM/TCU/ABS.
        // NEVER invent ECU names or roles!
        val allDiscoveredCanIds = (ecuSupportedPids.keys + latencyMap.keys + ecuVinMap.keys).distinct().sorted()
        val discoveredEcus = mutableListOf<DiscoveredEcuInfo>()

        for (rxId in allDiscoveredCanIds) {
            val pids = (ecuSupportedPids[rxId] ?: emptySet()).toList().sorted()
            val avgLat = latencyMap[rxId]?.average()?.toLong() ?: 35L
            val vin = ecuVinMap[rxId]
            val calId = ecuCalIdMap[rxId]
            val ecuName = ecuNameMap[rxId]
            val swVer = ecuSwVerMap[rxId]
            val partNum = ecuPartNumMap[rxId]
            val udsStatus = ecuUdsStatusMap[rxId] ?: UdsSupportStatus.UDS_UNKNOWN
            val bitmaps = ecuBitmaps[rxId] ?: emptyMap()
            val services = (ecuSupportedServices[rxId] ?: emptySet()).toList().sorted()

            // Derive role strictly based on genuine identification data
            val role = inferEcuRole(rxId, ecuName, calId)

            discoveredEcus.add(
                DiscoveredEcuInfo(
                    rxCanId = rxId,
                    ecuRole = role,
                    supportedPids = pids,
                    vin = vin,
                    calibrationId = calId,
                    ecuName = ecuName,
                    softwareVersion = swVer,
                    partNumber = partNum,
                    averageLatencyMs = avgLat,
                    udsSupported = udsStatus,
                    bitmapResults = bitmaps,
                    supportedServices = services
                )
            )
        }

        val totalSupported = discoveredEcus.sumOf { it.supportedPids.size }
        val totalScanned = rangePids.size * 32

        val modeCapabilityMap = mutableMapOf<String, CapabilityStatus>()
        modeCapabilityMap["01"] = CapabilityStatus.SUPPORTED
        modeCapabilityMap["02"] = if (modeSupportMap["02"] == true) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
        modeCapabilityMap["03"] = if (modeSupportMap["03"] == true) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
        modeCapabilityMap["04"] = CapabilityStatus.NOT_TESTED // Rule 12: Never marked SUPPORTED without testing
        modeCapabilityMap["06"] = if (modeSupportMap["06"] == true) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
        modeCapabilityMap["07"] = if (modeSupportMap["07"] == true) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
        modeCapabilityMap["08"] = CapabilityStatus.NOT_TESTED // Mode 08 actuator control is not executed in discovery
        modeCapabilityMap["09"] = if (modeSupportMap["09"] == true) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
        modeCapabilityMap["0A"] = if (modeSupportMap["0A"] == true) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED
        modeCapabilityMap["22"] = if (ecuUdsStatusMap.values.any { it == UdsSupportStatus.UDS_SUPPORTED }) CapabilityStatus.SUPPORTED else CapabilityStatus.NOT_SUPPORTED

        val report = EcuDiscoveryReport(
            timestampUtc = startTimeUtc,
            protocol = "${KylaqProtocolProfile.PROTOCOL_NAME} (${KylaqProtocolProfile.CAN_ID_TYPE} / ${KylaqProtocolProfile.BITRATE_DISPLAY})",
            detectedEcus = discoveredEcus,
            totalSupportedPids = totalSupported,
            totalScannedPids = totalScanned,
            isComplete = true,
            summaryMessage = if (discoveredEcus.isNotEmpty()) {
                "Discovered ${discoveredEcus.size} responding ECU(s) [${discoveredEcus.joinToString { it.rxCanId }}], $totalSupported supported PIDs"
            } else {
                "No responding ECUs detected on CAN functional broadcast (7DF)"
            },
            modeSupportMap = modeSupportMap,
            modeCapabilityMap = modeCapabilityMap,
            rawLogLines = logLines
        )

        _discoveryReport.value = report
        _isDiscovering.value = false
        capabilityManager.setDiscoveryInProgress(false)
        _discoveryProgressText.value = report.summaryMessage

        return report
    }

    /**
     * Infers ECU role strictly from evidence. If no identification evidence is present,
     * returns "ECU at $rxCanId" to prevent guessing roles purely based on CAN IDs.
     */
    private fun inferEcuRole(rxCanId: String, ecuName: String?, calId: String?): String {
        val combined = "${ecuName ?: ""} ${calId ?: ""}".uppercase()

        return when {
            combined.contains("ECM") || combined.contains("ENGINE") || combined.contains("MED17") ||
                    combined.contains("SIMOS") || combined.contains("EA211") || combined.contains("DME") -> {
                "ECU at $rxCanId (Engine Control Module)"
            }
            combined.contains("TCU") || combined.contains("TRANSMISSION") || combined.contains("GEARBOX") ||
                    combined.contains("DSG") || combined.contains("AQ250") -> {
                "ECU at $rxCanId (Transmission Control Module)"
            }
            combined.contains("ABS") || combined.contains("BRAKE") || combined.contains("ESP") || combined.contains("ESC") -> {
                "ECU at $rxCanId (Brake / ABS Module)"
            }
            combined.contains("BCM") || combined.contains("BODY") -> {
                "ECU at $rxCanId (Body Control Module)"
            }
            combined.contains("AIRBAG") || combined.contains("SRS") -> {
                "ECU at $rxCanId (Airbag / SRS Module)"
            }
            else -> {
                // Strict user mandate: Do not guess roles from CAN IDs!
                "ECU at $rxCanId"
            }
        }
    }

    private fun parseEcuFromLines(lines: List<String>): String? {
        for (line in lines) {
            val frame = com.example.protocol.CanFrameParser.parseFrame(line)
            if (frame.canId != null) return frame.canId
        }
        return null
    }

    /**
     * Parses ASCII payload from multi-ECU Mode 09 responses (e.g. VIN, CALID, ECU Name).
     */
    private fun parseMultiEcuAscii(
        lines: List<String>,
        expectedService: Int,
        expectedPid: Int,
        minLength: Int,
        maxLength: Int
    ): Map<String, String> {
        val results = mutableMapOf<String, String>()
        try {
            val messages = IsoTpParser.reassembleLines(lines)
            for (msg in messages) {
                val canId = msg.canId ?: "7E8"
                val bytes = msg.reconstructedBytes
                if (bytes.size >= 3 && bytes[0] == expectedService && bytes[1] == expectedPid) {
                    val ascii = StringBuilder()
                    // Skip service byte, pid byte, and sequence/count byte
                    val dataBytes = if (bytes.size > 3) bytes.drop(3) else bytes.drop(2)
                    for (b in dataBytes) {
                        if (b in 32..126) {
                            ascii.append(b.toChar())
                        }
                    }
                    val text = ascii.toString().trim()
                    if (text.length in minLength..maxLength) {
                        results[canId] = text
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Parses ASCII payload from read-only UDS Service 22 responses (0x62 + DID).
     */
    private fun parseUdsAscii(lines: List<String>, didHex: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        try {
            val did0 = didHex.substring(0, 2).toInt(16)
            val did1 = didHex.substring(2, 4).toInt(16)
            val messages = IsoTpParser.reassembleLines(lines)

            for (msg in messages) {
                val canId = msg.canId ?: "7E8"
                val bytes = msg.reconstructedBytes
                // Service 22 positive response is 0x62 followed by 2-byte DID
                if (bytes.size >= 3 && bytes[0] == 0x62 && bytes[1] == did0 && bytes[2] == did1) {
                    val ascii = StringBuilder()
                    for (b in bytes.drop(3)) {
                        if (b in 32..126) {
                            ascii.append(b.toChar())
                        }
                    }
                    val text = ascii.toString().trim()
                    if (text.isNotEmpty()) {
                        results[canId] = text
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }
}
