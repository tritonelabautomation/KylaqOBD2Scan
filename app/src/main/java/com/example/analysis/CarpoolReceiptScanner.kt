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
 * Scans ride-share summaries (Quick Ride, SRide, Uber Pool, Ola Share, etc.) to extract:
 * 1. Individual Rider names (e.g. "Yaswanth", "Kashyap Sharma")
 * 2. Per-rider earned fare / reimbursement (e.g. ₹246, ₹124)
 * 3. Total Ride Earnings (e.g. ₹495 Earned)
 * 4. Ride route endpoints (e.g. "VEDHIK KIDS PLAY SC..." -> "Sattva Knowledge City...")
 * 5. Shared distance in km if displayed.
 */
object CarpoolReceiptScanner {

    private val STOP_WORDS = setOf(
        "ride", "completed", "successfully", "okay", "amount", "credited", "your",
        "wallet", "live", "location", "stopped", "when", "the", "ended", "hop", "off",
        "on", "earned", "total", "fare", "payment", "received", "cancelled", "driver",
        "passenger", "co-rider", "km", "min", "mins", "hrs", "cash", "upi", "paytm",
        "gpay", "share", "quick", "sride", "uber", "ola", "route", "trip"
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
        val rotation = getExifRotationDegrees(bytes)
        val rawBitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

        if (rawBitmap != null) {
            val orientedBitmap = if (rotation != 0) rotateBitmap(rawBitmap, rotation) else rawBitmap

            // Pass 1: Direct on-device ML Kit OCR
            val pass1 = runMlKitPass(orientedBitmap, photoUri)
            if (pass1 != null && (pass1.riders.isNotEmpty() || (pass1.totalEarned ?: 0.0) > 0.0)) {
                return@withContext pass1
            }

            // Pass 2: Enhanced contrast
            val contrastBitmap = enhanceContrast(orientedBitmap)
            val pass2 = runMlKitPass(contrastBitmap, photoUri)
            if (pass2 != null && (pass2.riders.isNotEmpty() || (pass2.totalEarned ?: 0.0) > 0.0)) {
                return@withContext pass2
            }
        }

        // Pass 3: Gemini Vision fallback if key configured
        if (GeminiTextClient.isConfigured()) {
            val aiResult = scanWithGeminiVision(bytes, photoUri)
            if (aiResult != null && (aiResult.riders.isNotEmpty() || (aiResult.totalEarned ?: 0.0) > 0.0)) {
                return@withContext aiResult
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
                    val result = parseVisionText(visionText, photoUri)
                    cont.resume(result)
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        } catch (_: Exception) {
            cont.resume(null)
        }
    }

    fun parseVisionText(visionText: Text, photoUri: String?): CarpoolScanResult {
        val fullText = visionText.text
        if (fullText.isBlank()) return CarpoolScanResult(photoUri = photoUri, rawText = fullText)

        var totalEarned: Double? = null
        var totalDistanceKm: Double? = null
        var routeFrom: String? = null
        var routeTo: String? = null
        val detectedRiders = mutableListOf<CarpoolCodec.Rider>()

        // 1. Route extraction (e.g. "VEDHIK KIDS PLAY SC... -> Sattva Knowledge City...")
        val routePattern = Pattern.compile("(?i)(.*?)\\s*(?:->|→|—>|-->|to)\\s*(.*)")
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val m = routePattern.matcher(lineText)
                if (m.find()) {
                    val from = m.group(1)?.trim()?.removePrefix("<")?.trim()
                    val to = m.group(2)?.trim()
                    if (!from.isNullOrBlank() && !to.isNullOrBlank() && from.length > 2 && to.length > 2) {
                        if (routeFrom == null) routeFrom = from
                        if (routeTo == null) routeTo = to
                    }
                }
            }
        }

        // 2. Global Total Earned extraction (e.g. "₹495 Earned", "495 Earned", "Total: ₹495")
        val totalEarnedPattern = Pattern.compile("(?:[₹₹]|Rs\\.?|INR)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:Earned|earned|credited|Total|Fare)", Pattern.CASE_INSENSITIVE)
        val totalEarnedPrefixPattern = Pattern.compile("(?:Total|Earned|Fare|Credited)\\s*(?:Amount|Earned)?\\s*[:=-]?\\s*(?:[₹₹]|Rs\\.?|INR)?\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)

        for (line in fullText.lines()) {
            val trimmed = line.trim()
            val m1 = totalEarnedPattern.matcher(trimmed)
            if (m1.find()) {
                val amt = m1.group(1)?.toDoubleOrNull()
                if (amt != null && amt > 0.0 && (totalEarned == null || amt > totalEarned)) {
                    totalEarned = amt
                }
            }
            val m2 = totalEarnedPrefixPattern.matcher(trimmed)
            if (m2.find()) {
                val amt = m2.group(1)?.toDoubleOrNull()
                if (amt != null && amt > 0.0 && (totalEarned == null || amt > totalEarned)) {
                    totalEarned = amt
                }
            }
        }

        // 3. Distance extraction (e.g. "31.2 km", "24 km")
        val distancePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:km|kms)\\b", Pattern.CASE_INSENSITIVE)
        val distMatcher = distancePattern.matcher(fullText)
        if (distMatcher.find()) {
            val d = distMatcher.group(1)?.toDoubleOrNull()
            if (d != null && d > 0.1 && d < 1000.0) {
                totalDistanceKm = d
            }
        }

        // 4. Structured Block / Line Analysis for individual riders
        // In Quick Ride / SRide:
        // A rider card contains:
        // - Rider Name (e.g. "Yaswanth", "Kashyap Sharma")
        // - Tag (e.g. "Hop Off")
        // - "Earned" badge
        // - Amount transition or single amount (e.g. "₹192 -> ₹246", "₹97 -> ₹124", "₹120")
        for (block in visionText.textBlocks) {
            val lines = block.lines.map { it.text.trim() }.filter { it.isNotBlank() }
            val blockStr = lines.joinToString(" ")

            // Check if block contains Earned or Hop Off or currency
            if (blockStr.contains("Hop Off", ignoreCase = true) ||
                blockStr.contains("Hop On", ignoreCase = true) ||
                blockStr.contains("Earned", ignoreCase = true) ||
                blockStr.contains("₹")
            ) {
                var candidateName: String? = null
                var candidateAmount: Double? = null
                var candidateDist: Double? = null

                for (l in lines) {
                    val clean = l.replace(Regex("[^a-zA-Z0-9 .₹\\-—>]"), "").trim()

                    // Check for amount transition: e.g. "192 246" or "₹192 -> ₹246" or "₹246"
                    val amounts = extractAmounts(clean)
                    if (amounts.isNotEmpty()) {
                        // If multiple amounts in a surge transition, the last/highest is the final earned amount
                        val maxAmt = amounts.maxOrNull()
                        if (maxAmt != null && maxAmt > 0.0 && maxAmt < 10000.0) {
                            candidateAmount = maxAmt
                        }
                    }

                    // Check for distance: e.g. "15.4 km"
                    val mDist = distancePattern.matcher(l)
                    if (mDist.find()) {
                        candidateDist = mDist.group(1)?.toDoubleOrNull()
                    }

                    // Check for rider name
                    if (isLikelyRiderName(clean)) {
                        candidateName = clean
                    }
                }

                if (candidateName != null && candidateAmount != null && candidateAmount > 0.0) {
                    // Check if already added
                    if (detectedRiders.none { it.name.equals(candidateName, ignoreCase = true) }) {
                        detectedRiders.add(
                            CarpoolCodec.Rider(
                                name = candidateName,
                                amount = candidateAmount,
                                distanceKm = candidateDist
                            )
                        )
                    }
                }
            }
        }

        // 5. Multi-line window fallback if text blocks were split across ML Kit hierarchy
        if (detectedRiders.isEmpty()) {
            val allLines = visionText.textBlocks.flatMap { it.lines.map { l -> l.text.trim() } }
            var i = 0
            while (i < allLines.size) {
                val line = allLines[i]
                if (isLikelyRiderName(line)) {
                    val name = line
                    // Look ahead 1..3 lines for amounts
                    var amt: Double? = null
                    var dist: Double? = null
                    for (j in (i + 1)..minOf(i + 4, allLines.size - 1)) {
                        val nextLine = allLines[j]
                        val extracted = extractAmounts(nextLine)
                        if (extracted.isNotEmpty()) {
                            amt = extracted.maxOrNull()
                        }
                        val md = distancePattern.matcher(nextLine)
                        if (md.find()) dist = md.group(1)?.toDoubleOrNull()
                        if (amt != null) break
                    }
                    if (amt != null && amt > 0.0 && detectedRiders.none { it.name.equals(name, ignoreCase = true) }) {
                        detectedRiders.add(CarpoolCodec.Rider(name = name, amount = amt, distanceKm = dist))
                        i += 2
                        continue
                    }
                }
                i++
            }
        }

        // 6. If total earned was detected and no riders were extracted, generate a fallback entry
        val finalRiders = if (detectedRiders.isEmpty() && totalEarned != null && totalEarned > 0.0) {
            listOf(CarpoolCodec.Rider(name = "Carpool Rider", amount = totalEarned, distanceKm = totalDistanceKm))
        } else {
            detectedRiders.take(CarpoolCodec.MAX_RIDERS)
        }

        return CarpoolScanResult(
            riders = finalRiders,
            totalEarned = totalEarned ?: finalRiders.sumOf { it.amount }.takeIf { it > 0.0 },
            totalDistanceKm = totalDistanceKm,
            routeFrom = routeFrom,
            routeTo = routeTo,
            rideStatus = if (fullText.contains("Ride Completed", ignoreCase = true)) "Completed" else null,
            photoUri = photoUri,
            rawText = fullText
        )
    }

    private fun extractAmounts(text: String): List<Double> {
        val amounts = mutableListOf<Double>()
        // Match ₹123, Rs 123, 123.00, or numbers following ₹ / Rs
        val currencyRegex = Pattern.compile("(?:[₹₹]|Rs\\.?|INR)?\\s*(\\d+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE)
        val matcher = currencyRegex.matcher(text)
        while (matcher.find()) {
            val numStr = matcher.group(1)
            val v = numStr?.toDoubleOrNull()
            if (v != null && v > 5.0 && v < 20000.0) {
                // Filter out common times (e.g. 2026, 0908, 1928, etc. if no currency)
                val isTimeLike = v >= 1900.0 && v <= 2100.0 && !text.contains("₹") && !text.contains("Rs")
                if (!isTimeLike) {
                    amounts.add(v)
                }
            }
        }
        return amounts
    }

    private fun isLikelyRiderName(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 2 || trimmed.length > 30) return false
        val words = trimmed.split(Regex("\\s+"))
        if (words.size > 4) return false

        val lower = trimmed.lowercase(Locale.ROOT)
        if (STOP_WORDS.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) return false
        if (trimmed.contains("₹") || trimmed.contains("Rs") || trimmed.contains("%") || trimmed.contains("@")) return false
        if (trimmed.any { it.isDigit() }) return false

        // Must have at least 2 letters
        return trimmed.count { it.isLetter() } >= 2
    }

    private suspend fun scanWithGeminiVision(
        imageBytes: ByteArray,
        photoUri: String?
    ): CarpoolScanResult? = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Analyze this carpool ride confirmation screenshot (e.g., Quick Ride / SRide / Uber Pool).
                Extract the details in strict JSON format:
                {
                  "total_earned": <number or null>,
                  "total_distance_km": <number or null>,
                  "route_from": "<string or null>",
                  "route_to": "<string or null>",
                  "riders": [
                    {
                      "name": "<rider name>",
                      "amount": <earned amount for this rider as number>,
                      "distance_km": <shared distance as number or null>
                    }
                  ]
                }
                Return ONLY valid JSON.
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
