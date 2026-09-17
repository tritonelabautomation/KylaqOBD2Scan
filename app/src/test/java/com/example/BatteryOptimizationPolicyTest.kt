package com.example

import com.example.service.BatteryOptimizationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery-optimisation exemption prompt policy.
 *
 * ## Why this contract changed on 2026-09-17
 *
 * The previous rule was: ask ONCE, only while a session is live, then never again - and the
 * "asked" flag was sticky forever. The owner's Moto Edge 20 killed the recording service that day
 * and the whole drive was lost, and the app had already spent its single prompt long before (or
 * had never had a live session to prompt during, so it never asked at all). A one-shot prompt for
 * the one setting that decides whether the app can keep its data is a prompt that gets missed -
 * and missing it costs the logs, not a notification.
 *
 * The contract now is:
 *  - exemption granted  -> never ask again (the only terminal state);
 *  - exemption missing  -> ask, whether or not a session is live, because the exemption protects
 *                          the NEXT recording too;
 *  - asked recently     -> stay quiet, so it is not a nag on every resume;
 *  - asked over [BatteryOptimizationPolicy.REASK_INTERVAL_MS] ago and still missing -> ask again.
 */
class BatteryOptimizationPolicyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun promptsWhenRestrictedAndSessionLive() {
        assertTrue(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = false,
                alreadyPrompted = false,
                sessionActive = true,
                lastPromptAtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun promptsEvenWithNoSessionLiveSoTheNextDriveIsProtected() {
        // Was false before 1.0.337: no live session meant the app never asked, so a drive that
        // started later ran unprotected and its logs died with the process.
        assertTrue(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = false,
                alreadyPrompted = false,
                sessionActive = false,
                lastPromptAtMs = 0L,
                nowMs = now
            )
        )
    }

    @Test
    fun neverNagsOnceTheExemptionIsGranted() {
        assertFalse(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = true,
                alreadyPrompted = false,
                sessionActive = true,
                lastPromptAtMs = 0L,
                nowMs = now
            )
        )
        assertFalse(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = true,
                alreadyPrompted = true,
                sessionActive = true,
                lastPromptAtMs = now - 400 * 24 * 3_600_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun staysQuietForTheReaskIntervalAfterAPrompt() {
        assertFalse(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = false,
                alreadyPrompted = true,
                sessionActive = true,
                lastPromptAtMs = now - 60_000L,
                nowMs = now
            )
        )
        assertFalse(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = false,
                alreadyPrompted = true,
                sessionActive = true,
                lastPromptAtMs = now - BatteryOptimizationPolicy.REASK_INTERVAL_MS + 1,
                nowMs = now
            )
        )
    }

    @Test
    fun asksAgainOnceTheReaskIntervalHasPassedAndTheExemptionIsStillMissing() {
        // The 2026-09-17 fix: a dismissed dialog used to silence the app permanently while the OEM
        // kept killing the service.
        assertTrue(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = false,
                alreadyPrompted = true,
                sessionActive = true,
                lastPromptAtMs = now - BatteryOptimizationPolicy.REASK_INTERVAL_MS,
                nowMs = now
            )
        )
        assertTrue(
            BatteryOptimizationPolicy.shouldPrompt(
                isIgnoringBatteryOptimizations = false,
                alreadyPrompted = true,
                sessionActive = false,
                lastPromptAtMs = now - 30 * 24 * 3_600_000L,
                nowMs = now
            )
        )
    }
}
