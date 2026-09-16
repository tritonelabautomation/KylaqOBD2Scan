package com.example.service

/**
 * One-shot battery-optimisation exemption prompt policy.
 *
 * Owner pain (2026-09-16, Moto edge 20): a 74-minute trip was killed mid-recording by
 * OEM battery management and came back as "Recovered Run"; the owner had to find
 * Settings > App > Battery > Unrestricted by hand. A foreground service survives most
 * kills, but not OEM battery restrictions - so ask Android for the exemption ONCE,
 * while a session actually matters, and never nag again (the answered flag is sticky).
 */
object BatteryOptimizationPolicy {
    fun shouldPrompt(
        isIgnoringBatteryOptimizations: Boolean,
        alreadyPrompted: Boolean,
        sessionActive: Boolean
    ): Boolean = sessionActive && !isIgnoringBatteryOptimizations && !alreadyPrompted
}
