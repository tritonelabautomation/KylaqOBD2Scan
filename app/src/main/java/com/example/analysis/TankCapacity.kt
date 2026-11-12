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

    /**
     * The old boot value (the Kushaq number). Present in prefs on devices that have not
     * logged a fill since 2026-11-12, so it must not be mistaken for a measured tank.
     */
    const val LEGACY_NOMINAL_L = 50.0

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
     * THE tank capacity to print on a trip: the calibrated usable volume once a brim fill has
     * measured one, otherwise the factory spec.
     *
     * A value equal to [LEGACY_NOMINAL_L] (50.0) is the old boot default, left in prefs on
     * every device that has not logged a fill since the spec was corrected — it is a leftover,
     * not a measurement, so it reads as the 45 L spec. Anything else (the owner's typed value
     * or a fill-derived one) is honoured as-is.
     */
    fun effective(storedL: Double?): Double {
        val stored = storedL?.let { sanitize(it) } ?: return NOMINAL_L
        return if (kotlin.math.abs(stored - LEGACY_NOMINAL_L) < 0.05) NOMINAL_L else stored
    }

    /**
     * Capacity is "measured" when it differs from the factory 45 L spec AND is not the legacy
     * 50 L boot value — the two numbers this app ever wrote without a fill behind them.
     */
    fun isCalibrated(storedL: Double?): Boolean {
        val stored = storedL?.let { sanitize(it) } ?: return false
        if (kotlin.math.abs(stored - LEGACY_NOMINAL_L) < 0.05) return false
        return kotlin.math.abs(stored - NOMINAL_L) > 0.05
    }

}
