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
 * Minimal Gemini REST client for the two personal-use AI features VehIQ paywalls:
 * "ask your car" coach chat (text) and receipt scanning (vision). Same key handling as
 * [FirebaseAiDoctorProvider]: when GEMINI_API_KEY is the placeholder the callers fall back to
 * on-device rule-based answers, so the app never hard-fails without a key.
 */
object GeminiTextClient {

    data class ReceiptScan(
        val liters: Double?,
        val pricePerL: Double?,
        val total: Double?,
        val vendor: String?,
        val category: String?
    )

    private const val MODEL = "gemini-1.5-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    /** Pure JSON parsing of a receipt-scan reply (unit tested). */
    fun parseReceiptJson(text: String): ReceiptScan? {
        return try {
            val clean = text.substringAfter('{').substringBeforeLast('}')
            val json = JSONObject("{$clean}")
            ReceiptScan(
                liters = json.optDoubleOrNull("liters"),
                pricePerL = json.optDoubleOrNull("price_per_litre"),
                total = json.optDoubleOrNull("total"),
                vendor = json.optString("vendor", null)?.takeIf { it.isNotBlank() && it != "null" },
                category = json.optString("category", null)?.takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key) || !has(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN() || v <= 0) null else v
    }

    suspend fun generate(system: String, user: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        runCatching {
            val body = JSONObject().apply {
                put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", user)))))
            }
            post(body)
        }.getOrNull()
    }

    suspend fun scanReceipt(imageBytes: ByteArray, mime: String = "image/jpeg"): ReceiptScan? =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext null
            runCatching {
                val instruction =
                    "Extract fuel/service receipt fields. Reply with ONLY JSON: " +
                        "{\"liters\":number|null,\"price_per_litre\":number|null,\"total\":number|null," +
                        "\"vendor\":string|null,\"category\":\"Fuel\"|\"Service\"|\"Expense\"|null}"
                val body = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray()
                                    .put(JSONObject().put("text", instruction))
                                    .put(
                                        JSONObject().put(
                                            "inline_data",
                                            JSONObject()
                                                .put("mime_type", mime)
                                                .put("data", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
                                        )
                                    )
                            )
                        )
                    )
                }
                post(body)?.let { parseReceiptJson(it) }
            }.getOrNull()
        }

    private fun post(body: JSONObject): String? {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val json = JSONObject(text)
            return json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
        }
    }
}
