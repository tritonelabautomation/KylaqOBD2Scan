package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which alerts fire for one live snapshot (owner 2026-09-19, gap-analysis build 1). The silence
 * rules matter as much as the firing ones: a PID the car is not answering is absent from the
 * map and must never alarm, and a value inside the limits says nothing at all.
 */
class AlertRulesTest {

    private val t = AlertRules.Thresholds()

    @Test
    fun breachesFireWithTheirMetricAndMessage() {
        val alerts = AlertRules.evaluate(mapOf("0105" to 112.0), t)
        assertEquals(1, alerts.size)
        assertEquals("coolant", alerts[0].metric)
        assertTrue(alerts[0].message.contains("112"))
    }

    @Test
    fun voltageAlarmsOnBothSidesOfTheChargingWindow() {
        assertEquals("voltage-low", AlertRules.evaluate(mapOf("0142" to 11.9), t)[0].metric)
        assertEquals("voltage-high", AlertRules.evaluate(mapOf("0142" to 15.9), t)[0].metric)
        assertTrue(AlertRules.evaluate(mapOf("0142" to 13.8), t).isEmpty())
    }

    @Test
    fun fuelAndRevLimitsFireAtTheLimitNotAboveIt() {
        assertEquals("fuel", AlertRules.evaluate(mapOf("012F" to 12.0), t)[0].metric)
        assertEquals("rpm", AlertRules.evaluate(mapOf("010C" to 5500.0), t)[0].metric)
        assertTrue(AlertRules.evaluate(mapOf("012F" to 12.5, "010C" to 5499.0), t).isEmpty())
    }

    @Test
    fun absentPidsStaySilent() {
        assertTrue(AlertRules.evaluate(emptyMap(), t).isEmpty())
        assertTrue(AlertRules.evaluate(mapOf("010D" to 90.0), t).isEmpty())
    }
}
