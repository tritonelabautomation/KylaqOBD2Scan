package com.example

import com.example.engine.Aq250GearModel
import org.junit.Assert.*
import org.junit.Test

/**
 * AQ250-6F (09G) gear model per SSP 291: factory ratios are truth, the rpm-per-km/h scale
 * self-calibrates from steady cruise samples so the owner's D/S/M paddle gears all resolve.
 */
class GearModelTest {

    @Test
    fun `prior scale resolves highway cruise gears`() {
        val model = Aq250GearModel()
        // 100 km/h at 2200 rpm = 22.0 rpm per km/h -> 5th (prior 22.33)
        assertEquals(5, model.estimate(22.0)?.first)
        assertTrue(model.estimate(22.0)?.second == true)
        // 100 km/h at 1750 rpm = 17.5 -> 6th (prior 17.84)
        assertEquals(6, model.estimate(17.5)?.first)
        // City 3rd: 40 km/h at 1650 rpm = 41.25 -> 3rd (prior 40.46)
        assertEquals(3, model.estimate(41.25)?.first)
    }

    @Test
    fun `ratio between gears is not guessed`() {
        val model = Aq250GearModel()
        // 25.5 sits 14% above 5th and 15% below 4th: converter slip or shift transient.
        assertNull(model.estimate(25.5))
        assertNull(model.estimate(3.0))
    }

    @Test
    fun `steady cruise samples adapt the scale within bounds`() {
        val model = Aq250GearModel()
        val before = model.calibratedScales().first { it.first == 5 }.second
        repeat(10) { model.observe(24.0, stable = true) }
        val after = model.calibratedScales().first { it.first == 5 }.second
        assertTrue("scale must move toward observations", after > before)
        // Bounded: never more than +25% of the prior (22.33 * 1.25 = 27.9)
        assertTrue(after <= 22.33 * 1.25 + 0.001)
        assertEquals(10, model.samplesFor(5))
        // Unstable samples never adapt
        val stableCount = model.totalSamples()
        model.observe(30.0, stable = false)
        assertEquals(stableCount, model.totalSamples())
    }

    @Test
    fun `ssp 291 constants are the factory truth`() {
        assertEquals(4.148, Aq250GearModel.RATIOS[0], 0.0001)
        assertEquals(0.686, Aq250GearModel.RATIOS[5], 0.0001)
        assertEquals(6.05, Aq250GearModel.SPREAD, 0.001)
        assertEquals(3.394, Aq250GearModel.REVERSE_RATIO, 0.0001)
        assertTrue(Aq250GearModel.ATF_SPEC.contains("G 052 025"))
    }
}
