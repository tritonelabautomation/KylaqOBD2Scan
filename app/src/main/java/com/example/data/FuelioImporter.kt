package com.example.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Fuelio -> Kylaq one-way import (owner 2026-09-16: "how i can import my data from
 * fuelio to my app?"). Parses Fuelio's own sectioned CSV export:
 *
 * ```
 * ## Vehicle,,,,,,,,,,
 * Name,Description,DistUnit,FuelUnit,ConsumptionUnit,ImportCSVDateFormat,,,,,
 * My Car,Description,1,1,1,yyyy-MM-dd,,,,,
 * ## Log,,,,,,,,,,
 * Data,Odo (km),Fuel (litres),Full,Price (optional),...,City (optional),Notes (optional),Missed
 * 2026-08-01,12000,40.5,1,105.5,,,,,,Station,,0
 * ```
 *
 * Units travel INSIDE the header parentheses (km/mi, litres/us gallons/imperial
 * gallons) and are converted to km + litres; "Price" is per fuel unit and becomes
 * price-per-litre; Full=0 or Missed=1 maps to our partial-fillup semantics.
 * Pure JVM - unit-tested.
 */
object FuelioImporter {

    const val MI_TO_KM = 1.609344
    const val US_GAL_TO_L = 3.785411784
    const val IMP_GAL_TO_L = 4.54609

    data class Parsed(val entries: List<FuelLogCodec.FuelEntry>, val dataRows: Int)

    fun parse(text: String, nowMs: Long): Parsed {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return Parsed(emptyList(), 0)

        val dateFormat = vehicleDateFormat(lines)
        var headerIdx = -1
        val logMark = lines.indexOfFirst { it.startsWith("## Log", ignoreCase = true) }
        if (logMark >= 0 && logMark + 1 <= lines.lastIndex) {
            headerIdx = logMark + 1
        } else {
            // Flat-export fallback: first line that looks like a fill-up header.
            headerIdx = lines.indexOfFirst { l ->
                val c = split(l).map { it.lowercase() }
                (c.any { it.startsWith("date") || it == "data" }) &&
                    (c.any { it.startsWith("odo") }) &&
                    (c.any { it.startsWith("fuel") })
            }
        }
        if (headerIdx < 0) return Parsed(emptyList(), 0)
        val header = split(lines[headerIdx])
        val cols = header.map { it.lowercase() }
        val iDate = cols.indexOfFirst { it == "data" || it.startsWith("date") }
        val iOdo = cols.indexOfFirst { it.startsWith("odo") }
        val iFuel = cols.indexOfFirst { it.startsWith("fuel") }
        val iFull = cols.indexOfFirst { it == "full" }
        val iPrice = cols.indexOfFirst { it.startsWith("price") }
        val iCity = cols.indexOfFirst { it == "city" || it.startsWith("station") }
        val iNotes = cols.indexOfFirst { it.startsWith("notes") }
        val iMissed = cols.indexOfFirst { it.startsWith("missed") }
        if (iDate < 0 || iFuel < 0) return Parsed(emptyList(), 0)

        val odoUnit = unitOf(header.getOrNull(iOdo) ?: "")
        val fuelUnit = unitOf(header.getOrNull(iFuel) ?: "")
        val odoToKm = if (odoUnit.contains("mi")) MI_TO_KM else 1.0
        val unitToL = when {
            fuelUnit.contains("imp") || fuelUnit.contains("uk") -> IMP_GAL_TO_L
            fuelUnit.contains("us") -> US_GAL_TO_L
            fuelUnit.contains("gall") -> US_GAL_TO_L
            else -> 1.0
        }

        val out = mutableListOf<FuelLogCodec.FuelEntry>()
        var row = 0
        for (idx in headerIdx + 1..lines.lastIndex) {
            val line = lines[idx]
            if (line.startsWith("##")) continue
            val c = split(line)
            if (c.size <= iDate || c.size <= iFuel) continue
            val dateMs = parseDate(c[iDate], dateFormat) ?: continue
            val fuelUnits = c[iFuel].toDoubleOrNull() ?: continue
            val liters = fuelUnits * unitToL
            if (liters <= 0.0) continue
            val odoKm = if (iOdo >= 0 && c.size > iOdo) c[iOdo].toDoubleOrNull()?.let { it * odoToKm } else null
            val pricePerUnit = if (iPrice >= 0 && c.size > iPrice) c[iPrice].toDoubleOrNull() else null
            val full = if (iFull >= 0 && c.size > iFull) c[iFull].toIntOrNull() else null
            val missed = if (iMissed >= 0 && c.size > iMissed) c[iMissed].toIntOrNull() else null
            val station = if (iCity >= 0 && c.size > iCity) c[iCity].trim() else ""
            val notes = if (iNotes >= 0 && c.size > iNotes) c[iNotes].trim() else ""
            val pricePerL = (pricePerUnit ?: 0.0).let { if (it > 0.0) it / unitToL else 0.0 }
            val note = buildString {
                append(notes)
                if (pricePerL <= 0.0) append(" (imported from Fuelio - export carried no price)")
            }.trim()
            out += FuelLogCodec.FuelEntry(
                idMs = dateMs + row,
                dateUtc = isoUtc(dateMs),
                liters = liters,
                pricePerL = pricePerL,
                odometerKm = odoKm,
                station = station,
                grade = FuelLogCodec.GRADE_UNKNOWN,
                note = note,
                partial = (full != null && full == 0) || (missed != null && missed == 1)
            )
            row++
        }
        return Parsed(out.sortedBy { it.idMs }, row)
    }

    /** Same date+odometer+liters = same fill-up: re-imports never duplicate history. */
    fun dedupe(existing: List<FuelLogCodec.FuelEntry>, incoming: List<FuelLogCodec.FuelEntry>):
        List<FuelLogCodec.FuelEntry> {
        val keys = existing.map { key(it) }.toSet()
        return incoming.filter { key(it) !in keys }
    }

    private fun key(e: FuelLogCodec.FuelEntry): String =
        e.dateUtc.take(10) + "|" +
            (e.odometerKm?.let { String.format(Locale.US, "%.1f", it) } ?: "-") + "|" +
            String.format(Locale.US, "%.2f", e.liters)

    private fun unitOf(header: String): String =
        Regex("\\(([^)]+)\\)").find(header)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun vehicleDateFormat(lines: List<String>): String? {
        val mark = lines.indexOfFirst { it.startsWith("## Vehicle", ignoreCase = true) }
        if (mark < 0 || mark + 2 > lines.lastIndex) return null
        val valueRow = split(lines[mark + 2])
        return valueRow.getOrNull(5)?.takeIf { it.isNotBlank() && it.contains('y', true) }
    }

    private val FALLBACK_FORMATS = listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd-MM-yyyy", "dd/MM/yyyy", "MM/dd/yyyy")

    private fun parseDate(raw: String, preferred: String?): Long? {
        val value = raw.take(10)
        val formats = (preferred?.let { listOf(it) } ?: emptyList()) + FALLBACK_FORMATS
        for (f in formats) {
            val ms = runCatching {
                SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)?.time
            }.getOrNull()
            if (ms != null) return ms
        }
        return raw.toLongOrNull()?.let { it * 1000 }   // epoch seconds fallback
    }

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(millis))

    /** Quote-aware CSV split (Fuelio quotes fields containing commas). */
    internal fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(cur.toString().trim()); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }
}
