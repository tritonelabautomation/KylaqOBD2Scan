package com.example.analysis

/**
 * Usable tank volume for turning PID 012F percentages into litres.
 *
 * Factory spec is 50 L. Auto-cut brim fills (pump litres vs start% → cut%) measure the
 * real usable volume, which is what every trip %→L conversion must use. Owner fill
 * 2026-11-11: 40.27 L from 17.6% to 93.7% auto-cut ⇒ 52.9 L, not 50.
 */
object TankCapacity {
    const val NOMINAL_L = 50.0
    const val MIN_L = 30.0
    const val MAX_L = 80.0

    fun litresFromPercent(deltaPct: Double, capacityL: Double = NOMINAL_L): Double =
        kotlin.math.abs(deltaPct) / 100.0 * capacityL.coerceIn(MIN_L, MAX_L)

    /**
     * Implied usable capacity from one brim fill. Null when the rise is too small or the
     * result would be outside a sane tank.
     */
    fun impliedFromFill(pumpLitres: Double, startPct: Double, autoCutPct: Double): Double? {
        val risePct = autoCutPct - startPct
        if (pumpLitres <= 0.5 || risePct < 4.0) return null
        val implied = pumpLitres / (risePct / 100.0)
        return implied.takeIf { it in MIN_L..MAX_L }
    }

    fun sanitize(litres: Double): Double? =
        litres.takeIf { it in MIN_L..MAX_L }
}
