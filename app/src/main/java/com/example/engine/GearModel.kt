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
