package com.example.ai

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SECURITY WARNING: This provider calls the Gemini REST API directly using an API key
 * embedded in BuildConfig (i.e. compiled into the APK as a string literal).
 *
 * Any client-side API key is extractable by decompiling the APK. This is acceptable
 * only for:
 *   - Research/development builds
 *   - Low-traffic personal use
 *
 * For production deployment, you MUST:
 *   1. Move the Gemini call to a backend proxy server that holds the key
 *   2. Or use restricted API keys with Android app signing-key + package name restriction
 *      (https://cloud.google.com/docs/authentication/api-keys#api_key_restrictions)
 *   3. Or use the Firebase AI Logic SDK with App Check for token-based auth
 *
 * The current key is only read from BuildConfig and never written to disk or logs.
 */
class FirebaseAiDoctorProvider : AiDoctorProvider {
    override val providerName: String = "Gemini API (REST)"
    override val modelName: String = "gemini-1.5-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun analyze(request: AiDoctorRequest): AiDoctorResponse = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext AiDoctorResponse("AI provider is not configured. A valid GEMINI_API_KEY is required in your Secrets.", false)
        }

        try {
            val liveDataStr = if (request.context.liveData.isEmpty()) "No Live Data Available" else {
                request.context.liveData.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            }

            val systemInstruction = """
                You are a professional automotive diagnostic assistant.
                
                VEHICLE CONTEXT:
                Vehicle: ${request.context.vehicleName}
                VIN: ${request.context.vin}
                
                OBD CONNECTION STATE:
                Status: ${request.context.connectionStatus}
                Adapter: ${request.context.adapterName}
                Protocol: ${request.context.protocol}
                Verification State: ${request.context.verificationState}
                ECU Responses: ${request.context.ecuResponses}
                CAN Errors: ${request.context.canErrors}
                Timeouts: ${request.context.timeouts}
                
                APP VERSION:
                Version: ${request.context.appVersion} (${request.context.buildNumber})
                Commit: ${request.context.gitCommit}
                
                Provide a clear, technical, and safe analysis based ONLY on the provided evidence.
                Distinguish between confirmed facts (e.g., ECU measurements) and your inferences.
                If asked for a diagnosis, include sections:
                ## Diagnosis
                ## Evidence
                ## Likely Causes
                ## Recommended Checks
                ## Confidence
                
                Do not invent values. If a value is missing, explicitly state it is unavailable.
            """.trimIndent()

            val contentsArray = JSONArray()
            
            // Add history
            for (msg in request.chatHistory) {
                contentsArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", msg.text) })
                    })
                })
            }
            
            // Construct the latest user prompt with the injected live data and DTCs (acting as current snapshot)
            val latestPromptStr = """
                CURRENT DIAGNOSTIC SNAPSHOT:
                Active DTCs: ${request.context.dtcs}
                Live OBD Values:
                $liveDataStr
                
                USER QUERY:
                ${request.latestQuery}
            """.trimIndent()

            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", latestPromptStr) })
                })
            })

            val jsonBody = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", contentsArray)
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${apiKey}"
            
            val httpRequest = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(httpRequest).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val firstPart = parts?.optJSONObject(0)
                val text = firstPart?.optString("text") ?: "No response from AI model."
                
                AiDoctorResponse(
                    responseText = text,
                    isEcuFact = false
                )
            } else {
                val errorBody = response.body?.string() ?: ""
                AiDoctorResponse(
                    responseText = "API Error: ${response.code} ${response.message}\n$errorBody",
                    isEcuFact = false
                )
            }
        } catch (e: Exception) {
            AiDoctorResponse(
                responseText = "AI Provider Error: ${e.localizedMessage}",
                isEcuFact = false
            )
        }
    }
}
