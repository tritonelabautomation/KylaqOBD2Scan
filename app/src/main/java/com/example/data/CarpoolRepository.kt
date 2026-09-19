package com.example.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Car-pool ledger (owner 2026-09-19: "i'm started car pooling to office ... for each trip give
 * an option to add car pooling details like distance and amount ... max 4 people ... show
 * monthly earnings or car pool & effective cost for me").
 *
 * Same shape as the fuel journal on purpose: prefs-backed line codec, pure and JVM-testable,
 * changeTick so derived UI recomputes. One entry per trip: the shared distance and up to four
 * riders with what each paid. Effective cost of a trip = its pump fuel cost minus what the
 * riders paid; monthly rows roll that up per IST calendar month (owner mandate: IST only).
 */
object CarpoolCodec {

    const val FORMAT_VERSION = 1

    /** Seats are capped at four including nobody else's car: the owner drives, riders pay. */
    const val MAX_RIDERS = 4

    data class Rider(val name: String, val amount: Double)

    data class CarpoolEntry(
        val idMs: Long,
        /** Null for a standalone entry; normally the trip this ride was logged against. */
        val tripId: String?,
        /** IST stamp with offset, same convention as the fuel log's dateUtc. */
        val dateUtc: String,
        val distanceKm: Double,
        val riders: List<Rider>
    ) {
        val earned: Double get() = riders.sumOf { it.amount }
    }

    data class MonthRow(
        val month: String,
        val trips: Int,
        val distanceKm: Double,
        val earned: Double,
        /** Pump cost of the pooled trips; null when a trip has no integratable fuel rows. */
        val fuelCost: Double?,
        /** fuelCost - earned: positive = still out of pocket, negative = the ride paid for itself. */
        val effective: Double?
    )

    fun encode(e: CarpoolEntry): String = listOf(
        "c$FORMAT_VERSION",
        e.idMs.toString(),
        e.dateUtc,
        num(e.distanceKm),
        e.tripId ?: "-",
        e.riders.joinToString(";") { esc(it.name) + "~" + num(it.amount) }
    ).joinToString("|")

    fun decode(line: String): CarpoolEntry? {
        val p = line.split('|')
        if (p[0] != "c$FORMAT_VERSION" || p.size != 6) return null
        return try {
            CarpoolEntry(
                idMs = p[1].toLong(),
                tripId = p[4].takeIf { it != "-" },
                dateUtc = p[2],
                distanceKm = p[3].toDouble(),
                riders = p[5].split(';').filter { it.isNotBlank() }.map { r ->
                    val cut = r.lastIndexOf('~')
                    if (cut < 0) return null
                    Rider(unesc(r.substring(0, cut)), r.substring(cut + 1).toDouble())
                }
            ).takeIf { it.riders.isNotEmpty() && it.riders.size <= MAX_RIDERS }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Monthly roll-up, pure: the caller supplies each entry's pump cost (trip fuel litres x
     * latest logged price), because that needs the sample store. Months are IST keys, so a
     * ride logged at 00:20 IST on the 1st belongs to the new month, not the UTC previous one.
     */
    fun monthly(entries: List<CarpoolEntry>, fuelCost: (CarpoolEntry) -> Double?): List<MonthRow> =
        entries.groupBy { RecordTime.format("yyyy-MM", it.idMs) }
            .map { (month, es) ->
                val costs = es.map { fuelCost(it) }
                val fc = if (costs.all { it != null }) costs.sumOf { it!! } else null
                val earned = es.sumOf { it.earned }
                MonthRow(
                    month = month,
                    trips = es.size,
                    distanceKm = es.sumOf { it.distanceKm },
                    earned = earned,
                    fuelCost = fc,
                    effective = fc?.let { it - earned }
                )
            }
            .sortedBy { it.month }

    private fun num(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
            .replace(";", "\\s").replace("~", "\\w")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(
                    when (s[i + 1]) {
                        'p' -> '|'; 'n' -> '\n'; 's' -> ';'; 'w' -> '~'; else -> s[i + 1]
                    }
                )
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

class CarpoolRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("carpool_prefs", Context.MODE_PRIVATE)

    private val _changeTick = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Bumped on every mutation so trip cards and monthly rows recompute, fuel-journal style. */
    val changeTick: kotlinx.coroutines.flow.StateFlow<Long> = _changeTick

    fun entries(): List<CarpoolCodec.CarpoolEntry> =
        prefs.getString("carpool_log", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { CarpoolCodec.decode(it) }
            ?: emptyList()

    fun forTrip(tripId: String): List<CarpoolCodec.CarpoolEntry> =
        entries().filter { it.tripId == tripId }

    /** Upsert by id: one car-pool story per trip, edited in place, riders capped at four. */
    fun save(e: CarpoolCodec.CarpoolEntry) {
        require(e.riders.size in 1..CarpoolCodec.MAX_RIDERS) { "a ride carries 1..4 riders" }
        write(entries().filter { it.idMs != e.idMs } + e)
    }

    fun delete(idMs: Long) = write(entries().filter { it.idMs != idMs })

    private fun write(list: List<CarpoolCodec.CarpoolEntry>) {
        prefs.edit().putString("carpool_log", list.joinToString("\n") { CarpoolCodec.encode(it) }).apply()
        _changeTick.value = _changeTick.value + 1
    }
}
