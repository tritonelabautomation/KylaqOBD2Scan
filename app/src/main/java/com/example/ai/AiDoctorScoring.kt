package com.example.ai

/**
 * Pure live-health-score math for the AI Doctor hero card.
 *
 * HONESTY GATE (2026-09-14, owner: "without connect ... fake values"): the score
 * used to start at 100 and only deduct when a live reading was bad - so with NO
 * adapter link the app displayed a perfect 100/100 with zero evidence behind it.
 * [liveHealthScore] now returns null unless the adapter is CONNECTED AND at least
 * one live sample exists; the UI must then show "--" instead of a number.
 */
object AiDoctorScoring {

    /** Deductions from a clean-sheet 100 while live telemetry is flowing. */
    const val WEAK_BATTERY_PENALTY = 15
    const val OVERHEAT_PENALTY = 25
    const val WEAK_BATTERY_V = 12.4
    const val OVERHEAT_C = 108.0

    /**
     * @param connected adapter link state (ConnectionState.CONNECTED)
     * @param volt live control-module voltage (PID 0142), null when absent/stale
     * @param coolantC live coolant temperature (PID 0105), null when absent/stale
     * @return null when there is nothing to score; otherwise 0..100
     */
    fun liveHealthScore(connected: Boolean, volt: Double?, coolantC: Double?): Int? {
        if (!connected) return null
        if (volt == null && coolantC == null) return null
        var score = 100
        if (volt != null && volt < WEAK_BATTERY_V && volt > 0) score -= WEAK_BATTERY_PENALTY
        if (coolantC != null && coolantC > OVERHEAT_C) score -= OVERHEAT_PENALTY
        return score.coerceIn(0, 100)
    }
}
