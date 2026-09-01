package com.example.ai

data class AiMessage(
    val role: String, // "user" or "model"
    val text: String
)

data class VehicleDiagnosticContext(
    val vehicleName: String,
    val vin: String,
    val connectionStatus: String,
    val adapterName: String,
    val protocol: String,
    val verificationState: String,
    val ecuResponses: Long,
    val canErrors: Long,
    val timeouts: Long,
    val liveData: Map<String, String>,
    val dtcs: String,
    val appVersion: String,
    val buildNumber: Int,
    val gitCommit: String
)

data class AiDoctorRequest(
    val context: VehicleDiagnosticContext,
    val chatHistory: List<AiMessage>,
    val latestQuery: String
)

data class AiDoctorResponse(
    val responseText: String,
    val isEcuFact: Boolean = false
)

interface AiDoctorProvider {
    val providerName: String
    val modelName: String
    suspend fun analyze(request: AiDoctorRequest): AiDoctorResponse
}
