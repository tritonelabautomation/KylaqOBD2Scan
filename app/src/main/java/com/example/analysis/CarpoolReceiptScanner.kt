package com.example.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import com.example.ai.GeminiTextClient
import com.example.data.CarpoolCodec
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Carpool Ride Summary & Payment Receipt OCR Result.
 */
data class CarpoolScanResult(
    val riders: List<CarpoolCodec.Rider> = emptyList(),
    val totalEarned: Double? = null,
    val totalDistanceKm: Double? = null,
    val routeFrom: String? = null,
    val routeTo: String? = null,
    val rideStatus: String? = null,
    val photoUri: String? = null,
    val rawText: String? = null
)

/**
 * High-Precision Multi-Pass Carpool Screenshot OCR Engine.
 *
 * Scans ride-share summaries (Quick Ride, SRide, Uber Pool, Ola Share, etc.) using:
 *  1. Gemini Vision AI as primary when configured (accurate semantic layout & surge understanding).
 *  2. 2D Spatial On-Device ML Kit OCR (Y-axis horizontal band clustering to pair left-column rider
 *     names with right-column surge fares).
 *  3. Status-bar / Navigation-bar filtering (prevents battery % and clocks from corrupting fares).
 *  4. Rupee symbol glyph misread correction (e.g., ₹495 misread as 1495).
 */
object CarpoolReceiptScanner {

    private val STOP_WORDS = setOf(
        "ride", "completed", "successfully", "okay", "amount", "credited", "your",
        "wallet", "live", "location", "stopped", "when", "the", "ended", "hop", "off",
        "on", "earned", "total", "fare", "payment", "received", "cancelled", "driver",
        "passenger", "co-rider", "km", "min", "mins", "hrs", "cash", "upi", "paytm",
        "gpay", "share", "quick", "sride", "uber", "ola", "route", "trip", "summary",
        "riders", "details", "rating", "feedback"
    )

    data class SpatialLine(
        val text: String,
        val bbox: Rect,
        val centerY: Int = bbox.centerY()
    )

    suspend fun scanCarpoolImage(
        context: Context,
        imageUri: Uri
    ): CarpoolScanResult? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        scanFromBytes(bytes, imageUri.toString())
    }

    suspend fun scanFromBytes(
        bytes: ByteArray,
        photoUri: String? = null
    ): CarpoolScanResult? = withContext(Dispatchers.IO) {
        // Pass 1: Gemini Vision AI (Primary when API key is available)
        if (GeminiTextClient.isConfigured()) {
            val aiResult = scanWithGeminiVision(bytes, photoUri)
            if (aiResult != null && (aiResult.riders.isNotEmpty() || (aiResult.totalEarned ?: 0.0) > 0.0)) {
                return@withContext aiResult
            }
        }

        val rotation = getExifRotationDegrees(bytes)
        val rawBitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

        if (rawBitmap != null) {
            val orientedBitmap = if (rotation != 0) rotateBitmap(rawBitmap, rotation) else rawBitmap

            // Pass 2: 2D Spatial On-Device ML Kit OCR
            val pass2 = runMlKitPass(orientedBitmap, photoUri)
            if (pass2 != null && pass2.riders.isNotEmpty()) {
                return@withContext pass2
            }

            // Pass 3: Contrast Enhanced ML Kit OCR
            val contrastBitmap = enhanceContrast(orientedBitmap)
            val pass3 = runMlKitPass(contrastBitmap, photoUri)
            if (pass3 != null && pass3.riders.isNotEmpty()) {
                return@withContext pass3
            }

            // Fallback to highest confidence partial result
            val bestPartial = listOfNotNull(pass2, pass3)
                .maxByOrNull { (it.riders.size * 10) + (if ((it.totalEarned ?: 0.0) > 0) 5 else 0) }
            if (bestPartial != null && (bestPartial.riders.isNotEmpty() || (bestPartial.totalEarned ?: 0.0) > 0.0)) {
                return@withContext bestPartial
            }
        }

        null
    }

    private suspend fun runMlKitPass(
        bitmap: Bitmap,
        photoUri: String?
    ): CarpoolScanResult? = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val result = parseVisionText(visionText, bitmap.width, bitmap.height, photoUri)
                    cont.resume(result)
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        } catch (_: Exception) {
            cont.resume(null)
        }
    }

    /**
     * 2D Spatial Layout Parser for ML Kit VisionText.
     */
    fun parseVisionText(
        visionText: Text,
        imageWidth: Int = 1080,
        imageHeight: Int = 2400,
        photoUri: String? = null
    ): CarpoolScanResult {
        val fullText = visionText.text
        if (fullText.isBlank()) return CarpoolScanResult(photoUri = photoUri, rawText = fullText)

        val rawLines = mutableListOf<SpatialLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: Rect(0, 0, 0, 0)
                rawLines.add(SpatialLine(text = line.text.trim(), bbox = box))
            }
        }

        val effHeight = if (imageHeight > 0) imageHeight else (rawLines.maxOfOrNull { it.bbox.bottom } ?: 1000)

        // 1. Filter out top status bar (Y < 7%) and bottom nav bar (Y > 95%)
        val validLines = rawLines.filter {
            if (effHeight > 0 && it.bbox.height() > 0) {
                it.centerY >= effHeight * 0.06 && it.centerY <= effHeight * 0.95
            } else true
        }

        var totalEarned: Double? = null
        var totalDistanceKm: Double? = null
        var routeFrom: String? = null
        var routeTo: String? = null
        val detectedRiders = mutableListOf<CarpoolCodec.Rider>()

        // 2. Route extraction from top header region
        val routePattern = Pattern.compile("(?i)(?:Route:\\s*)?(.*?)\\s*(?:->|→|—>|-->|to)\\s*(.*)")
        for (line in validLines) {
            if (line.centerY < effHeight * 0.35 || effHeight <= 0) {
                val m = routePattern.matcher(line.text)
                if (m.find()) {
                    val from = m.group(1)?.trim()?.removePrefix("<")?.removePrefix("Route:")?.trim()
                    val to = m.group(2)?.trim()
                    if (!from.isNullOrBlank() && !to.isNullOrBlank() && from.length > 2 && to.length > 2) {
                        if (routeFrom == null) routeFrom = from
                        if (routeTo == null) routeTo = to
                    }
                }
            }
        }

        // 3. Overall Distance Extraction
        val distancePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:km|kms)\\b", Pattern.CASE_INSENSITIVE)
        val distMatcher = distancePattern.matcher(fullText)
        if (distMatcher.find()) {
            val d = distMatcher.group(1)?.toDoubleOrNull()
            if (d != null && d > 0.1 && d < 1000.0) {
                totalDistanceKm = d
            }
        }

        // 4. 2D Spatial Card Clustering for Riders (pairs rider name with right-hand fare/surge amount)
        val bandTolerance = ((effHeight * 0.05).toInt()).coerceIn(40, 160)
        for (line in validLines) {
            if (isLikelyRiderName(line.text) && (line.centerY < effHeight * 0.70 || effHeight <= 0)) {
                val name = line.text.replace(":", "").trim()
                val cy = line.centerY

                // Find all lines in the horizontal band of this card
                val bandLines = validLines.filter { Math.abs(it.centerY - cy) <= bandTolerance }
                val bandAmounts = mutableListOf<Double>()
                var bandDistance: Double? = null

                for (bLine in bandLines) {
                    if (bLine.text != line.text) {
                        val extracted = extractAmounts(bLine.text)
                        bandAmounts.addAll(extracted)
                        val mDist = distancePattern.matcher(bLine.text)
                        if (mDist.find() && bandDistance == null) {
                            bandDistance = mDist.group(1)?.toDoubleOrNull()
                        }
                    }
                }

                // In Quick Ride surge pricing (e.g. "₹192 ↗ ₹246"), the last/highest amount is the credited fare
                val finalAmt = if (bandAmounts.isNotEmpty()) bandAmounts.last() else null

                if (finalAmt != null && finalAmt > 0.0 && detectedRiders.none { it.name.equals(name, ignoreCase = true) }) {
                    detectedRiders.add(
                        CarpoolCodec.Rider(
                            name = name,
                            amount = finalAmt,
                            distanceKm = bandDistance
                        )
                    )
                }
            }
        }

        // 5. Total Earned Extraction & Rupee Symbol Glyph Misread Correction
        for (line in validLines) {
            if (line.text.contains("Total", ignoreCase = true) ||
                line.text.contains("Earned", ignoreCase = true) ||
                line.text.contains("credited", ignoreCase = true)
            ) {
                val extracted = extractAmounts(line.text)
                if (extracted.isNotEmpty()) {
                    var amt = extracted.last()
                    // Rupee symbol artifact correction: e.g. "₹495" OCRed as "1495"
                    if (amt >= 1000.0 && amt < 2000.0) {
                        val candidate = amt - 1000.0
                        val riderSum = detectedRiders.sumOf { it.amount }
                        if (candidate >= riderSum * 0.75 && candidate in 50.0..999.0) {
                            amt = candidate
                        }
                    }
                    if (totalEarned == null || amt > totalEarned) {
                        totalEarned = amt
                    }
                }
            }
        }

        // 6. Global regex fallback for unstructured text
        if (detectedRiders.isEmpty()) {
            val fallbackResult = parseFromUnstructuredText(fullText, photoUri)
            if (fallbackResult.riders.isNotEmpty()) {
                return fallbackResult
            }
        }

        val finalTotal = totalEarned ?: detectedRiders.sumOf { it.amount }.takeIf { it > 0.0 }

        val finalRiders = if (detectedRiders.isEmpty() && finalTotal != null && finalTotal > 0.0) {
            listOf(CarpoolCodec.Rider(name = "Carpool Rider", amount = finalTotal, distanceKm = totalDistanceKm))
        } else {
            detectedRiders.take(CarpoolCodec.MAX_RIDERS)
        }

        return CarpoolScanResult(
            riders = finalRiders,
            totalEarned = finalTotal,
            totalDistanceKm = totalDistanceKm,
            routeFrom = routeFrom,
            routeTo = routeTo,
            rideStatus = if (fullText.contains("Ride Completed", ignoreCase = true)) "Completed" else null,
            photoUri = photoUri,
            rawText = fullText
        )
    }

    /**
     * Unstructured text parser for plain strings (e.g. unit test mocks or non-spatial fallbacks).
     */
    fun parseFromUnstructuredText(text: String, photoUri: String? = null): CarpoolScanResult {
        var totalEarned: Double? = null
        var totalDistanceKm: Double? = null
        var routeFrom: String? = null
        var routeTo: String? = null
        val detectedRiders = mutableListOf<CarpoolCodec.Rider>()

        val routePattern = Pattern.compile("(?i)(?:Route:\\s*)?(.*?)\s*(?:->|→|—>|-->|to)\s*(.*)")
        for (line in text.lines()) {
            val m = routePattern.matcher(line.trim())
            if (m.find()) {
                val f = m.group(1)?.trim()?.removePrefix("<")?.removePrefix("Route:")?.trim()
                val t = m.group(2)?.trim()
                if (!f.isNullOrBlank() && !t.isNullOrBlank() && f.length > 2 && t.length > 2) {
                    if (routeFrom == null) routeFrom = f
                    if (routeTo == null) routeTo = t
                }
            }
        }

        val distancePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:km|kms)\\b", Pattern.CASE_INSENSITIVE)
        val distMatcher = distancePattern.matcher(text)
        if (distMatcher.find()) {
            val d = distMatcher.group(1)?.toDoubleOrNull()
            if (d != null && d > 0.1 && d < 1000.0) {
                totalDistanceKm = d
            }
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isLikelyRiderName(line)) {
                val name = line.replace(":", "").trim()
                var amt: Double? = null
                var dist: Double? = null
                for (j in (i + 1)..minOf(i + 4, lines.size - 1)) {
                    val nextLine = lines[j]
                    if (isLikelyRiderName(nextLine)) break
                    val extracted = extractAmounts(nextLine)
                    if (extracted.isNotEmpty() && amt == null) {
                        amt = extracted.last()
                    }
                    val md = distancePattern.matcher(nextLine)
                    if (md.find() && dist == null) {
                        dist = md.group(1)?.toDoubleOrNull()
                    }
                    if (amt != null && dist != null) break
                }
                if (amt != null && amt > 0.0 && detectedRiders.none { it.name.equals(name, ignoreCase = true) }) {
                    detectedRiders.add(CarpoolCodec.Rider(name = name, amount = amt, distanceKm = dist))
                }
            }
            i++
        }

        for (line in lines) {
            if (line.contains("Total", ignoreCase = true) ||
                line.contains("Earned", ignoreCase = true) ||
                line.contains("credited", ignoreCase = true)
            ) {
                val extracted = extractAmounts(line)
                if (extracted.isNotEmpty()) {
                    var amt = extracted.last()
                    if (amt >= 1000.0 && amt < 2000.0) {
                        val candidate = amt - 1000.0
                        val riderSum = detectedRiders.sumOf { it.amount }
                        if (candidate >= riderSum * 0.75 && candidate in 50.0..999.0) {
                            amt = candidate
                        }
                    }
                    if (totalEarned == null || amt > totalEarned) {
                        totalEarned = amt
                    }
                }
            }
        }

        val finalTotal = totalEarned ?: detectedRiders.sumOf { it.amount }.takeIf { it > 0.0 }
        val finalRiders = if (detectedRiders.isEmpty() && finalTotal != null && finalTotal > 0.0) {
            listOf(CarpoolCodec.Rider(name = "Carpool Rider", amount = finalTotal, distanceKm = totalDistanceKm))
        } else {
            detectedRiders.take(CarpoolCodec.MAX_RIDERS)
        }

        return CarpoolScanResult(
            riders = finalRiders,
            totalEarned = finalTotal,
            totalDistanceKm = totalDistanceKm,
            routeFrom = routeFrom,
            routeTo = routeTo,
            rideStatus = if (text.contains("Ride Completed", ignoreCase = true)) "Completed" else null,
            photoUri = photoUri,
            rawText = text
        )
    }

    /**
     * Extracts currency amounts while filtering out distance markers (km), durations (min/hr), and battery percentages.
     */
    fun extractAmounts(text: String): List<Double> {
        val amounts = mutableListOf<Double>()
        // Strip distance tokens (e.g. "14.8 km"), time tokens (e.g. "20 mins"), and percentage tokens (e.g. "86%")
        val clean = text.replace(Regex("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:km|kms|min|mins|hr|hrs|%)\\b"), "")
            .replace('↗', ' ')
            .replace("->", " ")
            .replace("→", " ")

        val currencyRegex = Pattern.compile("(?:[₹₹]|Rs\\.?|INR)?\\s*(\\d+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE)
        val matcher = currencyRegex.matcher(clean)
        while (matcher.find()) {
            val numStr = matcher.group(1)
            val v = numStr?.toDoubleOrNull()
            if (v != null && v >= 5.0 && v <= 10000.0) {
                val isTimeLike = v >= 1900.0 && v <= 2100.0 && !text.contains("₹") && !text.contains("Rs")
                if (!isTimeLike) {
                    amounts.add(v)
                }
            }
        }
        return amounts
    }

    fun isLikelyRiderName(line: String): Boolean {
        val trimmed = line.replace(":", "").trim()
        if (trimmed.length < 2 || trimmed.length > 30) return false
        val words = trimmed.split(Regex("\\s+"))
        if (words.size > 4) return false

        val lower = trimmed.lowercase(Locale.ROOT)
        if (STOP_WORDS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) return false
        if (trimmed.contains("₹") || trimmed.contains("Rs") || trimmed.contains("%") || trimmed.contains("@") || trimmed.contains("<") || trimmed.contains(">")) return false
        if (trimmed.any { it.isDigit() }) return false

        return trimmed.count { it.isLetter() } >= 2
    }

    /**
     * Multimodal Gemini Vision Cloud Fallback for structured carpool extraction.
     */
    private suspend fun scanWithGeminiVision(
        imageBytes: ByteArray,
        photoUri: String?
    ): CarpoolScanResult? = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are an expert OCR analysis system for carpool / ride-share completion screenshots (Quick Ride, sRide, Uber, Ola).
                Extract the carpool ride details from this screenshot image.
                
                CRITICAL INSTRUCTIONS:
                1. Extract all individual rider names (e.g. "Yaswanth", "Kashyap Sharma") and their final earned amounts.
                2. In Quick Ride surge pricing (e.g. "₹192 ↗ ₹246"), extract the FINAL surge earned amount on the right (e.g. 246.0, NOT the original 192.0).
                3. Extract the total ride earned amount (e.g. "₹495 Earned" -> 495.0). Be careful NOT to prepend a 1 if the rupee symbol looks like a digit.
                4. Extract the route start and end if visible (e.g. "VEDHIK KIDS PLAY SC..." -> "Sattva Knowledge City...").
                5. Extract any total or per-rider distance in km if visible.
                
                Reply with ONLY a valid JSON object in this exact schema:
                {
                  "service": "Quick Ride",
                  "route_from": "VEDHIK KIDS PLAY SC...",
                  "route_to": "Sattva Knowledge City...",
                  "total_earned": 495.0,
                  "total_distance_km": null,
                  "riders": [
                    {
                      "name": "Yaswanth",
                      "amount": 246.0,
                      "distance_km": null
                    },
                    {
                      "name": "Kashyap Sharma",
                      "amount": 124.0,
                      "distance_km": null
                    }
                  ]
                }
            """.trimIndent()

            val response = GeminiTextClient.scanCustomJson(imageBytes, prompt, "image/jpeg")
                ?: return@withContext null

            val jsonStr = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(jsonStr)
            val totalEarned = json.optDouble("total_earned").takeIf { !it.isNaN() && it > 0.0 }
            val dist = json.optDouble("total_distance_km").takeIf { !it.isNaN() && it > 0.0 }
            val routeFrom = json.optString("route_from").takeIf { it.isNotBlank() && it != "null" }
            val routeTo = json.optString("route_to").takeIf { it.isNotBlank() && it != "null" }

            val ridersArray = json.optJSONArray("riders")
            val ridersList = mutableListOf<CarpoolCodec.Rider>()
            if (ridersArray != null) {
                for (i in 0 until ridersArray.length()) {
                    val rObj = ridersArray.getJSONObject(i)
                    val name = rObj.optString("name", "Rider ${i + 1}")
                    val amt = rObj.optDouble("amount")
                    val rDist = rObj.optDouble("distance_km").takeIf { !it.isNaN() && it > 0.0 }
                    if (!amt.isNaN() && amt > 0.0) {
                        ridersList.add(CarpoolCodec.Rider(name = name, amount = amt, distanceKm = rDist))
                    }
                }
            }

            CarpoolScanResult(
                riders = ridersList,
                totalEarned = totalEarned ?: ridersList.sumOf { it.amount }.takeIf { it > 0.0 },
                totalDistanceKm = dist,
                routeFrom = routeFrom,
                routeTo = routeTo,
                photoUri = photoUri,
                rawText = response
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun enhanceContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix().apply {
            set(
                floatArrayOf(
                    1.6f, 0f, 0f, 0f, -40f,
                    0f, 1.6f, 0f, 0f, -40f,
                    0f, 0f, 1.6f, 0f, -40f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun getExifRotationDegrees(bytes: ByteArray): Int = try {
        val exifInterface = ExifInterface(ByteArrayInputStream(bytes))
        when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
