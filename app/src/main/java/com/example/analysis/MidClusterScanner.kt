package com.example.analysis

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.ai.GeminiTextClient
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Image OCR & Pattern Recognition Scanner for Škoda Virtual Cockpit / MID Cluster screens.
 *
 * Extracts:
 *  - Duration (e.g. "1:27 h" -> 87 minutes)
 *  - Distance (e.g. "31 km" -> 31.0 km)
 *  - Average Fuel Economy (e.g. "Avg. 9.8 km/l" -> 9.8 km/L)
 *  - Average Speed (e.g. "Avg. 21 km/h" -> 21 km/h)
 *  - Total Odometer (e.g. "4052 km")
 *  - Range to Empty (e.g. "210 km")
 *  - Ambient Temperature (e.g. "31.0 °C")
 *  - Mode (e.g. "Since start")
 */
object MidClusterScanner {

    /**
     * Scans an image URI using on-device ML Kit OCR, with Gemini Vision AI fallback.
     */
    suspend fun scanClusterImage(
        context: Context,
        imageUri: Uri,
        tripId: String = ""
    ): MidClusterData? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        scanFromBytes(bytes, tripId, imageUri.toString())
    }

    /**
     * Scans image bytes directly.
     */
    suspend fun scanFromBytes(
        bytes: ByteArray,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? = withContext(Dispatchers.IO) {
        // Path 1: On-device ML Kit Text Recognition (fast, offline, privacy-first)
        val onDeviceText = scanOnDeviceMlKit(bytes)
        if (!onDeviceText.isNullOrBlank()) {
            val parsed = parseFromText(onDeviceText, tripId, photoUri)
            if (parsed != null && parsed.avgFuelEconomyKmL > 0.0) {
                return@withContext parsed
            }
        }

        // Path 2: Gemini AI Vision (when configured and online)
        if (GeminiTextClient.isConfigured()) {
            val aiResult = scanWithGeminiVision(bytes, tripId, photoUri)
            if (aiResult != null && aiResult.avgFuelEconomyKmL > 0.0) {
                return@withContext aiResult
            }
        }

        // If partial data was detected on-device (e.g. distance without economy), still return it
        if (!onDeviceText.isNullOrBlank()) {
            val partial = parseFromText(onDeviceText, tripId, photoUri)
            if (partial != null) return@withContext partial
        }

        null
    }

    /**
     * On-device ML Kit OCR execution on raw image bytes.
     */
    suspend fun scanOnDeviceMlKit(imageBytes: ByteArray): String? = suspendCancellableCoroutine { continuation ->
        runCatching {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap == null) {
                continuation.resume(null)
                return@runCatching
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }.onFailure {
            continuation.resume(null)
        }
    }

    /**
     * Vision-based OCR via Gemini API.
     */
    suspend fun scanWithGeminiVision(
        imageBytes: ByteArray,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = """
                You are analyzing a photo of a Škoda Kylaq / VAG Virtual Cockpit instrument cluster (MID screen).
                Extract the trip statistics visible on the screen.
                Reply with ONLY a JSON object in this exact schema:
                {
                  "duration_text": "1:27 h",
                  "duration_minutes": 87,
                  "distance_km": 31.0,
                  "fuel_economy_km_l": 9.8,
                  "avg_speed_km_h": 21.0,
                  "odometer_km": 4052.0,
                  "range_km": 210.0,
                  "ambient_temp_c": 31.0,
                  "mode": "Since start",
                  "time_of_day": "17:28"
                }
            """.trimIndent()

            val rawJson = GeminiTextClient.scanCustomJson(imageBytes, prompt) ?: return@withContext null
            parseClusterJson(rawJson, tripId, photoUri)
        }.getOrNull()
    }

    /**
     * Parses clean JSON output from Vision model into [MidClusterData].
     */
    fun parseClusterJson(
        jsonString: String,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? {
        return try {
            val clean = jsonString.substringAfter('{').substringBeforeLast('}')
            val json = JSONObject("{$clean}")

            val dist = json.optDouble("distance_km", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: 0.0
            val economy = json.optDouble("fuel_economy_km_l", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: 0.0
            val speed = json.optDouble("avg_speed_km_h", Double.NaN).takeIf { !it.isNaN() && it >= 0 } ?: 0.0
            val durText = json.optString("duration_text", "").takeIf { it.isNotBlank() } ?: "0:00 h"
            var durMin = json.optInt("duration_minutes", 0)
            if (durMin <= 0) {
                durMin = parseDurationTextToMinutes(durText)
            }

            MidClusterData(
                tripId = tripId,
                durationMinutes = durMin,
                durationText = durText,
                distanceKm = dist,
                avgFuelEconomyKmL = economy,
                avgSpeedKmh = speed,
                totalOdometerKm = json.optDouble("odometer_km", Double.NaN).takeIf { !it.isNaN() },
                rangeKm = json.optDouble("range_km", Double.NaN).takeIf { !it.isNaN() },
                ambientTempC = json.optDouble("ambient_temp_c", Double.NaN).takeIf { !it.isNaN() },
                mode = json.optString("mode", "Since start"),
                timeOfDay = json.optString("time_of_day", null)?.takeIf { it.isNotBlank() && it != "null" },
                photoUri = photoUri
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses text string into [MidClusterData] using regular expressions.
     */
    fun parseFromText(
        text: String,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? {
        val normalized = text.replace(",", ".")

        // Economy: e.g. "Avg. 9.8 km/l" or "9.8 km/l" or "Avg 9.8 kmpl" or "Avg. 9.8 km/L"
        val econMatch = Pattern.compile(
            "(?:Avg\\.?|Average)?\\s*[:\\s]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:km\\s*\\/\\s*l|kmpl|km\\s*\\/\\s*L|l\\s*\\/\\s*100\\s*km)",
            Pattern.CASE_INSENSITIVE
        ).matcher(normalized)
        val economy = if (econMatch.find()) econMatch.group(1)?.toDoubleOrNull() ?: 0.0 else 0.0

        // Avg Speed: e.g. "Avg. 21 km/h" or "21 km/h" or "Avg 21 km/h"
        val speedMatch = Pattern.compile(
            "(?:Avg\\.?|Average)?\\s*[:\\s]?\\s*(\\d+(?:\\.\\d+)?)\\s*km\\s*\\/\\s*h",
            Pattern.CASE_INSENSITIVE
        ).matcher(normalized)
        val avgSpeed = if (speedMatch.find()) speedMatch.group(1)?.toDoubleOrNull() ?: 0.0 else 0.0

        // Duration: prioritize explicit "1:27 h" or "1:27h" or "45 min"
        val explicitDurMatch = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*h", Pattern.CASE_INSENSITIVE).matcher(normalized)
        var durText = "0:00 h"
        var durMin = 0

        if (explicitDurMatch.find()) {
            val hours = explicitDurMatch.group(1)?.toIntOrNull() ?: 0
            val mins = explicitDurMatch.group(2)?.toIntOrNull() ?: 0
            durMin = hours * 60 + mins
            durText = "$hours:${String.format(java.util.Locale.US, "%02d", mins)} h"
        } else {
            val minMatch = Pattern.compile("(\\d+)\\s*(?:min|mins)", Pattern.CASE_INSENSITIVE).matcher(normalized)
            if (minMatch.find()) {
                val mins = minMatch.group(1)?.toIntOrNull() ?: 0
                durMin = mins
                durText = "${mins / 60}:${String.format(java.util.Locale.US, "%02d", mins % 60)} h"
            } else {
                val generalTimeMatch = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(normalized)
                if (generalTimeMatch.find()) {
                    val hours = generalTimeMatch.group(1)?.toIntOrNull() ?: 0
                    val mins = generalTimeMatch.group(2)?.toIntOrNull() ?: 0
                    durMin = hours * 60 + mins
                    durText = "$hours:${String.format(java.util.Locale.US, "%02d", mins)} h"
                }
            }
        }

        // Clock Time of day if present (e.g. "17:28")
        var clockTime: String? = null
        val clockMatch = Pattern.compile("(?<!:)(\\b\\d{1,2}:\\d{2}\\b)(?!\\s*h)", Pattern.CASE_INSENSITIVE).matcher(normalized)
        if (clockMatch.find()) {
            clockTime = clockMatch.group(1)
        }

        // Distance and Odometer and Range:
        // Match numbers followed by km (not km/h or km/l)
        val kmMatches = mutableListOf<Double>()
        val kmMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km(?!\\s*\\/)", Pattern.CASE_INSENSITIVE).matcher(normalized)
        while (kmMatcher.find()) {
            kmMatcher.group(1)?.toDoubleOrNull()?.let { kmMatches.add(it) }
        }

        // Trip distance is typically the first number < 1000 km (or within central cluster)
        val distance = kmMatches.firstOrNull { it in 0.1..999.0 } ?: 0.0
        val odo = kmMatches.firstOrNull { it >= 1000.0 }
        val remainingKms = kmMatches.filter { it in 0.1..999.0 && it != distance }
        val range = remainingKms.lastOrNull()

        // Ambient temperature: e.g. "31.0°c", "31.0°C", "31.0 C"
        val tempMatch = Pattern.compile("(\\d{1,2}(?:\\.\\d+)?)\\s*°?\\s*[cC]\\b", Pattern.CASE_INSENSITIVE).matcher(normalized)
        val temp = if (tempMatch.find()) tempMatch.group(1)?.toDoubleOrNull() else null

        // Mode detection
        val mode = when {
            normalized.contains("refuel", ignoreCase = true) -> "Since refuel"
            normalized.contains("long", ignoreCase = true) -> "Long-term"
            else -> "Since start"
        }

        if (distance <= 0.0 && economy <= 0.0 && durMin <= 0) return null

        return MidClusterData(
            tripId = tripId,
            durationMinutes = durMin,
            durationText = durText,
            distanceKm = distance,
            avgFuelEconomyKmL = economy,
            avgSpeedKmh = avgSpeed,
            totalOdometerKm = odo,
            rangeKm = range,
            ambientTempC = temp,
            mode = mode,
            timeOfDay = clockTime,
            photoUri = photoUri
        )
    }

    /**
     * Converts "1:27 h" or "87 min" to integer minutes.
     */
    fun parseDurationTextToMinutes(durationText: String): Int {
        val clean = durationText.trim()
        val hm = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(clean)
        if (hm.find()) {
            val h = hm.group(1)?.toIntOrNull() ?: 0
            val m = hm.group(2)?.toIntOrNull() ?: 0
            return h * 60 + m
        }
        val justMin = Pattern.compile("(\\d+)\\s*(?:min|m)").matcher(clean)
        if (justMin.find()) {
            return justMin.group(1)?.toIntOrNull() ?: 0
        }
        return 0
    }
}
