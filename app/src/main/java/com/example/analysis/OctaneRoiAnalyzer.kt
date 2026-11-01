package com.example.analysis

/** Distance/fuel weighted comparison of comparable, user-labelled fuel batches.
 * E20/XP95 are log labels, not inferred ethanol contents. Timing/trim differences
 * are descriptive evidence only: route, AC, load and traffic can confound any ROI.
 */
class OctaneRoiAnalyzer {
    enum class Fuel { E20, XP95 }
    data class FuelLog(
        val fuel: Fuel, val distanceKm: Double, val litres: Double, val priceRupeesPerLitre: Double,
        val timingAdvanceDegrees: Double? = null, val fuelTrimPercent: Double? = null
    )
    data class Summary(
        val kmPerLitre: Double, val rupeesPerKm: Double,
        val meanTimingAdvanceDegrees: Double?, val meanFuelTrimPercent: Double?, val logCount: Int
    )
    data class Result(
        val regular: Summary?, val premium: Summary?,
        /** Positive means XP95 is cheaper per km. Null means insufficient evidence. */
        val premiumSavingsRupeesPerKm: Double?, val premiumBreakEvenPricePerLitre: Double?
    )

    fun analyze(logs: List<FuelLog>): Result {
        val valid = logs.filter { log ->
            log.distanceKm.isFinite() && log.distanceKm > 0.0 && log.litres.isFinite() && log.litres > 0.0 &&
                log.priceRupeesPerLitre.isFinite() && log.priceRupeesPerLitre > 0.0
        }
        fun summary(fuel: Fuel): Summary? {
            val group = valid.filter { it.fuel == fuel }
            if (group.isEmpty()) return null
            val distance = group.sumOf { it.distanceKm }
            val litres = group.sumOf { it.litres }
            fun weighted(value: (FuelLog) -> Double?): Double? {
                val measured = group.filter { value(it)?.isFinite() == true }
                return if (measured.isEmpty()) null else
                    measured.sumOf { value(it)!! * it.distanceKm } / measured.sumOf { it.distanceKm }
            }
            return Summary(distance / litres, group.sumOf { it.litres * it.priceRupeesPerLitre } / distance,
                weighted { it.timingAdvanceDegrees }, weighted { it.fuelTrimPercent }, group.size)
        }
        val regular = summary(Fuel.E20)
        val premium = summary(Fuel.XP95)
        return Result(regular, premium,
            if (regular != null && premium != null) regular.rupeesPerKm - premium.rupeesPerKm else null,
            if (regular != null && premium != null) regular.rupeesPerKm * premium.kmPerLitre else null)
    }
}
