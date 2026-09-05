package com.example.ai

/**
 * Fallback AI provider used when [FirebaseAiDoctorProvider] cannot be initialized
 * (e.g. Firebase is not configured, BuildConfig is missing, or the network/SDK is
 * unavailable on the device).
 *
 * Returns a deterministic, user-visible message that explains the AI Doctor is
 * unavailable due to configuration rather than silently failing. This prevents a
 * ViewModel constructor crash from taking down the entire app when Firebase is
 * misconfigured.
 */
class StubAiDoctorProvider : AiDoctorProvider {
    override val providerName: String = "Stub (AI Doctor disabled)"
    override val modelName: String = "n/a"

    override suspend fun analyze(request: AiDoctorRequest): AiDoctorResponse = AiDoctorResponse(
        responseText = "AI Doctor is currently unavailable. The configured AI provider failed to initialize. " +
            "Check that Firebase / Gemini API credentials are present in your build configuration.",
        isEcuFact = false
    )
}
