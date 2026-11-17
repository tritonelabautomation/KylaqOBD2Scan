package com.example.engine

import java.util.Locale

/**
 * E20 vs XP95 economy & fuel-trim ROI.
 *
 * Indian regular petrol is E20 (RON ≈ 95). XP95 / X95 is sold at a ₹4–12/L premium.
 * On this 10.5:1 turbo the knock sensor has almost no extra octane headroom, so the
 * extra rupees are only earned if measured km/L (and/or leaner trims) beat the price
 * premium. This engine accumulates cruise samples per grade and reports:
 *
 *  * km/L and ₹/km per grade;
 *  * STFT / LTFT averages (persistent positive LTFT on E20 is a weak-fuel hint);
 *  * break-even economy gain (%) equal to the price premium;
 *  * a worth-it verdict in rupees saved (or lost) per 100 km.
 *
 * Honest: route mix, AC and traffic move km/L too. Feed cruise-ish samples when you can.
 */
class OctaneRoiAnalyzer(
    private val defaultE20PriceInr: Double = 115.0,
    private val defaultXp95PriceInr: Double = 120.0
) {

    enum class Grade { E20, XP95, UNKNOWN }

    data class GradeStats(
        val grade: Grade,
        val distanceKm: Double,
        val fuelL: Double,
        val kmPerL: Double?,
        val avgStftPct: Double?,
        val avgLtftPct: Double?,
        val samples: Int
    )

    data class RoiVerdict(
        val worthIt: Boolean,
        val extraCostPerLiterInr: Double,
        val economyDeltaPercent: Double?,
        val costPerKmE20: Double?,
        val costPerKmXp95: Double?,
        val rupeesSavedPer100km: Double?,
        val breakEvenEconomyGainPct: Double,
        val trimDeltaPct: Double?,
        val e20: GradeStats?,
        val xp95: GradeStats?,
        val message: String
    )

    private var lastTs: Long = 0L
    private var e20Price: Double = defaultE20PriceInr
    private var xp95Price: Double = defaultXp95PriceInr
    private val e20 = Accumulator()
    private val xp95 = Accumulator()
    private var activeGrade: Grade = Grade.UNKNOWN

    private class Accumulator {
        var distanceKm: Double = 0.0
        var fuelL: Double = 0.0
        var stftSum: Double = 0.0
        var stftCount: Int = 0
        var ltftSum: Double = 0.0
        var ltftCount: Int = 0
        var samples: Int = 0
        fun kmPerL(): Double? = if (fuelL > 0.05 && distanceKm > 1.0) distanceKm / fuelL else null
        fun avgStft(): Double? = if (stftCount > 0) stftSum / stftCount else null
        fun avgLtft(): Double? = if (ltftCount > 0) ltftSum / ltftCount else null
        fun toStats(grade: Grade) = GradeStats(
            grade = grade,
            distanceKm = distanceKm,
            fuelL = fuelL,
            kmPerL = kmPerL(),
            avgStftPct = avgStft(),
            avgLtftPct = avgLtft(),
            samples = samples
        )
        fun reset() {
            distanceKm = 0.0
            fuelL = 0.0
            stftSum = 0.0
            stftCount = 0
            ltftSum = 0.0
            ltftCount = 0
            samples = 0
        }
    }

    fun reset() {
        lastTs = 0L
        e20.reset()
        xp95.reset()
        activeGrade = Grade.UNKNOWN
    }

    fun setPrices(e20Inr: Double, xp95Inr: Double) {
        if (e20Inr > 0.0) e20Price = e20Inr
        if (xp95Inr > 0.0) xp95Price = xp95Inr
    }

    fun setActiveGrade(grade: Grade) {
        activeGrade = grade
    }

    fun parseGrade(tag: String?): Grade {
        val s = tag?.uppercase(Locale.US)?.trim().orEmpty()
        if (s.isEmpty()) return Grade.UNKNOWN
        return when {
            s.contains("XP95") || s.contains("XP 95") ||
                s.contains("X95") || s.contains("V-POWER") || s.contains("VPOWER") ||
                s.contains("POWER95") || s.contains("POWER 95") -> Grade.XP95
            s.contains("E20") || s.contains("REGULAR") || s == "MS" ||
                s.startsWith("MS ") || s.contains("PETROL") -> Grade.E20
            else -> Grade.UNKNOWN
        }
    }

    fun onSample(
        timestampMonotonicMs: Long,
        speedKmh: Double?,
        fuelRateLh: Double?,
        stftPct: Double? = null,
        ltftPct: Double? = null,
        gradeTag: String? = null
    ): RoiVerdict {
        val dtSec = if (lastTs > 0L) {
            ((timestampMonotonicMs - lastTs).coerceIn(0L, 5000L)) / 1000.0
        } else 0.0
        lastTs = timestampMonotonicMs

        gradeTag?.let { parsed ->
            val g = parseGrade(parsed)
            if (g != Grade.UNKNOWN) activeGrade = g
        }

        val acc = when (activeGrade) {
            Grade.E20 -> e20
            Grade.XP95 -> xp95
            Grade.UNKNOWN -> null
        }
        if (acc != null && dtSec > 0.0) {
            val speed = (speedKmh ?: 0.0).coerceAtLeast(0.0)
            val fuel = (fuelRateLh ?: 0.0).coerceAtLeast(0.0)
            acc.distanceKm += speed * (dtSec / 3600.0)
            acc.fuelL += fuel * (dtSec / 3600.0)
            stftPct?.let { acc.stftSum += it; acc.stftCount++ }
            ltftPct?.let { acc.ltftSum += it; acc.ltftCount++ }
            acc.samples++
        }
        return verdict()
    }

    fun verdict(): RoiVerdict {
        val e20Stats = e20.toStats(Grade.E20).takeIf { it.samples > 0 }
        val xp95Stats = xp95.toStats(Grade.XP95).takeIf { it.samples > 0 }
        val extra = (xp95Price - e20Price).coerceAtLeast(0.0)
        val breakEvenPct = if (e20Price > 0.0) (xp95Price / e20Price - 1.0) * 100.0 else 0.0

        val e20KmL = e20Stats?.kmPerL
        val xp95KmL = xp95Stats?.kmPerL
        val economyDeltaPct = if (e20KmL != null && xp95KmL != null && e20KmL > 0.0) {
            (xp95KmL / e20KmL - 1.0) * 100.0
        } else null

        val costE20 = e20KmL?.let { if (it > 0.0) e20Price / it else null }
        val costXp95 = xp95KmL?.let { if (it > 0.0) xp95Price / it else null }
        val savedPer100 = if (costE20 != null && costXp95 != null) {
            (costE20 - costXp95) * 100.0
        } else null

        val trimDelta = if (e20Stats?.avgLtftPct != null && xp95Stats?.avgLtftPct != null) {
            e20Stats.avgLtftPct - xp95Stats.avgLtftPct
        } else null

        val worthIt = savedPer100 != null && savedPer100 > 0.0
        val message = when {
            e20Stats == null || xp95Stats == null || e20KmL == null || xp95KmL == null ->
                String.format(
                    Locale.US,
                    "Need a tank of E20 and a tank of XP95. Break-even is +%.1f%% km/L to cover ₹%.1f/L extra.",
                    breakEvenPct, extra
                )
            worthIt -> String.format(
                Locale.US,
                "XP95 earns its keep: ₹%.1f saved / 100 km (%.1f vs %.1f km/L). Premium ₹%.1f/L, economy +%.1f%%.",
                savedPer100, xp95KmL, e20KmL, extra, economyDeltaPct ?: 0.0
            )
            else -> String.format(
                Locale.US,
                "E20 is the economic fuel: XP95 costs ₹%.1f more / 100 km. Need +%.1f%% km/L to break even; measured %+.1f%%.",
                -(savedPer100 ?: 0.0), breakEvenPct, economyDeltaPct ?: 0.0
            )
        }

        return RoiVerdict(
            worthIt = worthIt,
            extraCostPerLiterInr = extra,
            economyDeltaPercent = economyDeltaPct,
            costPerKmE20 = costE20,
            costPerKmXp95 = costXp95,
            rupeesSavedPer100km = savedPer100,
            breakEvenEconomyGainPct = breakEvenPct,
            trimDeltaPct = trimDelta,
            e20 = e20Stats,
            xp95 = xp95Stats,
            message = message
        )
    }
}
