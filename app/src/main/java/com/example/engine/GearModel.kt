package com.example.engine

import kotlin.math.abs

/**
 * AQ250-6F (Aisin AW TF-60SN, service code 09G) gear model, per the owner-supplied
 * Self-Study Programme 291:
 *
 *  - Ratios 4.148 / 2.370 / 1.556 / 1.155 / 0.859 / 0.686 (spread 6.05), reverse 3.394
 *  - Lepelletier planetary concept, only five shifting elements, hydrodynamic converter
 *    with slip-controlled lock-up clutch
 *  - ATF G 052 025 A2 (Esso JWS 3309), 7.0 L initial fill, lifetime filling
 *
 * Cross-verified against the Skoda 09G Workshop Manual Edition 07.2014 (see
 * docs/reference/09g-workshop-manual.md): identical ratio set across most applications;
 * variant sets exist (KGV 4.044-0.672, PLS/PAL 4.670-0.690, QEM 4.460-0.670) with two
 * idler (1.061 / 0.906) and two final-drive (4.067 / 3.867) combinations, and the ATF
 * top-up after repair is approx. 3 L. With the converter lock-up closed, gears 2-6 are
 * mechanically driven (no slip) - exactly the state this model calibrates from.
 *
 * The RELATIVE ratios are factory truth; the ABSOLUTE rpm-per-km/h scale (final drive x
 * idler gear x tyre circumference) differs per engine/tyre application, so the model
 * self-calibrates: confident cruise samples (converter locked, steady rpm & speed) adapt
 * each gear's scale with a bounded exponential moving average.
 */
class Aq250GearModel {

    companion object {
        val RATIOS: List<Double> = listOf(4.148, 2.370, 1.556, 1.155, 0.859, 0.686)
        const val REVERSE_RATIO: Double = 3.394
        const val SPREAD: Double = 6.05
        const val ATF_SPEC: String = "G 052 025 A2 (Esso JWS 3309)"
        const val ATF_FILL_L: Double = 7.0
        const val ATF_TOPUP_L: Double = 3.0

        // Deep-service data from the 09G Workshop Manual (Ed. 07.2014, p.147-154) and the
        // Skoda SLAVIA 2022 Maintenance Manual (Ed. 11.2021, s.4.1 - same India-spec AQ250):
        /** India maintenance drain-and-fill quantity (converter/cooler stay filled). */
        const val ATF_CHANGE_L: Double = 3.0
        /** Quantity added when correcting a low level at the inspection plug. */
        const val ATF_LEVEL_TOPUP_L: Double = 1.0
        /** ATF inspection (overflow) plug tightening torque. */
        const val ATF_PLUG_TORQUE_NM: Int = 27
        /** Level check window: start <=35 C, correct if ~1 drop/s before 40 C, close by 45 C... */
        const val ATF_CHECK_START_C: Int = 35
        const val ATF_CHECK_DROP_C: Int = 40
        const val ATF_CHECK_MAX_C: Int = 45
        /** ...except hot countries (India): close the plug by 50 C. */
        const val ATF_CHECK_MAX_HOT_C: Int = 50
        const val IDLER: Double = 1.061

        /** Prior rpm-per-km/h per unit ratio, from Kylaq/Kushaq-family cruise observations. */
        const val PRIOR_SCALE: Double = 26.0

        /** A sample within 12% of a gear scale counts as that gear (converter slip guard). */
        const val TOLERANCE: Double = 0.12
    }

    /** Learned rpm-per-km/h centre per gear (index 0 = 1st). */
    private val gearScale = DoubleArray(6) { PRIOR_SCALE * RATIOS[it] }
    private val gearSamples = IntArray(6)

    /**
     * Feeds one sample. [stable] must mean steady rpm AND speed (cruise / lock-up), so the
     * ratio is not polluted by converter slip or shift transients.
     */
    fun observe(rpmPerKmh: Double, stable: Boolean) {
        if (!stable || rpmPerKmh <= 5.0) return
        val hit = estimateInternal(rpmPerKmh) ?: return
        val idx = hit.first - 1
        val prior = PRIOR_SCALE * RATIOS[idx]
        val adapted = gearScale[idx] * 0.8 + rpmPerKmh * 0.2
        gearScale[idx] = adapted.coerceIn(prior * 0.75, prior * 1.25)
        gearSamples[idx] += 1
    }

    /** Returns gear 1-6 with confidence, or null when the ratio falls between gears. */
    fun estimate(rpmPerKmh: Double): Pair<Int, Boolean>? = estimateInternal(rpmPerKmh)

    /**
     * Factory-model expected engine rpm in [gear] (1-based) at [speedKmh], using the CURRENT
     * learned scale for that gear. This is the "expected behaviour" half of the
     * expected-vs-actual comparison the dashboard shows as converter slip.
     */
    fun expectedRpm(gear: Int, speedKmh: Double): Double? =
        gearScale.getOrNull(gear - 1)?.let { it * speedKmh }

    /**
     * Closest gear to [rpmPerKmh] even when OUTSIDE the confidence tolerance, paired with the
     * relative deviation (0.0 = exact). Used to quantify converter slip / launch behaviour
     * when no gear matches (estimate() returns null).
     */
    fun nearestGear(rpmPerKmh: Double): Pair<Int, Double>? {
        if (rpmPerKmh <= 5.0) return null
        var best = -1
        var bestDelta = Double.MAX_VALUE
        for (i in 0..5) {
            val delta = abs(gearScale[i] - rpmPerKmh) / gearScale[i]
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return if (best >= 0) (best + 1) to bestDelta else null
    }

    private fun estimateInternal(rpmPerKmh: Double): Pair<Int, Boolean>? {
        if (rpmPerKmh <= 5.0) return null
        var best = -1
        var bestDelta = Double.MAX_VALUE
        for (i in 0..5) {
            val delta = abs(gearScale[i] - rpmPerKmh) / gearScale[i]
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return if (bestDelta <= TOLERANCE) (best + 1) to true else null
    }

    /** Learned rpm-per-km/h centre per gear, for the gearbox card UI. */
    fun calibratedScales(): List<Pair<Int, Double>> = (0..5).map { (it + 1) to gearScale[it] }

    fun samplesFor(gear: Int): Int = if (gear in 1..6) gearSamples[gear - 1] else 0

    fun totalSamples(): Int = gearSamples.sum()
}
