package com.example

import com.example.engine.AcClimateModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AC & climate behaviour model (added 2026-09-09). */
class AcClimateModelTest {

    @Test
    fun `off tag draws no compressor load`() {
        assertEquals(0.0, AcClimateModel.compressorLoadKw("OFF", false, 35.0, 24.0), 1e-9)
        assertEquals(0.0, AcClimateModel.fuelPenaltyLh(0.0), 1e-9)
    }

    @Test
    fun `blower only is a small fixed load`() {
        assertEquals(AcClimateModel.BLOWER_KW, AcClimateModel.compressorLoadKw("BLOWER", false, 35.0, 24.0), 1e-9)
    }

    @Test
    fun `manual AC load scales with delta-T`() {
        // delta 11 K -> 1.1 + 1.1 = 2.2 kW
        assertEquals(2.2, AcClimateModel.compressorLoadKw("AC", false, 35.0, 24.0), 1e-9)
        // unknown ambient falls back to 8 K -> 1.1 + 0.8 = 1.9 kW
        assertEquals(1.9, AcClimateModel.compressorLoadKw("AC", false, null, 24.0), 1e-9)
    }

    @Test
    fun `auto mode modulates and load is capped`() {
        assertEquals(2.2 * AcClimateModel.AUTO_MODULATION, AcClimateModel.compressorLoadKw("AC", true, 35.0, 24.0), 1e-9)
        // delta 44 K would be 5.5 kW -> capped at 4.0
        assertEquals(AcClimateModel.AC_MAX_KW, AcClimateModel.compressorLoadKw("AC", false, 60.0, 16.0), 1e-9)
    }

    @Test
    fun `fuel penalty prices kW through drivetrain efficiency`() {
        // 2.2 kW / (0.30 * 32.3 / 3.6) = 0.8173 L/h
        assertEquals(0.8173, AcClimateModel.fuelPenaltyLh(2.2), 1e-3)
        assertEquals(100.0 * 0.8173 / 6.0, AcClimateModel.penaltyPercent(0.8173, 6.0)!!, 1e-6)
        assertNull(AcClimateModel.penaltyPercent(0.8173, null))
    }

    @Test
    fun `delta needs both temperatures`() {
        assertNull(AcClimateModel.deltaC(null, 24.0))
        assertEquals(11.0, AcClimateModel.deltaC(35.0, 24.0)!!, 1e-9)
    }
}
