package com.example.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import com.example.ai.GeminiTextClient
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * High-Precision Multi-Pass Image OCR & Pattern Recognition Engine for Škoda Virtual Cockpit / MID Cluster screens.
 *
 * Employs a multi-pass pipeline:
 *  1. EXIF orientation correction (prevents sideways recognition on phone photos).
 *  2. Direct on-device ML Kit OCR with structured block/line spatial analysis.
 *  3. Dynamic high-contrast & dial center-crop passes for glare/dark cluster photos.
 *  4. Cloud Vision fallback when Gemini API key is configured.
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
     * Scans an image URI using multi-pass on-device ML Kit OCR with fallback.
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
     * Scans raw image bytes through multi-pass recognition.
     */
    suspend fun scanFromBytes(
        bytes: ByteArray,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? = withContext(Dispatchers.IO) {
        val rotation = getExifRotationDegrees(bytes)
        val rawBitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

        if (rawBitmap != null) {
            val orientedBitmap = if (rotation != 0) rotateBitmap(rawBitmap, rotation) else rawBitmap

            // Pass 1: Direct oriented image
            val pass1Result = runMlKitPass(orientedBitmap, tripId, photoUri)
            if (pass1Result != null && pass1Result.avgFuelEconomyKmL > 0.0 && pass1Result.distanceKm > 0.0) {
                return@withContext pass1Result
            }

            // Pass 2: High-contrast binarization (makes LCD white digits pop against dark background)
            val contrastBitmap = enhanceContrast(orientedBitmap)
            val pass2Result = runMlKitPass(contrastBitmap, tripId, photoUri)
            if (pass2Result != null && pass2Result.avgFuelEconomyKmL > 0.0 && pass2Result.distanceKm > 0.0) {
                return@withContext pass2Result
            }

            // Pass 3: Center crop (focus on central Virtual Cockpit dial, eliminating steering wheel glare)
            val croppedBitmap = centerCrop(orientedBitmap)
            val pass3Result = runMlKitPass(croppedBitmap, tripId, photoUri)
            if (pass3Result != null && pass3Result.avgFuelEconomyKmL > 0.0) {
                // Merge with best detected odo/range from full image if available
                val mergedOdo = pass3Result.totalOdometerKm ?: pass1Result?.totalOdometerKm
                val mergedRange = pass3Result.rangeKm ?: pass1Result?.rangeKm
                val mergedTemp = pass3Result.ambientTempC ?: pass1Result?.ambientTempC
                return@withContext pass3Result.copy(
                    totalOdometerKm = mergedOdo,
                    rangeKm = mergedRange,
                    ambientTempC = mergedTemp
                )
            }

            // If we obtained a partial result with valid economy or distance, preserve it
            val bestPartial = listOfNotNull(pass1Result, pass2Result, pass3Result)
                .maxByOrNull { (if (it.avgFuelEconomyKmL > 0) 10 else 0) + (if (it.distanceKm > 0) 5 else 0) + (if (it.durationMinutes > 0) 3 else 0) }
            if (bestPartial != null && (bestPartial.avgFuelEconomyKmL > 0.0 || bestPartial.distanceKm > 0.0)) {
                return@withContext bestPartial
            }
        }

        // Pass 4: Cloud AI Vision fallback (when Gemini API is configured)
        if (GeminiTextClient.isConfigured()) {
            val aiResult = scanWithGeminiVision(bytes, tripId, photoUri)
            if (aiResult != null && aiResult.avgFuelEconomyKmL > 0.0) {
                return@withContext aiResult
            }
        }

        null
    }

    /**
     * Executes ML Kit text recognition on a bitmap and parses results.
     */
    private suspend fun runMlKitPass(
        bitmap: Bitmap,
        tripId: String,
        photoUri: String?
    ): MidClusterData? = suspendCancellableCoroutine { continuation ->
        runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val parsed = parseFromVisionText(visionText, tripId, photoUri)
                    continuation.resume(parsed)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }.onFailure {
            continuation.resume(null)
        }
    }

    /**
     * Reads EXIF rotation from image bytes to ensure upright OCR.
     */
    fun getExifRotationDegrees(bytes: ByteArray): Int {
        return runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    /**
     * Rotates bitmap by specified degrees.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Enhances contrast of the instrument cluster snapshot to separate LCD white text from dark dials.
     */
    fun enhanceContrast(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()
        val cm = ColorMatrix(floatArrayOf(
            1.6f, 0f, 0f, 0f, -30f,
            0f, 1.6f, 0f, 0f, -30f,
            0f, 0f, 1.6f, 0f, -30f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    /**
     * Crops the central 65% area of the image where Virtual Cockpit trip information resides.
     */
    fun centerCrop(src: Bitmap): Bitmap {
        val startX = (src.width * 0.175).toInt().coerceAtLeast(0)
        val startY = (src.height * 0.175).toInt().coerceAtLeast(0)
        val width = (src.width * 0.65).toInt().coerceAtMost(src.width - startX)
        val height = (src.height * 0.65).toInt().coerceAtMost(src.height - startY)
        return Bitmap.createBitmap(src, startX, startY, width, height)
    }

    /**
     * Parses ML Kit structured [Text] (blocks, lines, and elements).
     */
    fun parseFromVisionText(
        visionText: Text,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? {
        val lines = mutableListOf<String>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                lines.add(line.text.trim())
            }
        }
        val fullText = visionText.text
        return parseFromLinesAndText(lines, fullText, tripId, photoUri)
    }

    /**
     * Parses unstructured string text into [MidClusterData].
     */
    fun parseFromText(
        text: String,
        tripId: String = "",
        photoUri: String? = null
    ): MidClusterData? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        return parseFromLinesAndText(lines, text, tripId, photoUri)
    }

    /**
     * Comprehensive line-by-line and regex extraction parser.
     */
    private fun parseFromLinesAndText(
        lines: List<String>,
        rawText: String,
        tripId: String,
        photoUri: String?
    ): MidClusterData? {
        val normalized = rawText.replace(",", ".")

        // ── 1. Average Fuel Economy Extraction ─────────────────────────────────────────
        var economy: Double? = null

        // Try direct line matching for "Avg. 9.8 km/l" / "9.8 km/l" / "Avg 9.8"
        for (line in lines) {
            val normLine = line.replace(",", ".")
            if (normLine.contains("km/l", ignoreCase = true) || normLine.contains("kmpl", ignoreCase = true) || normLine.contains("km/L", ignoreCase = true)) {
                val m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:km\\s*\\/\\s*l|kmpl|km\\s*\\/\\s*L)", Pattern.CASE_INSENSITIVE).matcher(normLine)
                if (m.find()) {
                    val num = m.group(1)?.toDoubleOrNull()
                    if (num != null && num in 3.0..50.0) {
                        economy = num
                        break
                    }
                }
            } else if (normLine.startsWith("Avg.", ignoreCase = true) && !normLine.contains("km/h", ignoreCase = true)) {
                val m = Pattern.compile("Avg\\.?\\s*[:\\s]?\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE).matcher(normLine)
                if (m.find()) {
                    val num = m.group(1)?.toDoubleOrNull()
                    if (num != null && num in 3.0..50.0) {
                        economy = num
                        break
                    }
                }
            }
        }

        // Global fallback for economy
        if (economy == null) {
            val econMatch = Pattern.compile(
                "(?:Avg\\.?|Average)?\\s*[:\\s]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:km\\s*\\/\\s*l|kmpl|km\\s*\\/\\s*L|l\\s*\\/\\s*100\\s*km)",
                Pattern.CASE_INSENSITIVE
            ).matcher(normalized)
            if (econMatch.find()) {
                economy = econMatch.group(1)?.toDoubleOrNull()
            }
        }

        // ── 2. Average Speed Extraction ──────────────────────────────────────────────
        var avgSpeed: Double? = null
        for (line in lines) {
            val normLine = line.replace(",", ".")
            if (normLine.contains("km/h", ignoreCase = true)) {
                val m = Pattern.compile("(?:Avg\\.?|Average)?\\s*[:\\s]?\\s*(\\d+(?:\\.\\d+)?)\\s*km\\s*\\/\\s*h", Pattern.CASE_INSENSITIVE).matcher(normLine)
                if (m.find()) {
                    val num = m.group(1)?.toDoubleOrNull()
                    if (num != null && num in 1.0..250.0) {
                        avgSpeed = num
                        break
                    }
                }
            }
        }
        if (avgSpeed == null) {
            val speedMatch = Pattern.compile(
                "(?:Avg\\.?|Average)?\\s*[:\\s]?\\s*(\\d+(?:\\.\\d+)?)\\s*km\\s*\\/\\s*h",
                Pattern.CASE_INSENSITIVE
            ).matcher(normalized)
            if (speedMatch.find()) {
                avgSpeed = speedMatch.group(1)?.toDoubleOrNull()
            }
        }

        // ── 3. Duration Extraction ───────────────────────────────────────────────────
        var durText = "0:00 h"
        var durMin = 0

        // Look for explicit "1:27 h" or "1:27h"
        val explicitDurMatch = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*h", Pattern.CASE_INSENSITIVE).matcher(normalized)
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
                // Find any "HH:MM" inside the central area that is not temperature
                for (line in lines) {
                    val m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(line)
                    if (m.find() && !line.contains("°") && !line.contains("C", ignoreCase = true)) {
                        val hours = m.group(1)?.toIntOrNull() ?: 0
                        val mins = m.group(2)?.toIntOrNull() ?: 0
                        if (hours in 0..24 && mins in 0..59) {
                            durMin = hours * 60 + mins
                            durText = "$hours:${String.format(java.util.Locale.US, "%02d", mins)} h"
                            break
                        }
                    }
                }
            }
        }

        // ── 4. Clock Time Extraction ────────────────────────────────────────────────
        var clockTime: String? = null
        val clockMatch = Pattern.compile("(?<!:)(\\b\\d{1,2}:\\d{2}\\b)(?!\\s*h)", Pattern.CASE_INSENSITIVE).matcher(normalized)
        if (clockMatch.find()) {
            clockTime = clockMatch.group(1)
        }

        // ── 5. Distance, Odometer & Range (DTE) Extraction ───────────────────────────
        val kmNumbers = mutableListOf<Double>()
        for (line in lines) {
            val normLine = line.replace(",", ".")
            if (normLine.contains("km", ignoreCase = true) && !normLine.contains("km/h", ignoreCase = true) && !normLine.contains("km/l", ignoreCase = true) && !normLine.contains("kmpl", ignoreCase = true)) {
                val m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km", Pattern.CASE_INSENSITIVE).matcher(normLine)
                while (m.find()) {
                    m.group(1)?.toDoubleOrNull()?.let { kmNumbers.add(it) }
                }
            }
        }

        if (kmNumbers.isEmpty()) {
            val kmMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km(?!\\s*\\/)", Pattern.CASE_INSENSITIVE).matcher(normalized)
            while (kmMatcher.find()) {
                kmMatcher.group(1)?.toDoubleOrNull()?.let { kmNumbers.add(it) }
            }
        }

        // Trip distance is the first number < 1000 km in the central dial
        val distance = kmNumbers.firstOrNull { it in 0.1..999.0 } ?: 0.0
        val odo = kmNumbers.firstOrNull { it >= 1000.0 }
        val remainingKms = kmNumbers.filter { it in 0.1..999.0 && it != distance }
        val range = remainingKms.lastOrNull()

        // ── 6. Ambient Temperature Extraction ────────────────────────────────────────
        var temp: Double? = null
        val tempMatch = Pattern.compile("(\\d{1,2}(?:\\.\\d+)?)\\s*°?\\s*[cC]\\b", Pattern.CASE_INSENSITIVE).matcher(normalized)
        if (tempMatch.find()) {
            temp = tempMatch.group(1)?.toDoubleOrNull()
        }

        // ── 7. Mode Detection ────────────────────────────────────────────────────────
        val mode = when {
            normalized.contains("refuel", ignoreCase = true) -> "Since refuel"
            normalized.contains("long", ignoreCase = true) -> "Long-term"
            else -> "Since start"
        }

        val econResult = economy ?: 0.0
        val speedResult = avgSpeed ?: 0.0

        if (distance <= 0.0 && econResult <= 0.0 && durMin <= 0) return null

        return MidClusterData(
            tripId = tripId,
            durationMinutes = durMin,
            durationText = durText,
            distanceKm = distance,
            avgFuelEconomyKmL = econResult,
            avgSpeedKmh = speedResult,
            totalOdometerKm = odo,
            rangeKm = range,
            ambientTempC = temp,
            mode = mode,
            timeOfDay = clockTime,
            photoUri = photoUri
        )
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
