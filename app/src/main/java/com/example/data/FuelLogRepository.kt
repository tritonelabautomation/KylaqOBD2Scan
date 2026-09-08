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

    const val FORMAT_VERSION = 1

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
        val note: String
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
        esc(e.note)
    ).joinToString("|")

    fun decode(line: String): FuelEntry? {
        val p = line.split('|')
        if (p.size != 9 || p[0] != "f$FORMAT_VERSION") return null
        return try {
            FuelEntry(
                idMs = p[1].toLong(),
                dateUtc = p[2],
                liters = p[3].toDouble(),
                pricePerL = p[4].toDouble(),
                odometerKm = p[5].toDoubleOrNull(),
                station = unesc(p[6]),
                grade = p[7],
                note = unesc(p[8])
            )
        } catch (e: NumberFormatException) {
            null
        }
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
    val entryCount: Int = 0
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

        var kmSum = 0.0
        var literSum = 0.0
        for (i in 1 until list.size) {
            val prevOdo = list[i - 1].odometerKm
            val odo = list[i].odometerKm
            if (prevOdo != null && odo != null && odo > prevOdo && list[i].liters > 0.5) {
                kmSum += odo - prevOdo
                literSum += list[i].liters
            }
        }
        val avgKmPerL = if (literSum > 1.0) kmSum / literSum else null

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
            entryCount = list.size
        )
    }
}
