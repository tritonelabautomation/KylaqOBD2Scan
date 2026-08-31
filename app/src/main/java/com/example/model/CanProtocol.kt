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
    NO_RESPONSE
}

data class ProtocolVerificationResult(
    val protocol: CanProtocol,
    val successCount: Int,
    val timeoutCount: Int,
    val unsupportedCount: Int,
    val invalidCount: Int,
    val totalRequests: Int,
    val avgResponseTimeMs: Long,
    val minResponseTimeMs: Long,
    val maxResponseTimeMs: Long,
    val health: ProtocolHealth
)
