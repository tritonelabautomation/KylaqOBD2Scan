package com.example.data

import java.util.Locale

/**
 * Unit & currency formatting honouring the owner's Settings choice (metric/imperial, currency
 * symbol). Screens render money/distance/speed through this so one toggle restates the app.
 */
object Fmt {

    const val KM_PER_MILE = 1.609344

    fun money(value: Double?, symbol: String = "₹", decimals: Int = 0): String =
        value?.let { String.format(Locale.US, "%s%.${decimals}f", symbol, it) } ?: "--"

    fun distance(km: Double?, metric: Boolean = true, decimals: Int = 1): String =
        km?.let {
            if (metric) String.format(Locale.US, "%.${decimals}f km", it)
            else String.format(Locale.US, "%.${decimals}f mi", it / KM_PER_MILE)
        } ?: "--"

    fun speed(kmh: Double?, metric: Boolean = true): String =
        kmh?.let {
            if (metric) String.format(Locale.US, "%.0f km/h", it)
            else String.format(Locale.US, "%.0f mph", it / KM_PER_MILE)
        } ?: "--"

    fun efficiency(kmPerL: Double?, metric: Boolean = true): String =
        kmPerL?.let {
            if (metric) String.format(Locale.US, "%.1f km/L", it)
            else String.format(Locale.US, "%.1f mpg", it * 2.352145) // km/L -> US mpg
        } ?: "--"

    /**
     * Honest drift label (owner screenshot 2026-09-16: "0.0 → 26.2 (+0%)" - a division
     * by zero dressed up as "no change"). From zero to something is not 0 %: it is a
     * new signal; zero to zero is a flat ±0 %.
     */
    fun driftLabel(first: Double, last: Double): String = when {
        first == 0.0 && last == 0.0 -> "±0%"
        first == 0.0 -> "new"
        else -> {
            val d = (last - first) / kotlin.math.abs(first) * 100.0
            "${if (d >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.0f", d)}%"
        }
    }
}
