package com.example.model

enum class ProfileType {
    STANDARD_OBD,
    VAG_EXPERIMENTAL,
    UDS_EXPERIMENTAL,
    DISCOVERY
}

enum class ProfileTestStatus {
    PENDING,
    CONFIRMED,
    ECU_SUPPORTED,
    RESPONSE_RECEIVED,
    PARSED,
    RAW_RESPONSE_ONLY,
    EXPERIMENTAL,
    UNSUPPORTED,
    TIMEOUT,
    INVALID_RESPONSE,
    ERROR,
    UNKNOWN
}

data class DiagnosticRequest(
    val id: String,
    val name: String,
    val service: String,
    val identifier: String, // PID or DID
    val description: String,
    val decoderType: DecoderType = DecoderType.RESEARCH_RAW,
    val isUds: Boolean = false
) {
    val requestCommand: String
        get() = "$service$identifier"
}

data class DiagnosticProfile(
    val id: String,
    val name: String,
    val type: ProfileType,
    val description: String,
    val requests: List<DiagnosticRequest>
)

data class DiagnosticResult(
    val request: DiagnosticRequest,
    val rawTx: String,
    val rawRx: String,
    val parsedValue: String,
    val status: ProfileTestStatus,
    val responseTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)
