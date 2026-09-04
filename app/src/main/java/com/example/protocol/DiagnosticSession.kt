package com.example.protocol

import android.os.SystemClock
import com.example.bluetooth.ElmTransport
import com.example.model.CanProtocol
import com.example.model.KylaqProtocolProfile
import com.example.model.ProtocolAttemptResult
import com.example.model.ProtocolEvidenceStatus
import com.example.model.ResponseStatus
import kotlinx.coroutines.delay

data class SessionInitResult(
    val isSuccess: Boolean,
    val activeProtocol: CanProtocol,
    val log: List<String>,
    val adapterInfo: String? = null
)

data class ProtocolFallbackReport(
    val verifiedProtocol: CanProtocol?,
    val attempts: List<ProtocolAttemptResult>,
    val isVerified: Boolean
)

/**
 * Authoritative diagnostic session manager for Škoda Kylaq (ISO 15765-4 CAN).
 *
 * Responsibilities:
 * 1. Safe, deterministic ELM327 initialization (ATZ, ATE0, ATL0, ATS0, ATH1, ATSP6).
 * 2. Evidence-based protocol verification using harmless Mode 01 PID 00.
 * 3. Structured, evidence-based protocol fallback. Never claims verification without positive response.
 */
object DiagnosticSession {

    /**
     * Initializes the OBD adapter with the default Škoda Kylaq profile or specified protocol.
     */
    suspend fun initialize(
        transport: ElmTransport,
        protocol: CanProtocol = KylaqProtocolProfile.DEFAULT_CAN_PROTOCOL
    ): SessionInitResult {
        val logs = mutableListOf<String>()
        var adapterInfo: String? = null

        fun log(msg: String) {
            logs.add(msg)
        }

        log("Initializing ELM327 adapter for ${KylaqProtocolProfile.VEHICLE_NAME} (${protocol.displayName})")

        // 1. Reset
        val resetResp = transport.sendCommand("ATZ", timeoutMs = 2000L)
        log("ATZ -> ${resetResp.rawText.trim()}")
        adapterInfo = resetResp.rawText.trim().lines().firstOrNull { it.isNotBlank() }
        delay(600) // Allow adapter reboot settle

        // 2. Base Configuration
        val configCmds = listOf(
            "ATE0" to "Echo off",
            "ATL0" to "Linefeeds off",
            "ATS0" to "Spaces off",
            "ATH1" to "CAN Headers on (ATH1 mandatory for ECU address discrimination)"
        )

        for ((cmd, desc) in configCmds) {
            val resp = transport.sendCommand(cmd, timeoutMs = 1200L)
            log("$cmd ($desc) -> ${resp.rawText.trim()}")
            if (resp.status != ResponseStatus.OK && !resp.rawText.contains("OK")) {
                log("Warning: Command $cmd did not return expected OK")
            }
        }

        // 3. Set CAN protocol
        val protoCmd = protocol.atCommand
        val protoResp = transport.sendCommand(protoCmd, timeoutMs = 1500L)
        log("$protoCmd (Set ${protocol.displayName}) -> ${protoResp.rawText.trim()}")

        val isSuccess = protoResp.status == ResponseStatus.OK || protoResp.rawText.contains("OK")
        return SessionInitResult(
            isSuccess = isSuccess,
            activeProtocol = protocol,
            log = logs,
            adapterInfo = adapterInfo
        )
    }

    /**
     * Verifies protocol communication using standard harmless 0100 query.
     * Requires positive diagnostic evidence (positive response e.g. 41 00 from 7E8).
     */
    suspend fun verifyProtocol(transport: ElmTransport, protocol: CanProtocol): ProtocolAttemptResult {
        val cmd = KylaqProtocolProfile.PROTOCOL_VERIFICATION_COMMAND
        val startMs = SystemClock.elapsedRealtime()

        val resp = transport.sendCommand(cmd, timeoutMs = 3000L)
        val latency = SystemClock.elapsedRealtime() - startMs
        val raw = resp.lines.joinToString(" | ").ifEmpty { resp.rawText.trim() }

        val hasPositiveCanResponse = resp.status == ResponseStatus.OK &&
                resp.lines.any { line ->
                    (line.contains("41 00") || line.contains("4100")) &&
                            !line.contains("7F") &&
                            !line.contains("SEARCHING")
                }

        val status = when {
            hasPositiveCanResponse -> ProtocolEvidenceStatus.VERIFIED
            resp.status == ResponseStatus.TIMEOUT -> ProtocolEvidenceStatus.NO_RESPONSE
            resp.status == ResponseStatus.NO_DATA -> ProtocolEvidenceStatus.NO_RESPONSE
            resp.rawText.contains("UNABLE TO CONNECT") -> ProtocolEvidenceStatus.NO_RESPONSE
            resp.status == ResponseStatus.CAN_ERROR || resp.status == ResponseStatus.BUS_INIT_ERROR -> ProtocolEvidenceStatus.INVALID_RESPONSE
            else -> ProtocolEvidenceStatus.TESTED
        }

        return ProtocolAttemptResult(
            protocol = protocol,
            status = status,
            rawResponse = raw,
            latencyMs = latency,
            isVerified = status == ProtocolEvidenceStatus.VERIFIED
        )
    }

    /**
     * Attempts protocol fallback when the default ATSP6 fails to verify.
     * Each attempted protocol produces an explicit evidence record.
     */
    suspend fun attemptProtocolFallback(transport: ElmTransport): ProtocolFallbackReport {
        val attempts = mutableListOf<ProtocolAttemptResult>()

        // 1. Verify default protocol first
        val defaultAttempt = verifyProtocol(transport, KylaqProtocolProfile.DEFAULT_CAN_PROTOCOL)
        attempts.add(defaultAttempt)

        if (defaultAttempt.isVerified) {
            return ProtocolFallbackReport(
                verifiedProtocol = KylaqProtocolProfile.DEFAULT_CAN_PROTOCOL,
                attempts = attempts,
                isVerified = true
            )
        }

        // 2. Iterate fallbacks
        for (fallbackProto in KylaqProtocolProfile.FALLBACK_PROTOCOLS) {
            // Apply protocol command
            transport.sendCommand(fallbackProto.atCommand, timeoutMs = 1500L)
            delay(150)

            val attempt = verifyProtocol(transport, fallbackProto)
            attempts.add(attempt)

            if (attempt.isVerified) {
                return ProtocolFallbackReport(
                    verifiedProtocol = fallbackProto,
                    attempts = attempts,
                    isVerified = true
                )
            }
            delay(150)
        }

        return ProtocolFallbackReport(
            verifiedProtocol = null,
            attempts = attempts,
            isVerified = false
        )
    }
}
