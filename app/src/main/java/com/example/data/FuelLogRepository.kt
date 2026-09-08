package com.example.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable fuel (fill-up) log — the Fuelio/VehIQ-style mileage & cost journal.
 *
 * Manual fill-ups complement the OBD-integrated log fuel: they carry price, station and the
 * fuel grade (X95 vs regular), which is ground truth for the X95 comparison and the source of
 * cost/km and 30-day cost. The codec is dependency-free and pure so it is JVM-testable.
 */
object FuelLogCodec {

    const val FORMAT_VERSION = 2

    const val GRADE_X95 = "X95"
    const val GRADE_REGULAR = "REGULAR"
    const val GRADE_UNKNOWN = "UNKNOWN"

    data class FuelEntry(
        val idMs: Long,
        val dateUtc: String,
        val liters: Double,
        val pricePerL: Double,
        val odometerKm: Double?,
        val station: String,
        val grade: String,
        val note: String,
        /** Fuelio parity: partial tank / missed previous fill-up - excluded from km/L anchoring. */
        val partial: Boolean = false
    ) {
        val totalCost: Double get() = liters * pricePerL

        val display: String
            get() = buildString {
                append(dateUtc.take(10))
                append(" · ").append(String.format("%.2f L", liters))
                append(" · ₹").append(String.format("%.0f", totalCost))
                append(" · ").append(grade)
                if (station.isNotBlank()) append(" · ").append(station)
            }
    }

    fun encode(e: FuelEntry): String = listOf(
        "f$FORMAT_VERSION",
        e.idMs.toString(),
        e.dateUtc,
        num(e.liters),
        num(e.pricePerL),
        e.odometerKm?.let { num(it) } ?: "-",
        esc(e.station),
        e.grade,
        esc(e.note),
        if (e.partial) "1" else "0"
    ).joinToString("|")

    fun decode(line: String): FuelEntry? {
        val p = line.split('|')
        // v1 lines (9 fields) stay readable: partial defaults to false.
        val partial = when {
            p[0] == "f2" && p.size == 10 -> p[9] == "1"
            p[0] == "f1" && p.size == 9 -> false
            else -> return null
        }
        if (p[0] != "f1" && p[0] != "f2") return null
        return try {
            FuelEntry(
                idMs = p[1].toLong(),
                dateUtc = p[2],
                liters = p[3].toDouble(),
                pricePerL = p[4].toDouble(),
                odometerKm = p[5].toDoubleOrNull(),
                station = unesc(p[6]),
                grade = p[7],
                note = unesc(p[8]),
                partial = partial
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Fuelio-semantics interval walker: an interval closes at each FULL fill-up with a
     * higher odometer; partial fill-ups contribute litres to the next closing interval.
     * Pure function so unit tests need no Android context.
     */
    fun intervals(list: List<FuelEntry>): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        var anchorOdo: Double? = null
        var litersSinceAnchor = 0.0
        for (e in list.sortedBy { it.idMs }) {
            val odo = e.odometerKm ?: continue
            if (e.liters <= 0.5) continue
            if (e.partial) {
                litersSinceAnchor += e.liters
                continue
            }
            val a = anchorOdo
            if (a != null && odo > a) {
                val fuel = litersSinceAnchor + e.liters
                if (fuel > 0.5) out.add(e.idMs to (odo - a) / fuel)
            }
            litersSinceAnchor = 0.0
            anchorOdo = odo
        }
        return out
    }

    /** Per-station price statistics from the owner's own log (pure). */
    fun stationStats(list: List<FuelEntry>): List<StationStat> = list
        .filter { it.station.isNotBlank() && it.pricePerL > 0 }
        .groupBy { it.station.trim() }
        .map { (name, es) ->
            val sorted = es.sortedBy { it.idMs }
            StationStat(
                name = name,
                visits = sorted.size,
                avgPrice = sorted.map { it.pricePerL }.average(),
                lastPrice = sorted.last().pricePerL,
                bestPrice = sorted.minOf { it.pricePerL }
            )
        }
        .sortedByDescending { it.visits }

    /** Calendar-month spend in local time (pure). */
    fun costThisMonth(list: List<FuelEntry>, nowMs: Long): Double? {
        val calNow = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val sum = list.filter {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = it.idMs }
            cal.get(java.util.Calendar.YEAR) == calNow.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.MONTH) == calNow.get(java.util.Calendar.MONTH)
        }.sumOf { it.totalCost }
        return sum.takeIf { list.isNotEmpty() }
    }

    // ---- Fuelio-parity selective CSV export/import (merge-safe) ----
    private const val CSV_HEADER = "idMs,dateUtc,liters,pricePerL,odometerKm,station,grade,note,partial"

    fun toCsv(entries: List<FuelEntry>): String = buildString {
        appendLine(CSV_HEADER)
        entries.forEach { e ->
            appendLine(
                listOf(
                    e.idMs.toString(), csv(e.dateUtc), csv(num(e.liters)), csv(num(e.pricePerL)),
                    e.odometerKm?.let { num(it) } ?: "", csv(e.station.replace("\n", " ")),
                    csv(e.grade), csv(e.note.replace("\n", "\\n")),
                    if (e.partial) "1" else "0"
                ).joinToString(",")
            )
        }
    }

    fun fromCsv(text: String): List<FuelEntry> = text
        .lineSequence()
        .filter { it.isNotBlank() }
        .drop(1)
        .mapNotNull { line ->
            val c = splitCsvLine(line)
            if (c.size < 9) null else try {
                FuelEntry(
                    idMs = c[0].toLong(),
                    dateUtc = c[1],
                    liters = c[2].toDouble(),
                    pricePerL = c[3].toDouble(),
                    odometerKm = c[4].toDoubleOrNull(),
                    station = c[5],
                    grade = c[6],
                    note = c[7].replace("\\n", "\n"),
                    partial = c[8] == "1"
                )
            } catch (e: NumberFormatException) { null }
        }
        .toList()

    /** Existing entries win on id collisions; newcomers are appended. */
    fun merge(existing: List<FuelEntry>, incoming: List<FuelEntry>): List<FuelEntry> {
        val ids = existing.map { it.idMs }.toSet()
        return (existing + incoming.filter { it.idMs !in ids }).sortedByDescending { it.idMs }
    }

    private fun csv(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('
')) """ + v.replace(""", """") + """ else v

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    private fun num(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

    private fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'p' -> append('|')
                    'n' -> append('\n')
                    '\\' -> append('\\')
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }
}

/** Aggregate cost/efficiency figures derived from the fill-up log. */
data class FuelStats(
    val avgKmPerL: Double? = null,
    val costPerKm: Double? = null,
    val cost30d: Double? = null,
    val liters30d: Double? = null,
    val lastPricePerL: Double? = null,
    val entryCount: Int = 0,
    /** Fuelio semantics: km/L of the most recent closed full-tank interval. */
    val lastKmPerL: Double? = null,
    /** Calendar-month spend (local time). */
    val costThisMonth: Double? = null,
    /** entry idMs -> closed-interval km/L, for per-row rendering. */
    val intervals: List<Pair<Long, Double>> = emptyList()
)

/** Per-station price intelligence derived ONLY from the owner's own fill-up log. */
data class StationStat(
    val name: String,
    val visits: Int,
    val avgPrice: Double,
    val lastPrice: Double,
    val bestPrice: Double
)

class FuelLogRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fuel_log_prefs", Context.MODE_PRIVATE)

    fun entries(): List<FuelLogCodec.FuelEntry> =
        prefs.getString("fuel_log", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { FuelLogCodec.decode(it) }
            ?: emptyList()

    fun add(entry: FuelLogCodec.FuelEntry) {
        val lines = entries().map { FuelLogCodec.encode(it) }.toMutableList()
        lines.add(0, FuelLogCodec.encode(entry))
        while (lines.size > 500) lines.removeAt(lines.size - 1)
        prefs.edit().putString("fuel_log", lines.joinToString("\n")).apply()
    }

    fun delete(idMs: Long) {
        val remaining = entries().filterNot { it.idMs == idMs }
        prefs.edit().putString("fuel_log", remaining.joinToString("\n") { FuelLogCodec.encode(it) }).apply()
    }

    /**
     * Efficiency and cost statistics. km/L comes from odometer deltas between consecutive
     * fill-ups (the same maths Fuelio uses); cost figures cover a rolling 30-day window.
     */
    fun stats(nowMs: Long = System.currentTimeMillis()): FuelStats {
        val list = entries().sortedBy { it.idMs }
        if (list.isEmpty()) return FuelStats()

        // Fuelio semantics delegated to the pure codec walker (unit-testable).
        val intervals = FuelLogCodec.intervals(list)
        var kmSum = 0.0
        var literSum = 0.0
        for ((idMs, kmL) in intervals) {
            val e = list.first { it.idMs == idMs }
            val idx = list.indexOf(e)
            val prevFull = list.take(idx).lastOrNull { !it.partial && it.odometerKm != null }
            val a = prevFull?.odometerKm
            val odo = e.odometerKm
            if (a != null && odo != null) {
                kmSum += odo - a
                literSum += (odo - a) / kmL
            }
        }
        val lastKmPerL = intervals.lastOrNull()?.second
        val avgKmPerL = if (literSum > 1.0) kmSum / literSum else null
        val costThisMonth = FuelLogCodec.costThisMonth(list, nowMs)

        val cutoff = nowMs - 30L * 24 * 60 * 60 * 1000
        val recent = list.filter { it.idMs >= cutoff }
        val cost30d = recent.sumOf { it.totalCost }.takeIf { recent.isNotEmpty() }
        val liters30d = recent.sumOf { it.liters }.takeIf { recent.isNotEmpty() }

        val odoRecent = recent.mapNotNull { it.odometerKm }
        val km30d = if (odoRecent.size >= 2) (odoRecent.last() - odoRecent.first()).takeIf { it > 0 } else null
        val costPerKm = when {
            km30d != null && cost30d != null -> cost30d / km30d
            avgKmPerL != null && list.last().pricePerL > 0 -> list.last().pricePerL / avgKmPerL
            else -> null
        }

        return FuelStats(
            avgKmPerL = avgKmPerL,
            costPerKm = costPerKm,
            cost30d = cost30d,
            liters30d = liters30d,
            lastPricePerL = list.last().pricePerL.takeIf { it > 0 },
            entryCount = list.size,
            lastKmPerL = lastKmPerL,
            costThisMonth = costThisMonth,
            intervals = intervals
        )
    }

    /** Per-station price statistics from the owner's own log (no external data). */
    fun stationStats(): List<StationStat> = FuelLogCodec.stationStats(entries())

    /** Bulk replace (used by CSV merge import). */
    fun replaceAll(entries: List<FuelLogCodec.FuelEntry>) {
        prefs.edit().putString("fuel_log", entries.take(500).joinToString("\n") { FuelLogCodec.encode(it) }).apply()
    }
}
