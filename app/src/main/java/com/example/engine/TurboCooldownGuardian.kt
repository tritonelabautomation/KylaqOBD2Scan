package com.example.engine

import kotlin.math.ceil

/** Bearing heat-load proxy, not a measured turbo temperature or an ECU shutdown command.
 * Feed fresh, synchronized ECM (7E8) samples with a monotonic millisecond timestamp.
 * Only running, low-load samples discharge the recommendation; missing data cannot cool it.
 */
class TurboCooldownGuardian {
    data class Result(val thermalLoad: Double, val cooldownSeconds: Int, val highLoad: Boolean)
    private var lastTime: Long? = null
    private var lastHigh = false
    private var lastCooling = false
    private var load = 0.0

    fun update(timeMs: Long, boostBar: Double?, rpm: Double?, coolantC: Double?): Result {
        if (timeMs < 0 || lastTime?.let { timeMs <= it } == true) return result(lastHigh)
        val valid = boostBar != null && boostBar.isFinite() && boostBar in -1.0..4.0 &&
            rpm != null && rpm.isFinite() && rpm in 0.0..8000.0 &&
            coolantC != null && coolantC.isFinite() && coolantC in -40.0..150.0
        val high = valid && boostBar!! > 0.5 && rpm!! > 2500.0 && coolantC!! > 95.0
        val cooling = valid && rpm!! in 500.0..2500.0 && boostBar!! <= 0.5
        val dt = lastTime?.let { timeMs - it } ?: 0L
        if (dt in 1..5000) {
            if (high && lastHigh) load = (load + dt / 1000.0).coerceAtMost(60.0)
            if (cooling && lastCooling) load = (load - dt / 1000.0).coerceAtLeast(0.0)
        }
        // A single validated high-load observation warrants a minimum precaution.
        if (high) load = load.coerceAtLeast(15.0)
        lastTime = timeMs
        lastHigh = high
        lastCooling = cooling
        return result(high)
    }

    private fun result(high: Boolean) = Result(load, if (load > 0.0) ceil(load).toInt().coerceIn(15, 60) else 0, high)
    fun reset() { lastTime = null; lastHigh = false; lastCooling = false; load = 0.0 }
}
