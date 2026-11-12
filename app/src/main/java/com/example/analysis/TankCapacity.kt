package com.example.analysis

/**
 * Usable tank volume for turning PID 012F percentages into litres, and the ONE place that
 * answers "how big is the tank right now?".
 *
 * Why this matters (owner 2026-11-12, trip screenshots): the tank %→litre number on a trip
 * was 1.76 L for a 3.53-point float drop on EVERY trip while a 40.3 pt fill window read
 * 34.25 L. Those two numbers cannot both come from one capacity: 3.53 pt ⇒ 1.76 L implies
 * ~49.9 L, but 40.3 pt ⇒ 34.25 L implies 45.0 L. The 45 L is the Kylaq's factory spec
 * (docs/kylaq-ideal-fuel-economy-e20-xp95.md) and is the boot value now; the previous
 * 50 L boot was the Kushaq figure, which made every trip's litres ~11 % too high.
 *
 * The calibrated (measured) usable volume still wins whenever an auto-cut brim fill has
 * been logged: owner fill 2026-11-11, 40.27 L from 17.6 % → 93.7 % ⇒ 52.9 L. That value
 * is stored per device and is what every trip %→L conversion must use afterwards.
 */
object TankCapacity {

    /** Kylaq 1.0 TSI factory tank (autocar spec). Boot value, not the Kushaq 50 L. */
    const val NOMINAL_L = 45.0

    const val MIN_L = 30.0
    const val MAX_L = 80.0

    /** A 012F rise smaller than this is slosh / sensor steps, never a fill window. */
    const val MIN_FILL_RISE_PCT = 4.0

    fun litresFromPercent(deltaPct: Double, capacityL: Double = NOMINAL_L): Double =
        kotlin.math.abs(deltaPct) / 100.0 * capacityL.coerceIn(MIN_L, MAX_L)

    /**
     * Implied usable capacity from one brim fill. Null when the rise is too small or the
     * result would be outside a sane tank.
     */
    fun impliedFromFill(pumpLitres: Double, startPct: Double, autoCutPct: Double): Double? {
        val risePct = autoCutPct - startPct
        if (pumpLitres <= 0.5 || risePct < MIN_FILL_RISE_PCT) return null
        val implied = pumpLitres / (risePct / 100.0)
        return implied.takeIf { it in MIN_L..MAX_L }
    }

    fun sanitize(litres: Double): Double? =
        litres.takeIf { it in MIN_L..MAX_L }

    /**
     * THE tank capacity to print on a trip: the calibrated value when one has been measured
     * on this account, otherwise the factory 45 L. A settings value equal to the factory
     * boot value is not a measurement, so it is not reported as one.
     */
    fun effective(storedL: Double?): Double {
        val stored = storedL?.let { sanitize(it) }
        return stored ?: NOMINAL_L
    }

    /** True only when [storedL] differs from the factory value, i.e. a fill measured it. */
    fun isCalibrated(storedL: Double?): Boolean {
        val stored = storedL?.let { sanitize(it) } ?: return false
        return kotlin.math.abs(stored - NOMINAL_L) > 0.05
    }
}
