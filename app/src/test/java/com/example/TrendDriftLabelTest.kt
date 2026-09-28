package com.example

import com.example.data.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner screenshot 2026-09-16: the trends card printed "0.0 → 26.2 (+0%)" - a division
 * by zero dressed up as "no change". From zero to something is a NEW signal, not 0 %.
 */
class TrendDriftLabelTest {

    @Test
    fun zeroToSomethingIsNewNotZeroPercent() {
        assertEquals("new", Fmt.driftLabel(0.0, 26.2))
    }

    @Test
    fun zeroToZeroIsFlat() {
        assertEquals("±0%", Fmt.driftLabel(0.0, 0.0))
    }

    @Test
    fun normalGrowthKeepsSignedPercent() {
        assertEquals("+45%", Fmt.driftLabel(976.0, 1412.0))
        assertEquals("+141%", Fmt.driftLabel(9.4, 22.7))
    }

    @Test
    fun declineIsNegativePercent() {
        assertEquals("-50%", Fmt.driftLabel(2.0, 1.0))
    }
}
