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
 * Fuel Receipt OCR Data Model.
 */
data class FuelReceiptData(
    val liters: Double? = null,
    val pricePerL: Double? = null,
    val totalAmount: Double? = null,
    val station: String? = null,
    val grade: String? = null,
    val nozzleNumber: String? = null,
    val timestampUtc: String? = null,
    val photoUri: String? = null,
    val rawText: String? = null
) {
    val litres: Double? get() = liters
    val stationName: String? get() = station
    val fuelGrade: String? get() = grade
    val nozzle: String? get() = nozzleNumber
}

/**
 * Multi-Pass High-Precision Fuel Receipt & Petrol Pump Dispenser OCR Engine.
 *
 * Scans thermal receipt paper and digital pump dispenser displays to extract:
 * 1. Liters filled (Quantity / Volume in L)
 * 2. Unit Price per Liter (Rate in ₹/L)
 * 3. Total Amount Paid (₹)
 * 4. Petrol Bunk / Fuel Station Brand (IOCL, HPCL, BPCL, Shell, Jio-bp, Nayara)
 * 5. Fuel Grade (XP95, Power95, Speed97, Petrol / MS)
 * 6. Dispenser Nozzle Number / Bay
 */
object FuelReceiptScanner {

    suspend fun scanReceipt(
        context: Context,
        imageUri: Uri
    ): FuelReceiptData? = scanReceiptImage(context, imageUri)

    suspend fun scanReceiptImage(
        context: Context,
        imageUri: Uri
    ): FuelReceiptData? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        scanFromBytes(bytes, imageUri.toString())
    }

    suspend fun scanFromBytes(
        bytes: ByteArray,
        photoUri: String? = null
    ): FuelReceiptData? = withContext(Dispatchers.IO) {
        val rotation = getExifRotationDegrees(bytes)
        val origBitmap = BitmapFactory.decodeStream(ByteArrayInputStream(bytes)) ?: return@withContext null
        val oriented = if (rotation != 0) rotateBitmap(origBitmap, rotation) else origBitmap

        // Pass 1: Direct Recognition on Original Image
        var result = runOcrPass(oriented)
        var extracted = result?.let { parseReceiptText(it.text, photoUri) }

        // Pass 2: High Contrast Grayscale Pass (for faint thermal prints / glares)
        if (extracted == null || extracted.liters == null || extracted.pricePerL == null) {
            val highContrast = enhanceReceiptContrast(oriented)
            val contrastResult = runOcrPass(highContrast)
            if (contrastResult != null) {
                val contrastExtracted = parseReceiptText(contrastResult.text, photoUri)
                if (contrastExtracted != null && (contrastExtracted.liters != null || contrastExtracted.totalAmount != null)) {
                    extracted = contrastExtracted
                }
            }
        }

        // Pass 3: Cloud Vision Fallback if Gemini Key is available
        if (extracted == null || extracted.liters == null) {
            val cloudData = runCloudReceiptOcr(oriented, photoUri)
            if (cloudData != null && cloudData.liters != null) {
                extracted = cloudData
            }
        }

        extracted
    }

    private suspend fun runOcrPass(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(text)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }

    fun parseReceiptText(text: String, photoUri: String? = null): FuelReceiptData? {
        if (text.isBlank()) return null

        var liters: Double? = null
        var pricePerL: Double? = null
        var totalAmount: Double? = null
        var station: String? = null
        var grade: String? = null
        var nozzle: String? = null

        // 1. Station Brand Matcher
        val fullTextUpper = text.uppercase()
        station = when {
            fullTextUpper.contains("INDIAN OIL") || fullTextUpper.contains("INDIANOIL") || fullTextUpper.contains("IOCL") || fullTextUpper.contains("INDANE") -> "IndianOil"
            fullTextUpper.contains("HINDUSTAN PETROLEUM") || fullTextUpper.contains("HPCL") || fullTextUpper.contains("HP AUTO") -> "HPCL"
            fullTextUpper.contains("BHARAT PETROLEUM") || fullTextUpper.contains("BPCL") -> "BPCL"
            fullTextUpper.contains("SHELL") -> "Shell"
            fullTextUpper.contains("JIO-BP") || fullTextUpper.contains("JIO BP") || fullTextUpper.contains("RELIANCE") -> "Jio-bp"
            fullTextUpper.contains("NAYARA") || fullTextUpper.contains("ESSAR") -> "Nayara Energy"
            else -> "Petrol Pump"
        }

        // 2. Fuel Grade Matcher
        grade = when {
            fullTextUpper.contains("POWER 95") || fullTextUpper.contains("POWER95") -> "POWER 95"
            fullTextUpper.contains("XP95") || fullTextUpper.contains("XP 95") -> "XP95"
            fullTextUpper.contains("SPEED 97") || fullTextUpper.contains("SPEED97") -> "SPEED 97"
            fullTextUpper.contains("SPEED") -> "Speed"
            fullTextUpper.contains("POWER") -> "Power"
            fullTextUpper.contains("X95") -> "X95"
            fullTextUpper.contains("PETROL") || fullTextUpper.contains("MOTOR SPIRIT") || fullTextUpper.contains("MS") || fullTextUpper.contains("UNLEADED") -> "Regular Petrol"
            fullTextUpper.contains("DIESEL") || fullTextUpper.contains("HSD") -> "Diesel"
            else -> "X95"
        }

        // 3. Nozzle Number Matcher (e.g., "Nozzle : 02", "Nozzle No: 4", "NZ: 3", "Pump: 2")
        val nozzlePattern = Pattern.compile("(?i)(?:nozzle\\s*no|nozzle|pump\\s*no|pump|nz|bay)[\\s:#._-]*([0-9]{1,2})")
        val nozzleMatcher = nozzlePattern.matcher(text)
        if (nozzleMatcher.find()) {
            val num = nozzleMatcher.group(1)?.toIntOrNull()
            nozzle = if (num != null) "Nozzle #$num" else "Nozzle #${nozzleMatcher.group(1)}"
        }

        // 4. Volume / Liters Matcher (e.g. "Volume: 38.54", "Qty: 35.20 Ltr", "38.54 L")
        val volumePattern = Pattern.compile("(?i)(?:volume|qty\\s*\\(l\\)|qty|quantity|litres|ltrs|vol\\s*\\(l\\)|vol)[\\s:#._-]*([0-9]{1,3}(?:\\.[0-9]{1,3})?)")
        val volumeMatcher = volumePattern.matcher(text)
        if (volumeMatcher.find()) {
            liters = volumeMatcher.group(1)?.toDoubleOrNull()
        }

        // 5. Rate / Price per Liter Matcher (e.g. "Rate: 107.50", "Price/Ltr: 115.72", "₹107.50")
        val ratePattern = Pattern.compile("(?i)(?:rate\\/l|rate|price\\/ltr|price\\/l|price|unit\\s*price|rsp)[\\s:#._-]*₹?\\s*(?:rs\\.?\\s*)?([0-9]{2,3}(?:\\.[0-9]{1,2})?)")
        val rateMatcher = ratePattern.matcher(text)
        if (rateMatcher.find()) {
            pricePerL = rateMatcher.group(1)?.toDoubleOrNull()
        }

        // 6. Total Amount Matcher (e.g. "Amount: 4144.00", "Total: 4144", "Net Amount: 3500.00")
        val amountPattern = Pattern.compile("(?i)(?:net\\s*amount|total\\s*sale|amount|total|net\\s*amt|sale)[\\s:#._-]*₹?\\s*(?:rs\\.?\\s*)?([0-9]{3,6}(?:\\.[0-9]{1,2})?)")
        val amountMatcher = amountPattern.matcher(text)
        if (amountMatcher.find()) {
            totalAmount = amountMatcher.group(1)?.toDoubleOrNull()
        }

        // 7. Contextual Fallback Extraction if labels were missing
        if (liters == null || pricePerL == null || totalAmount == null) {
            val decimalNumbers = mutableListOf<Double>()
            val numPattern = Pattern.compile("\\b([0-9]{1,5}\\.[0-9]{1,3})\\b")
            val numMatcher = numPattern.matcher(text)
            while (numMatcher.find()) {
                numMatcher.group(1)?.toDoubleOrNull()?.let { decimalNumbers.add(it) }
            }

            // In typical fuel receipts:
            // Price/L is in 95.0..130.0 range
            // Volume is in 5.0..55.0 range
            // Amount is in 500.0..6000.0 range
            if (pricePerL == null) {
                pricePerL = decimalNumbers.firstOrNull { it in 95.0..130.0 }
            }
            if (liters == null) {
                liters = decimalNumbers.firstOrNull { it in 5.0..55.0 && it != pricePerL }
            }
            if (totalAmount == null) {
                totalAmount = decimalNumbers.firstOrNull { it in 500.0..7000.0 && it != pricePerL && it != liters }
            }
        }

        // Cross-reconcile Amount = Liters * Price
        if (totalAmount != null && pricePerL != null && (liters == null || liters <= 0.0)) {
            liters = totalAmount / pricePerL
        } else if (totalAmount != null && liters != null && (pricePerL == null || pricePerL <= 0.0)) {
            pricePerL = totalAmount / liters
        } else if (liters != null && pricePerL != null && totalAmount == null) {
            totalAmount = liters * pricePerL
        }

        return FuelReceiptData(
            liters = liters,
            pricePerL = pricePerL,
            totalAmount = totalAmount,
            station = station ?: "Petrol Pump",
            grade = grade ?: "X95",
            nozzleNumber = nozzle,
            photoUri = photoUri,
            rawText = text
        )
    }

    private suspend fun runCloudReceiptOcr(bitmap: Bitmap, photoUri: String?): FuelReceiptData? {
        val system = "Analyze this fuel receipt photo or petrol pump dispenser screen. Return a strict JSON with: {\"liters\": double, \"pricePerL\": double, \"totalAmount\": double, \"station\": string, \"grade\": string, \"nozzleNumber\": string}"
        val user = "Extract fuel pump receipt details."
        val response = GeminiTextClient.generate(system, user) ?: return null
        return try {
            val clean = response.substringAfter("{").substringBeforeLast("}")
            val json = JSONObject("{$clean}")
            FuelReceiptData(
                liters = json.optDouble("liters", Double.NaN).takeIf { !it.isNaN() },
                pricePerL = json.optDouble("pricePerL", Double.NaN).takeIf { !it.isNaN() },
                totalAmount = json.optDouble("totalAmount", Double.NaN).takeIf { !it.isNaN() },
                station = json.optString("station", null)?.takeIf { it.isNotBlank() },
                grade = json.optString("grade", null)?.takeIf { it.isNotBlank() },
                nozzleNumber = json.optString("nozzleNumber", null)?.takeIf { it.isNotBlank() },
                photoUri = photoUri
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun enhanceReceiptContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val contrast = 1.8f
        val brightness = -30f
        val scale = contrast
        val translate = (1f - contrast) * 128f + brightness
        val cmContrast = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(cmContrast)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun getExifRotationDegrees(bytes: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
