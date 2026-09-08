package com.example.data

/**
 * Unit & currency formatting honouring the owner's Settings choice (metric/imperial, currency
 * symbol). Screens render money/distance/speed through this so one toggle restates the app.
 */
object Fmt {

    const val KM_PER_MILE = 1.609344

    fun money(value: Double?, symbol: String = "₹", decimals: Int = 0): String =
        value?.let { String.format("%s%.${decimals}f", symbol, it) } ?: "--"

    fun distance(km: Double?, metric: Boolean = true, decimals: Int = 1): String =
        km?.let {
            if (metric) String.format("%.${decimals}f km", it)
            else String.format("%.${decimals}f mi", it / KM_PER_MILE)
        } ?: "--"

    fun speed(kmh: Double?, metric: Boolean = true): String =
        kmh?.let {
            if (metric) String.format("%.0f km/h", it)
            else String.format("%.0f mph", it / KM_PER_MILE)
        } ?: "--"

    fun efficiency(kmPerL: Double?, metric: Boolean = true): String =
        kmPerL?.let {
            if (metric) String.format("%.1f km/L", it)
            else String.format("%.1f mpg", it * 2.352145) // km/L -> US mpg
        } ?: "--"
}
