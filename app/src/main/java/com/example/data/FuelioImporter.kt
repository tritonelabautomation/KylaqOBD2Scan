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
        // Fuelio dialects: comma (classic), semicolon (EU, decimal commas),
        // TAB (the Google-Drive "vehicle-N-sync.csv" export the owner sent).
        val delim = detectDelim(lines.first { it.contains(',') || it.contains(';') || it.contains('	') })

        val dateFormat = vehicleDateFormat(lines)
        var headerIdx = -1
        val logMark = lines.indexOfFirst { it.startsWith("## Log", ignoreCase = true) }
        if (logMark >= 0 && logMark + 1 <= lines.lastIndex) {
            headerIdx = logMark + 1
        } else {
            // Flat-export fallback: first line that looks like a fill-up header.
            headerIdx = lines.indexOfFirst { l ->
                val c = split(l, delim).map { it.lowercase() }
                (c.any { it.startsWith("date") || it == "data" }) &&
                    (c.any { it.startsWith("odo") }) &&
                    (c.any { isFuelCol(it) })
            }
        }
        if (headerIdx < 0) return Parsed(emptyList(), 0)
        val header = split(lines[headerIdx], delim)
        val cols = header.map { it.lowercase() }
        val iDate = cols.indexOfFirst { it == "data" || it.startsWith("date") }
        val iOdo = cols.indexOfFirst { it.startsWith("odo") }
        val iFuel = cols.indexOfFirst { isFuelCol(it) }
        val iFull = cols.indexOfFirst { it == "full" || it.startsWith("fillup") || it == "type" }
        val iPrice = cols.indexOfFirst { it.startsWith("price") && !it.contains("total") }
        val iTotal = cols.indexOfFirst { it.contains("total") && (it.contains("price") || it.contains("cost")) }
        // Sync dialect: VolumePrice = price per fuel unit; Price = total cost.
        val iVolPrice = cols.indexOfFirst { it.startsWith("volumeprice") }
        val iCity = cols.indexOfFirst { it.startsWith("city") || it.startsWith("station") }
        val iNotes = cols.indexOfFirst { it.startsWith("notes") || it.startsWith("comment") }
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
            // Next section (FavStations / Pictures / Category ...) ends the log.
            if (line.startsWith("##")) break
            val c = split(line, delim)
            if (c.size <= iDate || c.size <= iFuel) continue
            val dateMs = parseDate(c[iDate], dateFormat) ?: continue
            val fuelUnits = num(c[iFuel], delim) ?: continue
            val liters = fuelUnits * unitToL
            if (liters <= 0.0) continue
            val odoKm = if (iOdo >= 0 && c.size > iOdo) num(c[iOdo], delim)?.let { it * odoToKm } else null
            val pricePerUnit = if (iPrice >= 0 && c.size > iPrice) num(c[iPrice], delim) else null
            val totalCost = if (iTotal >= 0 && c.size > iTotal) num(c[iTotal], delim) else null
            val volPrice = if (iVolPrice >= 0 && c.size > iVolPrice) num(c[iVolPrice], delim) else null
            val full = if (iFull >= 0 && c.size > iFull) fullFlag(c[iFull]) else null
            val missed = if (iMissed >= 0 && c.size > iMissed) c[iMissed].toIntOrNull() else null
            val station = if (iCity >= 0 && c.size > iCity) c[iCity].trim() else ""
            val notes = if (iNotes >= 0 && c.size > iNotes) c[iNotes].trim() else ""
            val pricePerL = when {
                (volPrice ?: 0.0) > 0.0 -> volPrice!! / unitToL
                (totalCost ?: 0.0) > 0.0 -> totalCost!! / liters
                (pricePerUnit ?: 0.0) > 0.0 -> pricePerUnit!! / unitToL
                else -> 0.0
            }
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
        val valueRow = split(lines[mark + 2], detectDelim(lines[mark + 2]))
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

    private fun isFuelCol(c: String): Boolean =
        c.startsWith("fuel") || c.startsWith("quantity") || c == "litres" || c == "liters"

    private fun detectDelim(line: String): Char {
        var best = ','
        var bestN = 0
        for (c in listOf(',', ';', '	')) {
            val n = line.count { it == c }
            if (n > bestN) { bestN = n; best = c }
        }
        return best
    }

    private fun num(raw: String, delim: Char): Double? {
        val v = raw.trim().replace(" ", "")
        return v.toDoubleOrNull() ?: if (delim == ';') v.replace(',', '.').toDoubleOrNull() else null
    }

    private fun fullFlag(raw: String): Int? =
        raw.trim().toIntOrNull() ?: when (raw.trim().lowercase()) {
            "full", "yes", "true" -> 1
            "partial", "part", "no", "false" -> 0
            else -> null
        }

    /** Quote-aware CSV split (Fuelio quotes fields containing the delimiter). */
    internal fun split(line: String, delim: Char = ','): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == delim && !inQuotes -> { out.add(cur.toString().trim()); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out.add(cur.toString().trim())
        return out
    }
}
