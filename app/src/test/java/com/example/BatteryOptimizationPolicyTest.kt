package com.example

import com.example.service.BatteryOptimizationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner regression (2026-09-16): OEM battery management killed a 74-minute trip
 * mid-recording ("Recovered Run"); the owner had to find Settings > App > Battery >
 * Unrestricted by hand. The app now asks for the standard exemption once, while a
 * session is live - and never nags again.
 */
class BatteryOptimizationPolicyTest {

    @Test
    fun promptsOnceWhenRestrictedAndSessionLive() {
        assertTrue(BatteryOptimizationPolicy.shouldPrompt(isIgnoringBatteryOptimizations = false, alreadyPrompted = false, sessionActive = true))
    }

    @Test
    fun neverNagsAfterTheFirstPrompt() {
        assertFalse(BatteryOptimizationPolicy.shouldPrompt(isIgnoringBatteryOptimizations = false, alreadyPrompted = true, sessionActive = true))
    }

    @Test
    fun silentWhenAlreadyExempt() {
        assertFalse(BatteryOptimizationPolicy.shouldPrompt(isIgnoringBatteryOptimizations = true, alreadyPrompted = false, sessionActive = true))
    }

    @Test
    fun silentWhenNoSessionIsLive() {
        assertFalse(BatteryOptimizationPolicy.shouldPrompt(isIgnoringBatteryOptimizations = false, alreadyPrompted = false, sessionActive = false))
    }
}
