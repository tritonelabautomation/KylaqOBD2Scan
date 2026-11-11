package com.example.analysis

import java.util.Locale

/**
 * Split a trip that contains a fill into (1) PID tank window, (2) pump fill, (3) net burn.
 *
 * Owner 2026-11-11 recovered run 17c54147: 22.0% → 93.7% is the fill sitting in the
 * trip, not 37.98 L burned. OBD 3.74 L and MID 3.81 L are consumption. Float burn is
 * only the drop before the pump plus the drop after auto-cut.
 */
object MidTripFuel {

    data class Split(
        val tripStartPct: Double?,
        val tripEndPct: Double?,
        val fillBeforePct: Double?,
        val fillAfterPct: Double?,
        val pumpLitres: Double?,
        /** abs(trip end − trip start) × tank — what 012F saw on the trip, fill or drop. */
        val pidTripWindowLitres: Double?,
        /** (after% − before%) × tank — PID litres of the fill window. */
        val pidFillLitres: Double?,
        /** pump − PID fill. Positive = pump dispensed more than the float rose. */
        val pumpMinusPidFillL: Double?,
        /** Drop before the pump + drop after auto-cut. Null when a brim has no fill window. */
        val netBurnPct: Double?,
        val netBurnLitres: Double?,
        val fillOnThisTrip: Boolean
    ) {
        val tripIsBrim: Boolean
            get() = tripStartPct != null && tripEndPct != null && tripEndPct > tripStartPct + 4.0
    }

    fun split(
        tripStartPct: Double?,
        tripEndPct: Double?,
        tankL: Double,
        fillBeforePct: Double? = null,
        fillAfterPct: Double? = null,
        pumpLitres: Double? = null,
        fillOnThisTrip: Boolean = false
    ): Split {
        val tank = TankCapacity.sanitize(tankL) ?: TankCapacity.NOMINAL_L
        val tripWindowL = if (tripStartPct != null && tripEndPct != null) {
            TankCapacity.litresFromPercent(tripEndPct - tripStartPct, tank)
        } else null

        val fillRise = if (fillBeforePct != null && fillAfterPct != null) fillAfterPct - fillBeforePct else null
        val pidFillL = if (fillRise != null && fillRise >= 4.0) {
            TankCapacity.litresFromPercent(fillRise, tank)
        } else null
        val pump = pumpLitres?.takeIf { it > 0.5 }
        val pumpDelta = if (pump != null && pidFillL != null) pump - pidFillL else null

        val netPct = when {
            fillOnThisTrip && fillBeforePct != null && fillAfterPct != null -> {
                // Trip start near auto-cut means the recording began after the pump —
                // do not treat 17.6 → 93.7 as a pre-fill drop.
                val startedBeforeFill = tripStartPct != null && tripStartPct < fillAfterPct - 4.0
                val pre = if (startedBeforeFill) maxOf(0.0, tripStartPct!! - fillBeforePct) else 0.0
                val post = tripEndPct?.let { maxOf(0.0, fillAfterPct - it) } ?: 0.0
                pre + post
            }
            tripStartPct != null && tripEndPct != null && tripStartPct > tripEndPct + 0.3 ->
                tripStartPct - tripEndPct
            else -> null
        }
        val netL = netPct?.takeIf { it > 0.15 }?.let { TankCapacity.litresFromPercent(it, tank) }

        return Split(
            tripStartPct = tripStartPct,
            tripEndPct = tripEndPct,
            fillBeforePct = fillBeforePct,
            fillAfterPct = fillAfterPct,
            pumpLitres = pump,
            pidTripWindowLitres = tripWindowL,
            pidFillLitres = pidFillL,
            pumpMinusPidFillL = pumpDelta,
            netBurnPct = netPct?.takeIf { it > 0.15 },
            netBurnLitres = netL,
            fillOnThisTrip = fillOnThisTrip
        )
    }

    fun pct(v: Double?): String =
        v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--%"

    fun litres(v: Double?): String =
        v?.let { String.format(Locale.US, "%.2f L", it) } ?: "-- L"

    /** "PID 40.26 L vs pump 40.27 L (Δ +0.01 L)" — bunk short is the delta. */
    fun pumpVsPidLine(split: Split): String? {
        val pid = split.pidFillLitres ?: return null
        val pump = split.pumpLitres ?: return null
        val d = split.pumpMinusPidFillL ?: (pump - pid)
        return String.format(Locale.US, "PID %.2f L vs pump %.2f L (Δ %+.2f L)", pid, pump, d)
    }

    fun fillWindowLine(split: Split): String? {
        if (split.fillBeforePct == null || split.fillAfterPct == null) return null
        return "Fill ${pct(split.fillBeforePct)} → ${pct(split.fillAfterPct)}"
    }
}
