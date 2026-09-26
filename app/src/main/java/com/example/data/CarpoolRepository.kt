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

    const val FORMAT_VERSION = 2

    /** Seats are capped at four including nobody else's car: the owner drives, riders pay. */
    const val MAX_RIDERS = 4

    data class Rider(
        val name: String,
        val amount: Double,
        /** Individual shared distance for this rider — null means "same as trip's shared distance" (old data). */
        val distanceKm: Double? = null
    ) {
        /** Effective distance for cost/km: rider's own if set, else the trip's shared distance (passed in). */
        fun effectiveDistance(fallback: Double): Double = distanceKm?.takeIf { it > 0.0 } ?: fallback
    }

    data class CarpoolEntry(
        val idMs: Long,
        /** Null for a standalone entry; normally the trip this ride was logged against. */
        val tripId: String?,
        /** IST stamp with offset, same convention as the fuel log's dateUtc. */
        val dateUtc: String,
        /** Total shared distance of the trip (or the old single value). Kept for backward compat and month roll-up. */
        val distanceKm: Double,
        val riders: List<Rider>
    ) {
        val earned: Double get() = riders.sumOf { it.amount }
    }

    /**
     * Cash-basis fuel for the month cards (owner 2026-09-20: "its not fetching the fuel to cost
     * fetch the price and show the effective cost"): the month's REFUEL spend from the fuel
     * ledger replaces the trip-derived pump cost, because that is the money that actually left
     * his wallet that month - his own "MONTH ₹" chip computes the same sum. A month with no
     * refuel keeps the trip-derived cost when rides linked to trips have one, otherwise zero
     * (cash truth, not a guess), and `effective` is recomputed as fuel - earned so the card can
     * always show net cost or saving.
     */
    fun withRefuelFuel(
        rows: List<MonthRow>,
        refuelSpendByMonth: Map<String, Double>
    ): List<MonthRow> = rows.map { r ->
        val fuel = refuelSpendByMonth[r.month] ?: r.fuelCost ?: 0.0
        r.copy(fuelCost = fuel, effective = fuel - r.earned)
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
        e.riders.joinToString(";") { r ->
            val base = esc(r.name) + "~" + num(r.amount)
            if (r.distanceKm != null) base + "~" + num(r.distanceKm) else base
        }
    ).joinToString("|")

    fun decode(line: String): CarpoolEntry? {
        val p = line.split('|')
        if (p.size != 6) return null
        val ver = p[0]
        if (ver != "c1" && ver != "c2" && ver != "c$FORMAT_VERSION") return null
        return try {
            CarpoolEntry(
                idMs = p[1].toLong(),
                tripId = p[4].takeIf { it != "-" },
                dateUtc = p[2],
                distanceKm = p[3].toDouble(),
                riders = p[5].split(';').filter { it.isNotBlank() }.mapNotNull { r ->
                    // Split into name~amount[~distance] — name is escaped, amount/distance are numeric
                    // Old format: esc(name)~amount (2 parts). New: esc(name)~amount~distance (3 parts)
                    // Escaped ~ is \w, so a raw split on ~ is safe for new data; for old data with esc, parts==2
                    val tildeParts = r.split('~')
                    if (tildeParts.size < 2) return@mapNotNull null
                    val amountIdx: Int
                    val distStr: String?
                    val nameEnd: Int
                    if (tildeParts.size >= 3 && tildeParts.last().toDoubleOrNull() != null && tildeParts[tildeParts.size - 2].toDoubleOrNull() != null) {
                        // 3-part: name~amount~distance
                        distStr = tildeParts.last()
                        amountIdx = tildeParts.size - 2
                        nameEnd = amountIdx
                    } else {
                        // 2-part: name~amount
                        distStr = null
                        amountIdx = tildeParts.size - 1
                        nameEnd = amountIdx
                    }
                    val nameEsc = tildeParts.subList(0, nameEnd).joinToString("~")
                    val amount = tildeParts[amountIdx].toDoubleOrNull() ?: return@mapNotNull null
                    val distKm = distStr?.toDoubleOrNull()
                    Rider(unesc(nameEsc), amount, distKm)
                }
            ).takeIf { it.riders.isNotEmpty() && it.riders.size <= MAX_RIDERS }
        } catch (e: NumberFormatException) {
            null
        }
    }

    data class TripWindow(val tripId: String, val startMs: Long, val endMs: Long?)

    /**
     * The trip whose recorded window covers an instant - how a ride logged by date and time
     * joins its drive EVEN WHEN the trip materialises later: abruptly-stopped sessions are
     * recovered after the fact, and the owner's passengers were aboard whether or not the
     * recorder survived. Latest-starting covering window wins; a null end (still recording)
     * covers everything after its start. Pure, so the rule is unit-testable.
     */
    fun tripLinkFor(ms: Long, windows: List<TripWindow>): String? =
        windows.filter { it.startMs <= ms && (it.endMs == null || it.endMs >= ms) }
            .maxByOrNull { it.startMs }?.tripId

    /** The entry's own date and time back in millis from its IST stamp; null on garbage. */
    fun whenMs(e: CarpoolEntry): Long? = RecordTime.parseStamp(e.dateUtc)

    /**
     * Monthly roll-up, pure: the caller supplies each entry's pump cost (trip fuel litres x
     * latest logged price), because that needs the sample store. Months are IST keys, so a
     * ride logged at 00:20 IST on the 1st belongs to the new month, not the UTC previous one.
     */
    fun monthly(entries: List<CarpoolEntry>, fuelCost: (CarpoolEntry) -> Double?): List<MonthRow> =
        // BUGFIX 2026-09-20 (owner, screenshot: "Another BUG for you august"): this keyed months
        // by idMs - the SAVE instant - while the ride cards, the screen's month grouping and the
        // trip linking all use the ride's own IST instant (dateUtc, idMs only as fallback). A
        // back-dated ride (saved in September for an 18-Aug drive) therefore landed in the
        // September row, and the August header found no row at all and fell back to
        // "1 ride(s) - 0 km - earned 0 - fuel --" right above a 30 km / 235 ride. One key rule
        // everywhere: the ride's instant.
        entries.groupBy { RecordTime.format("yyyy-MM", whenMs(it) ?: it.idMs) }
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
