package com.example.data

import android.content.Context
import android.content.SharedPreferences

/**
 * VehIQ "Planned trips" without the paywall: quick/road trip plans with distance, date, budget
 * and notes, persisted like the other fleet stores (one encoded line per plan).
 */
object TripPlanCodec {

    private const val FORMAT_VERSION = 1

    data class TripPlan(
        val idMs: Long,
        val name: String,
        val from: String,
        val to: String,
        val distanceKm: Double,
        val dateMs: Long,
        val budget: Double?,
        val note: String,
        val completed: Boolean
    )

    fun encode(t: TripPlan): String = listOf(
        "t$FORMAT_VERSION", t.idMs.toString(),
        ExpenseCodec.esc(t.name), ExpenseCodec.esc(t.from), ExpenseCodec.esc(t.to),
        String.format(java.util.Locale.US, "%.1f", t.distanceKm), t.dateMs.toString(),
        t.budget?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-",
        ExpenseCodec.esc(t.note),
        if (t.completed) "1" else "0"
    ).joinToString("|")

    fun decode(line: String): TripPlan? {
        val p = line.split('|')
        if (p.size != 10 || p[0] != "t$FORMAT_VERSION") return null
        return try {
            TripPlan(
                idMs = p[1].toLong(),
                name = ExpenseCodec.unesc(p[2]),
                from = ExpenseCodec.unesc(p[3]),
                to = ExpenseCodec.unesc(p[4]),
                distanceKm = p[5].toDouble(),
                dateMs = p[6].toLong(),
                budget = p[7].toDoubleOrNull(),
                note = ExpenseCodec.unesc(p[8]),
                completed = p[9] == "1"
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}

class TripPlanRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("trip_plan_prefs", Context.MODE_PRIVATE)

    fun plans(): List<TripPlanCodec.TripPlan> =
        prefs.getString("trip_plans", null)?.split('\n')?.filter { it.isNotBlank() }
            ?.mapNotNull { TripPlanCodec.decode(it) } ?: emptyList()

    fun add(plan: TripPlanCodec.TripPlan) {
        val lines = plans().map { TripPlanCodec.encode(it) }.toMutableList()
        lines.add(0, TripPlanCodec.encode(plan))
        while (lines.size > 200) lines.removeAt(lines.size - 1)
        prefs.edit().putString("trip_plans", lines.joinToString("\n")).apply()
    }

    fun update(plan: TripPlanCodec.TripPlan) {
        val lines = plans().map { if (it.idMs == plan.idMs) TripPlanCodec.encode(plan) else TripPlanCodec.encode(it) }
        prefs.edit().putString("trip_plans", lines.joinToString("\n")).apply()
    }

    fun remove(idMs: Long) {
        val lines = plans().filter { it.idMs != idMs }.map { TripPlanCodec.encode(it) }
        prefs.edit().putString("trip_plans", lines.joinToString("\n")).apply()
    }

    fun activePlan(): TripPlanCodec.TripPlan? = plans().firstOrNull { !it.completed }
}
