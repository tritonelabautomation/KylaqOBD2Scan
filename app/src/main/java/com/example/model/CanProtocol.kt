package com.example.model

enum class CanProtocol(val protocolNumber: String, val displayName: String, val atCommand: String) {
    AUTO("0", "Auto Detect", "ATSP0"),
    ISO_15765_11B_500K("6", "ISO 15765-4 (11-bit / 500k)", "ATSP6"),
    ISO_15765_29B_500K("7", "ISO 15765-4 (29-bit / 500k)", "ATSP7"),
    ISO_15765_11B_250K("8", "ISO 15765-4 (11-bit / 250k)", "ATSP8"),
    ISO_15765_29B_250K("9", "ISO 15765-4 (29-bit / 250k)", "ATSP9")
}

enum class ProtocolHealth {
    UNKNOWN,
    TESTING,
    WORKING,
    PARTIAL,
    NO_RESPONSE,
    ADAPTER_ERROR
}

/**
 * How many valid ISO-TP messages constitute proof that the bus is talking, no matter what a
 * connect-time probe concluded. Owner 2026-09-19: the dashboard showed NO_RESPONSE, grey CAN/ECU
 * dots and "No ECU response received" WHILE 39,808 frames were flowing - a probe can miss an ECU
 * that is still waking up, but live frames cannot lie.
 */
const val FRAME_EVIDENCE_THRESHOLD = 25

/**
 * Evidence over verdict: upgrades a stale NO_RESPONSE/UNKNOWN once [FRAME_EVIDENCE_THRESHOLD]
 * valid frames have arrived. PARTIAL, WORKING and ADAPTER_ERROR are left alone - a partial
 * capability bitmap and an adapter fault are claims the frame count cannot settle.
 */
fun healthAfterFrameEvidence(current: ProtocolHealth, validFrames: Long): ProtocolHealth =
    if (validFrames >= FRAME_EVIDENCE_THRESHOLD &&
        (current == ProtocolHealth.NO_RESPONSE || current == ProtocolHealth.UNKNOWN)
    ) {
        ProtocolHealth.WORKING
    } else {
        current
    }

enum class PidTestStatus {
    ECU_RESPONSE,
    NO_DATA,
    TIMEOUT,
    CAN_ERROR,
    ADAPTER_ERROR,
    MALFORMED
}

data class PidTestResult(
    val txCommand: String,
    val rxResponse: String,
    val status: PidTestStatus,
    val latencyMs: Long
)

data class ProtocolVerificationResult(
    val protocol: CanProtocol,
    val successCount: Int,
    val timeoutCount: Int,
    val unsupportedCount: Int,
    val invalidCount: Int,
    val canErrorCount: Int,
    val totalRequests: Int,
    val avgResponseTimeMs: Long,
    val minResponseTimeMs: Long,
    val maxResponseTimeMs: Long,
    val health: ProtocolHealth,
    val pidResults: List<PidTestResult>,
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val buildNumber: Int = 0,
    val commitHash: String = ""
)

enum class ProtocolEvidenceStatus {
    NOT_TESTED,
    TESTED,
    NO_RESPONSE,
    INVALID_RESPONSE,
    VERIFIED
}

data class ProtocolAttemptResult(
    val protocol: CanProtocol,
    val status: ProtocolEvidenceStatus,
    val rawResponse: String,
    val latencyMs: Long,
    val isVerified: Boolean
)
