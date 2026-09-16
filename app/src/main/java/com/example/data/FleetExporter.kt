package com.example.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * VehIQ/Fuelio "Export data" without the paywall: one unified ledger (fuel, services, expenses,
 * documents) rendered as CSV, JSON or a framework-generated PDF summary. Pure rendering helpers
 * are separated from the Context-bound writers so they stay unit-testable.
 */
object FleetExporter {

    data class Row(
        val dateUtc: String,
        val type: String,
        val name: String,
        val amount: Double?,
        val odometerKm: Double?,
        val detail: String
    )

    fun collectRows(
        fuel: List<FuelLogCodec.FuelEntry>,
        services: List<MaintenanceCatalog.ServiceLog>,
        expenses: List<ExpenseCodec.ExpenseEntry>,
        documents: List<DocumentCodec.VehicleDocument>
    ): List<Row> {
        val rows = mutableListOf<Row>()
        fuel.forEach {
            rows.add(
                Row(
                    dateUtc = it.dateUtc, type = "Fuel",
                    name = String.format(java.util.Locale.US, "%.2f L @ %.2f", it.liters, it.pricePerL),
                    amount = it.totalCost, odometerKm = it.odometerKm,
                    detail = listOf(it.station, it.grade, it.note).filter(String::isNotBlank).joinToString(" · ")
                )
            )
        }
        services.forEach {
            val label = MaintenanceCatalog.KYLAQ_ITEMS.firstOrNull { s -> s.id == it.itemId }?.label ?: it.itemId
            rows.add(
                Row(
                    dateUtc = it.dateUtc, type = "Service", name = label,
                    amount = it.cost, odometerKm = it.odometerKm,
                    detail = listOfNotNull(
                        it.rating?.let { r -> "$r/5" },
                        it.notes.takeIf(String::isNotBlank)
                    ).joinToString(" · ")
                )
            )
        }
        expenses.forEach {
            rows.add(
                Row(
                    dateUtc = it.dateUtc, type = it.category, name = it.vendor.ifBlank { it.note },
                    amount = it.amount, odometerKm = null, detail = it.note
                )
            )
        }
        documents.forEach {
            rows.add(
                Row(
                    dateUtc = it.expiryUtc ?: "", type = "Document", name = "${it.type}: ${it.title}",
                    amount = null, odometerKm = null,
                    detail = listOf(it.number, it.issuer).filter(String::isNotBlank).joinToString(" · ")
                )
            )
        }
        return rows.sortedByDescending { it.dateUtc }
    }

    private fun csvCell(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    fun toCsv(rows: List<Row>): String = buildString {
        append("date,type,name,amount,odometer_km,detail\n")
        rows.forEach {
            append(it.dateUtc.take(10)).append(',')
            append(csvCell(it.type)).append(',')
            append(csvCell(it.name)).append(',')
            append(it.amount?.let { a -> String.format(java.util.Locale.US, "%.2f", a) } ?: "").append(',')
            append(it.odometerKm?.let { o -> String.format(java.util.Locale.US, "%.0f", o) } ?: "").append(',')
            append(csvCell(it.detail))
            append('\n')
        }
    }

    fun toJson(rows: List<Row>, vehicleName: String, statsLine: String): String {
        val arr = JSONArray()
        rows.forEach {
            arr.put(
                JSONObject().apply {
                    put("date", it.dateUtc)
                    put("type", it.type)
                    put("name", it.name)
                    it.amount?.let { a -> put("amount", a) }
                    it.odometerKm?.let { o -> put("odometer_km", o) }
                    if (it.detail.isNotBlank()) put("detail", it.detail)
                }
            )
        }
        return JSONObject().apply {
            put("app", "KylaqOBD2Scan")
            put("vehicle", vehicleName)
            put("stats", statsLine)
            put("generated_utc", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date()))
            put("entries", arr)
        }.toString(2)
    }

    /** A4 text PDF via the framework PdfDocument - no dependencies, no print spooler needed. */
    fun writePdf(file: File, title: String, statsLines: List<String>, rows: List<Row>): Boolean {
        return try {
            val document = PdfDocument()
            val paint = Paint().apply { color = Color.BLACK; textSize = 9f }
            val headerPaint = Paint().apply { color = Color.BLACK; textSize = 14f; isFakeBoldText = true }
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            var y = 48f
            page.canvas.drawText(title, 40f, y, headerPaint)
            y += 18f
            statsLines.forEach {
                page.canvas.drawText(it, 40f, y, paint)
                y += 13f
            }
            y += 8f
            page.canvas.drawText("date        type      name                                   amount", 40f, y, headerPaint)
            y += 14f
            rows.forEach { row ->
                if (y > 800f) {
                    document.finishPage(page)
                    pageNumber++
                    page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                    y = 48f
                }
                val line = String.format(
                    java.util.Locale.US, "%-11s %-9s %-38s %8s",
                    row.dateUtc.take(10),
                    row.type.take(9),
                    row.name.take(38),
                    row.amount?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: ""
                )
                page.canvas.drawText(line, 40f, y, paint)
                y += 12f
            }
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Write bytes/text into the app-visible Downloads folder (same target as the Expenses CSV). */
    fun writeToDownloads(context: Context, fileName: String, content: String): File? {
        return try {
            val downloads = File(context.getExternalFilesDir(null), "Downloads").apply { mkdirs() }
            File(downloads, fileName).apply { writeText(content) }
        } catch (e: Exception) {
            null
        }
    }
}
