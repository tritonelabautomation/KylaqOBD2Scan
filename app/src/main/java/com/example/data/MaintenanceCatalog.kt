package com.example.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Škoda Kylaq 1.0 TSI maintenance catalogue + due-state engine (VehIQ-style health tracking,
 * offline and free of any account).
 *
 * Intervals follow the Indian-market EA211 service plan: oil service every 15 000 km or 1 year,
 * air/cabin filters and plugs on their own cycles, brake fluid time-based. `intervalDays == 0`
 * means the item is distance-only.
 */
object MaintenanceCatalog {

    data class ServiceItem(
        val id: String,
        val label: String,
        val intervalKm: Double,
        val intervalDays: Int,
        val category: String
    )

    val KYLAQ_ITEMS: List<ServiceItem> = listOf(
        ServiceItem("engine_oil", "Engine oil (5W-30, VW 504/507)", 15_000.0, 365, "Engine"),
        ServiceItem("oil_filter", "Oil filter", 15_000.0, 365, "Engine"),
        ServiceItem("air_filter", "Air filter (engine)", 30_000.0, 730, "Engine"),
        ServiceItem("cabin_filter", "Cabin / pollen filter", 30_000.0, 365, "Cabin"),
        ServiceItem("spark_plugs", "Spark plugs", 60_000.0, 1460, "Engine"),
        ServiceItem("brake_fluid", "Brake fluid", 45_000.0, 730, "Brakes"),
        ServiceItem("front_pads", "Front brake pads", 40_000.0, 0, "Brakes"),
        ServiceItem("rear_pads", "Rear brake pads", 60_000.0, 0, "Brakes"),
        ServiceItem("coolant", "Coolant (G12evo) check", 30_000.0, 730, "Engine"),
        ServiceItem("tyres_rotate", "Tyre rotation", 10_000.0, 180, "Tyres")
    )

    data class ServiceLog(
        val itemId: String,
        val dateUtc: String,
        val dateMs: Long,
        val odometerKm: Double?,
        val cost: Double?
    )

    enum class DueStatus { UNKNOWN, GOOD, DUE_SOON, OVERDUE }

    data class DueState(
        val item: ServiceItem,
        val last: ServiceLog?,
        val kmRemaining: Double?,
        val daysRemaining: Int?,
        val status: DueStatus
    ) {
        val headline: String
            get() = when (status) {
                DueStatus.UNKNOWN -> "Not logged yet"
                DueStatus.OVERDUE -> "Overdue"
                DueStatus.DUE_SOON -> "Due soon"
                DueStatus.GOOD -> buildString {
                    kmRemaining?.let { append(String.format("%.0f km", it)) }
                    daysRemaining?.let {
                        if (isNotEmpty()) append(" / ")
                        append("$it d")
                    }
                    if (isEmpty()) append("OK")
                    append(" left")
                }
            }
    }

    private const val DUE_SOON_KM = 1500.0
    private const val DUE_SOON_DAYS = 30
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    /** Pure due-state evaluation — unit tested without Android. */
    fun evaluate(
        item: ServiceItem,
        last: ServiceLog?,
        currentOdometerKm: Double?,
        nowMs: Long
    ): DueState {
        if (last == null) return DueState(item, null, null, null, DueStatus.UNKNOWN)

        val kmRemaining = if (currentOdometerKm != null && last.odometerKm != null) {
            last.odometerKm + item.intervalKm - currentOdometerKm
        } else {
            null
        }
        val daysRemaining = if (item.intervalDays > 0) {
            ((last.dateMs + item.intervalDays * MS_PER_DAY - nowMs) / MS_PER_DAY).toInt()
        } else {
            null
        }

        val kmOver = kmRemaining != null && kmRemaining <= 0
        val daysOver = daysRemaining != null && daysRemaining <= 0
        val kmSoon = kmRemaining != null && kmRemaining <= DUE_SOON_KM
        val daysSoon = daysRemaining != null && daysRemaining <= DUE_SOON_DAYS

        val status = when {
            kmOver || daysOver -> DueStatus.OVERDUE
            kmSoon || daysSoon -> DueStatus.DUE_SOON
            else -> DueStatus.GOOD
        }
        return DueState(item, last, kmRemaining, daysRemaining, status)
    }
}

/** Durable service history (one line per logged service). */
class MaintenanceRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("maintenance_prefs", Context.MODE_PRIVATE)

    fun logs(): List<MaintenanceCatalog.ServiceLog> =
        prefs.getString("service_log", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { decode(it) }
            ?: emptyList()

    fun lastPerItem(): Map<String, MaintenanceCatalog.ServiceLog> =
        logs().sortedBy { it.dateMs }.associateBy { it.itemId }

    fun log(entry: MaintenanceCatalog.ServiceLog) {
        val lines = logs().map { encode(it) }.toMutableList()
        lines.add(0, encode(entry))
        while (lines.size > 500) lines.removeAt(lines.size - 1)
        prefs.edit().putString("service_log", lines.joinToString("\n")).apply()
    }

    fun currentOdometerKm(): Double? =
        prefs.getString("odometer_km", null)?.toDoubleOrNull()

    fun setCurrentOdometerKm(km: Double) =
        prefs.edit().putString("odometer_km", String.format(java.util.Locale.US, "%.1f", km)).apply()

    fun dueStates(nowMs: Long = System.currentTimeMillis()): List<MaintenanceCatalog.DueState> {
        val lastMap = lastPerItem()
        val odo = currentOdometerKm()
        return MaintenanceCatalog.KYLAQ_ITEMS.map { item ->
            MaintenanceCatalog.evaluate(item, lastMap[item.id], odo, nowMs)
        }
    }

    private fun encode(l: MaintenanceCatalog.ServiceLog): String = listOf(
        "s1", l.itemId, l.dateUtc, l.dateMs.toString(),
        l.odometerKm?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-",
        l.cost?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-"
    ).joinToString("|")

    private fun decode(line: String): MaintenanceCatalog.ServiceLog? {
        val p = line.split('|')
        if (p.size != 6 || p[0] != "s1") return null
        return try {
            MaintenanceCatalog.ServiceLog(
                itemId = p[1],
                dateUtc = p[2],
                dateMs = p[3].toLong(),
                odometerKm = p[4].toDoubleOrNull(),
                cost = p[5].toDoubleOrNull()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
