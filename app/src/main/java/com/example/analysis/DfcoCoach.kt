package com.example.analysis

/** PID 019D must be decoded as mass fuel rate in g/s, not L/h.
 * Savings compare against a configured idle baseline; this is an estimate, not a
 * claim that neutral coasting is safe or that fuel was actually purchased/saved.
 */
class DfcoCoach(
    private val priceRupeesPerLitre: Double,
    private val idleFuelLitresPerHour: Double = 0.7
) {
    init {
        require(priceRupeesPerLitre.isFinite() && priceRupeesPerLitre > 0.0)
        require(idleFuelLitresPerHour.isFinite() && idleFuelLitresPerHour >= 0.0)
    }
    data class Result(
        val active: Boolean, val glideSeconds: Double, val estimatedSavedLitres: Double,
        val estimatedSavedRupees: Double, val rupeesPerMinute: Double
    )
    private var lastTime: Long? = null
    private var wasActive = false
    private var seconds = 0.0

    fun update(timeMs: Long, massFuelGramsPerSecond: Double?, rpm: Double?, throttlePercent: Double?, inGear: Boolean): Result {
        if (timeMs < 0 || lastTime?.let { timeMs <= it } == true) return result(wasActive)
        val active = inGear && massFuelGramsPerSecond == 0.0 &&
            rpm != null && rpm.isFinite() && rpm > 1200.0 && rpm <= 8000.0 && throttlePercent == 0.0
        val dt = lastTime?.let { timeMs - it } ?: 0L
        if (active && wasActive && dt in 1..5000) seconds += dt / 1000.0
        lastTime = timeMs
        wasActive = active
        return result(active)
    }

    private fun result(active: Boolean): Result {
        val litres = seconds / 3600.0 * idleFuelLitresPerHour
        return Result(active, seconds, litres, litres * priceRupeesPerLitre,
            if (active) idleFuelLitresPerHour * priceRupeesPerLitre / 60.0 else 0.0)
    }
    fun reset() { lastTime = null; wasActive = false; seconds = 0.0 }
}
