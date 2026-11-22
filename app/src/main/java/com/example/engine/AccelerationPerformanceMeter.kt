package com.example.engine

/** Closed-course use only. Timings use monotonic timestamps and linear interpolation
 * between speed samples, with no rollout subtraction. Arm only after a stationary
 * sample; a >1s telemetry gap, invalid speed, timeout or stop aborts the run.
 */
class AccelerationPerformanceMeter {
    enum class State { IDLE, ARMED, RUNNING, COMPLETE, ABORTED }
    data class Result(val state: State, val zeroTo60Seconds: Double?, val zeroTo100Seconds: Double?, val peakBoostBar: Double?)
    private var state = State.IDLE
    private var lastTime: Long? = null
    private var lastSpeed = 0.0
    private var launchMs = 0.0
    private var split60: Double? = null
    private var split100: Double? = null
    private var peak: Double? = null

    fun update(timeMs: Long, speedKmh: Double?, boostBar: Double? = null): Result {
        if (timeMs < 0 || lastTime?.let { timeMs <= it } == true) return result()
        val dt = lastTime?.let { timeMs - it } ?: 0L
        val valid = speedKmh != null && speedKmh.isFinite() && speedKmh in 0.0..250.0
        if (!valid || (dt > 1000 && state in listOf(State.ARMED, State.RUNNING))) {
            if (state == State.ARMED || state == State.RUNNING) state = State.ABORTED
            lastTime = timeMs
            return result()
        }
        val speed = speedKmh!!
        if (state == State.IDLE && speed == 0.0) state = State.ARMED
        if (state == State.ARMED && speed > 0.0 && lastTime != null) {
            // Last stationary observation is the best available launch bound.
            launchMs = lastTime!!.toDouble()
            state = State.RUNNING
        }
        if (state == State.RUNNING) {
            if (speed == 0.0 || timeMs - launchMs > 120_000.0) state = State.ABORTED
            else {
                boostBar?.takeIf { it.isFinite() && it in -1.0..4.0 }?.let { peak = maxOf(peak ?: it, it) }
                fun crossing(target: Double): Double? = if (lastSpeed < target && speed >= target && dt > 0)
                    (timeMs - dt + dt * (target - lastSpeed) / (speed - lastSpeed) - launchMs) / 1000.0 else null
                if (split60 == null) split60 = crossing(60.0)
                if (split100 == null) split100 = crossing(100.0)
                if (split100 != null) state = State.COMPLETE
            }
        }
        lastTime = timeMs
        lastSpeed = speed
        return result()
    }

    private fun result() = Result(state, split60, split100, peak)
    /** Explicit reset prevents a completed/aborted pass being silently overwritten. */
    fun reset() {
        state = State.IDLE; lastTime = null; lastSpeed = 0.0; launchMs = 0.0
        split60 = null; split100 = null; peak = null
    }
}
