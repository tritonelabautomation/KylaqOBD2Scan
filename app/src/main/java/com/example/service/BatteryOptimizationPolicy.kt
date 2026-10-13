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

    /** Ask again after a day, not never. */
    const val REASK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /**
     * The old rule was `sessionActive && !ignoring && !alreadyPrompted` - ONE attempt, ever, and
     * only while a session was live.
     *
     * That is why the owner's logs died on 2026-09-17: he dismissed the system dialog once (or
     * launched the app without an active session, so it never asked at all), the sticky
     * `battery_exempt_prompted` flag was set, and from then on the app never asked again while
     * Motorola kept killing the service. A one-shot prompt for a setting that decides whether the
     * app can keep its data is a prompt that will be missed.
     *
     * Now: ask whenever the exemption is missing and at least [REASK_INTERVAL_MS] has passed since
     * the last ask - still never a nag on every resume, but no longer silence forever. A granted
     * exemption stops the prompts for good, which is the only terminal state that matters.
     */
    fun shouldPrompt(
        isIgnoringBatteryOptimizations: Boolean,
        alreadyPrompted: Boolean,
        sessionActive: Boolean,
        lastPromptAtMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        // Granted -> terminal. This is the only state in which the app stops asking.
        if (isIgnoringBatteryOptimizations) return false
        // Never asked: ask now. Note that `sessionActive` deliberately does NOT gate this any
        // more - the exemption is what keeps the NEXT recording alive too, and waiting for a live
        // session means only ever asking while the owner is driving and cannot deal with a dialog.
        // The parameter is kept so the call site still records what it observed.
        if (!alreadyPrompted) return true
        // Asked before and still restricted: ask again, but at most once per interval.
        return nowMs - lastPromptAtMs >= REASK_INTERVAL_MS
    }
}
