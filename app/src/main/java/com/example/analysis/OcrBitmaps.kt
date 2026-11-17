package com.example.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.example.data.HeapGuard

/**
 * Decode receipt / MID photos at a bounded size. Adobe Scan JPEGs were
 * `readBytes()` + `decodeByteArray` at full sensor resolution (tens of MB each,
 * several copies for contrast/rotate) and filled the 512 MB heap on 1.0.842.
 */
object OcrBitmaps {

    fun decodeBounded(bytes: ByteArray, rotationDegrees: Int = 0, maxEdge: Int = HeapGuard.OCR_MAX_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth.coerceAtLeast(1)
        val h = bounds.outHeight.coerceAtLeast(1)
        var sample = 1
        while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (rotationDegrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }
}
