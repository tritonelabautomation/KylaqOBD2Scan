package com.example.discovery

import com.example.bluetooth.ElmTransport
import com.example.model.ResponseStatus
import com.example.protocol.IsoTpParser
import kotlinx.coroutines.delay

/**
 * Results of comprehensive Adapter Hardware & UDS Capability Verification.
 */
data class AdapterBenchmarkReport(
    val timestampMs: Long = System.currentTimeMillis(),
    val chipIdentifier: String,
    val isStnChip: Boolean,
    val isCloneOrCounterfeit: Boolean,
    val chipVerdict: String,
    val customHeadersSupported: Boolean,
    val flowControlSupported: Boolean,
    val multiFrameIsoTpVerified: Boolean,
    val vinMultiFrameReassembled: String?,
    val module44Status: Module44ProbeStatus,
    val module44RawResponse: String?,
    val testLogs: List<String>
)

enum class Module44ProbeStatus {
    RESPONDED_POSITIVE,
    NRC_OUT_OF_RANGE_31,
    NRC_SECURITY_DENIED_33,
    NRC_SESSION_REQUIRED_7E,
    NRC_OTHER,
    GATEWAY_NO_DATA_OR_TIMEOUT,
    NOT_TESTED
}

/**
 * Automated 4-Phase Benchmark Suite to verify genuine ELM327 / STN hardware,
 * custom CAN headers, multi-frame ISO-TP flow control, and Module 44 (EPS J500) reachability.
 */
object AdapterCapabilityProfiler {

    suspend fun runBenchmark(transport: ElmTransport): AdapterBenchmarkReport {
        val logs = mutableListOf<String>()
        fun log(msg: String) { logs.add(msg) }

        log("=== PHASE 1: MICROCONTROLLER & FIRMWARE PROBE ===")
        val atz = transport.sendCommand("ATZ", timeoutMs = 2000L).rawText.trim()
        delay(400)
        log("ATZ -> $atz")

        val ati = transport.sendCommand("ATI", timeoutMs = 1500L).rawText.trim()
        log("ATI -> $ati")

        val atAt1 = transport.sendCommand("AT@1", timeoutMs = 1500L).rawText.trim()
        log("AT@1 -> $atAt1")

        val atAt2 = transport.sendCommand("AT@2", timeoutMs = 1500L).rawText.trim()
        log("AT@2 -> $atAt2")

        val sti = transport.sendCommand("STI", timeoutMs = 1500L).rawText.trim()
        log("STI -> $sti")

        val isStn = sti.isNotBlank() && !sti.contains("?") && !sti.contains("ERROR") && (sti.contains("STN") || sti.contains("OBDLink") || sti.contains("vLinker"))
        val isClone = atz.contains("v1.5", ignoreCase = true) || atz.contains("v2.1", ignoreCase = true) || atAt1.contains("?") || atAt2.contains("?")

        val chipVerdict = when {
            isStn -> "Authentic STN Processor (High Performance, Hardware ISO-TP)"
            !isClone && (atz.contains("v1.4") || atz.contains("v2.")) -> "Authentic PIC18F25K80 / ELM327 v1.4b+ Firmware"
            isClone -> "Budget ELM327 Clone / Emulated Firmware (v1.5/v2.1 clone board)"
            else -> "Standard ELM327 Compatible Controller"
        }

        log("=== PHASE 2: UDS COMMAND MATRIX & CAN FLOW CONTROL ===")
        transport.sendCommand("ATE0", timeoutMs = 1000L)
        transport.sendCommand("ATSP6", timeoutMs = 1000L)
        transport.sendCommand("ATCAF1", timeoutMs = 1000L)
        transport.sendCommand("ATH1", timeoutMs = 1000L)

        val shResp = transport.sendCommand("ATSH 7E0", timeoutMs = 1000L)
        log("ATSH 7E0 -> ${shResp.rawText.trim()}")

        val craResp = transport.sendCommand("ATCRA 7E8", timeoutMs = 1000L)
        log("ATCRA 7E8 -> ${craResp.rawText.trim()}")

        val fcShResp = transport.sendCommand("ATFC SH 7E0", timeoutMs = 1000L)
        val fcSdResp = transport.sendCommand("ATFC SD 30 00 00", timeoutMs = 1000L)
        val fcSmResp = transport.sendCommand("ATFC SM 1", timeoutMs = 1000L)
        log("ATFC suite -> SH: ${fcShResp.rawText.trim()}, SD: ${fcSdResp.rawText.trim()}, SM: ${fcSmResp.rawText.trim()}")

        val customHeadersOk = (shResp.rawText.contains("OK") || shResp.status == ResponseStatus.OK) &&
                (craResp.rawText.contains("OK") || craResp.status == ResponseStatus.OK)
        val flowControlOk = !fcShResp.rawText.contains("?") && !fcSdResp.rawText.contains("?")

        log("=== PHASE 3: MULTI-FRAME ISO-TP TEST ON ECM (VIN READ 22 F1 90) ===")
        val vinResp = transport.sendCommand("22 F1 90", timeoutMs = 3000L)
        log("22 F1 90 (ECM UDS VIN) -> [${vinResp.status}] ${vinResp.lines.joinToString(" | ")}")

        var vinExtracted: String? = null
        var multiFrameOk = false

        if (vinResp.status == ResponseStatus.OK && vinResp.lines.isNotEmpty()) {
            val messages = try { IsoTpParser.reassembleLines(vinResp.lines) } catch (_: Exception) { emptyList() }
            val posMsg = messages.firstOrNull { it.reconstructedBytes.size >= 4 && it.reconstructedBytes[0] == 0x62 }
            if (posMsg != null) {
                multiFrameOk = true
                val rawBytes = posMsg.reconstructedBytes.drop(3) // Drop 0x62, 0xF1, 0x90
                vinExtracted = rawBytes.map { it.toInt().toChar() }.joinToString("").filter { it.isLetterOrDigit() }
                log("Successfully reassembled multi-frame UDS VIN: $vinExtracted")
            }
        }

        // Fallback to Mode 09 PID 02 check if 22 F1 90 is not supported
        if (!multiFrameOk) {
            val m09Resp = transport.sendCommand("09 02", timeoutMs = 3000L)
            log("09 02 (Mode 09 VIN) -> [${m09Resp.status}] ${m09Resp.lines.joinToString(" | ")}")
            if (m09Resp.status == ResponseStatus.OK && m09Resp.lines.size >= 2) {
                multiFrameOk = true
                log("Multi-frame ISO-TP verified via Mode 09 PID 02")
            }
        }

        log("=== PHASE 4: MODULE 44 (STEERING ASSIST J500) GATEWAY PROBE ===")
        // Test primary physical CAN ID pair: 714 (Req) / 77E (Resp)
        transport.sendCommand("ATSH 714", timeoutMs = 1000L)
        transport.sendCommand("ATCRA 77E", timeoutMs = 1000L)

        val epsResp = transport.sendCommand("22 02 00", timeoutMs = 2500L)
        val epsRaw = epsResp.rawText.ifBlank { epsResp.lines.joinToString(" | ") }
        log("22 02 00 (Module 44 @ 714/77E) -> [${epsResp.status}] $epsRaw")

        val m44Status = when {
            epsRaw.contains("62 02 00") || epsRaw.contains("620200") -> Module44ProbeStatus.RESPONDED_POSITIVE
            epsRaw.contains("7F 22 31") || epsRaw.contains("7F2231") -> Module44ProbeStatus.NRC_OUT_OF_RANGE_31
            epsRaw.contains("7F 22 33") || epsRaw.contains("7F2233") -> Module44ProbeStatus.NRC_SECURITY_DENIED_33
            epsRaw.contains("7F 22 7E") || epsRaw.contains("7F227E") -> Module44ProbeStatus.NRC_SESSION_REQUIRED_7E
            epsRaw.contains("7F 22") || epsRaw.contains("7F22") -> Module44ProbeStatus.NRC_OTHER
            epsResp.status == ResponseStatus.TIMEOUT || epsResp.status == ResponseStatus.NO_DATA || epsRaw.contains("NO DATA") -> Module44ProbeStatus.GATEWAY_NO_DATA_OR_TIMEOUT
            else -> Module44ProbeStatus.GATEWAY_NO_DATA_OR_TIMEOUT
        }

        // Restore default functional header for standard telemetry
        transport.sendCommand("ATSH 7DF", timeoutMs = 1000L)
        transport.sendCommand("ATCRA", timeoutMs = 1000L)

        return AdapterBenchmarkReport(
            chipIdentifier = ati.ifBlank { atz },
            isStnChip = isStn,
            isCloneOrCounterfeit = isClone,
            chipVerdict = chipVerdict,
            customHeadersSupported = customHeadersOk,
            flowControlSupported = flowControlOk,
            multiFrameIsoTpVerified = multiFrameOk,
            vinMultiFrameReassembled = vinExtracted,
            module44Status = m44Status,
            module44RawResponse = epsRaw,
            testLogs = logs
        )
    }
}
