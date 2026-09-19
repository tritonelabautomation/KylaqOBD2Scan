package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inspection verdict the ECU already knew (owner gap analysis 2026-09-19): J1979 readiness
 * monitors were in the PID catalogue as raw bitmasks with no decoder and no UI. These pin the
 * bit layout - MIL and DTC count in byte A, one supported bit and one complete bit per monitor
 * in B/C/D - and the rule that unfitted monitors never block READY.
 */
class ReadinessMonitorsTest {

    @Test
    fun aFullyRunCarIsReadyAndCountsItsFittedMonitors() {
        // misfire/fuel/components + catalyst/heated-cat/evap/O2S + O2-heater/EGR fitted, all done.
        val s = ReadinessMonitors.decode(0x00, 0x77, 0xFF, 0x99)
        assertFalse(s.milOn)
        assertEquals(0, s.confirmedDtcCount)
        assertTrue(s.ready)
        assertEquals(9, s.supportedCount)
        assertEquals(2, s.monitors.count { !it.supported }) // secondary air, A/C refrigerant
    }

    @Test
    fun oneUnfinishedMonitorBlocksReadyButNotTheOthers() {
        // EVAP fitted (C bit2) but its complete bit (C bit6) never set.
        val s = ReadinessMonitors.decode(0x00, 0x77, 0xBF, 0x89)
        assertFalse(s.ready)
        val evap = s.monitors.first { it.name == "Evaporative system" }
        assertTrue(evap.supported)
        assertFalse(evap.complete)
        assertTrue(s.monitors.first { it.name == "Catalyst" }.complete)
    }

    @Test
    fun milAndDtcCountComeFromByteA() {
        val s = ReadinessMonitors.decode(0x03, 0x77, 0xFF, 0x99)
        assertTrue(s.milOn)
        assertEquals(1, s.confirmedDtcCount)
        assertFalse(s.ready) // MIL on is never "ready", whatever the monitors say
    }

    @Test
    fun responseLinesParseOnlyWhenTheyAreMonitorAnswers() {
        val s = ReadinessMonitors.fromResponseLine("41 41 00 77 FF 99", "41")
        assertEquals(9, s!!.supportedCount)
        assertNull(ReadinessMonitors.fromResponseLine("NO DATA", "41"))
        assertNull(ReadinessMonitors.fromResponseLine("41 01 00 77 FF 99", "41"))
        assertNull(ReadinessMonitors.fromResponseLine("41 41 00 77 FF", "41"))
    }
}
