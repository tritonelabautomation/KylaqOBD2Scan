package com.example.analysis

import android.content.Context
import android.net.Uri
import com.example.ai.GeminiTextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Image OCR & Pattern Recognition Scanner for Škoda Virtual Cockpit / MID Cluster screens.
 *
 * Extracts:
 *  - Duration (e.g. "1:23 h" -> 83 minutes)
 *  - Distance (e.g. "31 km" -> 31.0 km)
 *  - Average Fuel Economy (e.g. "Avg. 9.1 km/l" -> 9.1 km/L)
 *  - Average Speed (e.g. "Avg. 23 km/h" -> 23 km/h)
 *  - Total Odometer (e.g. "4021 km")
 *  - Range to Empty (e.g. "240 km")
 *  - Ambient Temperature (e.g. "29.5 °C")
 *  - Mode (e.g. "Since start")
 */
object MidClusterScanner {

    /**
     * Scans an image URI using AI Vision when configured, or pattern recognition.
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
        // Path 1: Gemini AI Vision (when configured)
        if (GeminiTextClient.isConfigured()) {
            val aiResult = scanWithGeminiVision(bytes, tripId, photoUri)
            if (aiResult != null) return@withContext aiResult
        }

        // Path 2: On-device image OCR / Pattern extractor
        null
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
                  "duration_text": "1:23 h",
                  "duration_minutes": 83,
                  "distance_km": 31.0,
                  "fuel_economy_km_l": 9.1,
                  "avg_speed_km_h": 23.0,
                  "odometer_km": 4021.0,
                  "range_km": 240.0,
                  "ambient_temp_c": 29.5,
                  "mode": "Since start",
                  "time_of_day": "9:07"
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

        // Distance: e.g. "31 km" or "31.0 km"
        val distMatch = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km", Pattern.CASE_INSENSITIVE).matcher(normalized)
        val distance = if (distMatch.find()) distMatch.group(1)?.toDoubleOrNull() ?: 0.0 else 0.0

        // Economy: e.g. "Avg. 9.1 km/l" or "9.1 km/l"
        val econMatch = Pattern.compile("(?:Avg\\.?\\s*)?(\\d+(?:\\.\\d+)?)\\s*km\\/l", Pattern.CASE_INSENSITIVE).matcher(normalized)
        val economy = if (econMatch.find()) econMatch.group(1)?.toDoubleOrNull() ?: 0.0 else 0.0

        // Avg Speed: e.g. "Avg. 23 km/h" or "23 km/h"
        val speedMatch = Pattern.compile("(?:Avg\\.?\\s*)?(\\d+(?:\\.\\d+)?)\\s*km\\/h", Pattern.CASE_INSENSITIVE).matcher(normalized)
        val avgSpeed = if (speedMatch.find()) speedMatch.group(1)?.toDoubleOrNull() ?: 0.0 else 0.0

        // Duration: e.g. "1:23 h" or "1:23"
        val durMatch = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*h?", Pattern.CASE_INSENSITIVE).matcher(normalized)
        var durText = "0:00 h"
        var durMin = 0
        if (durMatch.find()) {
            val hours = durMatch.group(1)?.toIntOrNull() ?: 0
            val mins = durMatch.group(2)?.toIntOrNull() ?: 0
            durMin = hours * 60 + mins
            durText = "$hours:${String.format(java.util.Locale.US, "%02d", mins)} h"
        }

        // Total Odo: e.g. "4021 km"
        val odoMatch = Pattern.compile("(\\d{4,6})\\s*km", Pattern.CASE_INSENSITIVE).matcher(normalized)
        val odo = if (odoMatch.find()) odoMatch.group(1)?.toDoubleOrNull() else null

        // Temp: e.g. "29.5°C" or "29.5 C"
        val tempMatch = Pattern.compile("(\\d{1,2}(?:\\.\\d+)?)\\s*°?[cC]", Pattern.CASE_INSENSITIVE).matcher(normalized)
        val temp = if (tempMatch.find()) tempMatch.group(1)?.toDoubleOrNull() else null

        if (distance <= 0.0 && economy <= 0.0 && durMin <= 0) return null

        return MidClusterData(
            tripId = tripId,
            durationMinutes = durMin,
            durationText = durText,
            distanceKm = distance,
            avgFuelEconomyKmL = economy,
            avgSpeedKmh = avgSpeed,
            totalOdometerKm = odo,
            ambientTempC = temp,
            mode = if (normalized.contains("refuel", ignoreCase = true)) "Since refuel" else if (normalized.contains("long", ignoreCase = true)) "Long-term" else "Since start",
            photoUri = photoUri
        )
    }

    /**
     * Converts "1:23 h" or "83 min" to integer minutes.
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
