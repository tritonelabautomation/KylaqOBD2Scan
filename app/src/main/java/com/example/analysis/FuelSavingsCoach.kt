package com.example.analysis

import com.example.data.FuelLogCodec
import com.example.engine.AcClimateModel
import com.example.engine.PowertrainModel

/**
 * Pure personalisation math behind the Save Fuel playbook screen (owner request
 * 2026-09-13, patterned on general fuel-saving guides but wired to THIS car's models
 * and the owner's own fuel log).
 *
 * Every number the playbook shows is computed here so it stays unit-testable and
 * consistent with PowertrainModel / AcClimateModel / FuelLogCodec semantics
 * (partial fill-ups contribute litres but never anchor a km/L interval).
 */
object FuelSavingsCoach {

    /** Multi-tank baseline: the guide's "you can't improve what you don't measure" step. */
    data class Baseline(
        /** Mean km/L over the most recent closed full-tank intervals (max 4). */
        val recentKmL: Double?,
        /** Mean km/L over the up-to-4 intervals before those (null when history is short). */
        val previousKmL: Double?,
        /** Percent change recent vs previous (null unless both windows exist). */
        val trendPct: Double?,
        /** Number of closed intervals feeding recentKmL. */
        val tanks: Int,
        /** Price per litre from the newest log entry (null when never logged). */
        val latestPricePerL: Double?
    )

    fun baseline(entries: List<FuelLogCodec.FuelEntry>): Baseline {
        val kmL = FuelLogCodec.intervals(entries).map { it.second }
        val recent = kmL.takeLast(4)
        val previous = kmL.dropLast(4).takeLast(4)
        val r = if (recent.isNotEmpty()) recent.average() else null
        val p = if (previous.isNotEmpty()) previous.average() else null
        val trend = if (r != null && p != null && p > 0.0) (r - p) / p * 100.0 else null
        val price = entries.maxByOrNull { it.idMs }?.pricePerL?.takeIf { it > 0.0 }
        return Baseline(
            recentKmL = r,
            previousKmL = p,
            trendPct = trend,
            tanks = recent.size,
            latestPricePerL = price
        )
    }

    /** Warm idle of the 1.0 TSI (0.8 L/h) priced at the owner's latest pump price. */
    fun idleRupeesPerHour(pricePerL: Double?): Double? =
        pricePerL?.takeIf { it > 0.0 }?.let { PowertrainModel.IDLE_FUEL_LH * it }

    /** Convenience for the "10 minutes idling costs you..." line. */
    fun idleRupeesPer10Min(pricePerL: Double?): Double? =
        idleRupeesPerHour(pricePerL)?.let { it / 6.0 }

    /**
     * Extra L/h the climate system burns, straight from the documented AC model
     * (AC = 1.1 kW + 0.10 kW per Δ°C capped at 4.0; AUTO modulates ×0.75;
     * L/h = kW ÷ 2.6916 at 30 % drivetrain efficiency and 32.3 MJ/L).
     */
    fun acExtraLh(acTag: String, autoMode: Boolean, ambientC: Double?, setTempC: Double?): Double =
        AcClimateModel.fuelPenaltyLh(
            AcClimateModel.compressorLoadKw(acTag, autoMode, ambientC, setTempC)
        )
}
