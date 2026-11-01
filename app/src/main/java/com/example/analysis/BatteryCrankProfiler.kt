package com.example.analysis

/** PID 0142 is ECU supply voltage, NOT a calibrated battery-terminal/CCA test.
 * EFB/AGM heuristic at ordinary ambient temperature: 9.6V cranking floor, 11V
 * full score. A low score merits a proper load test, not automatic replacement.
 * RPM validates cranking (0 < rpm < 500); accessory sag alone cannot create a profile.
 */
class BatteryCrankProfiler {
    enum class Phase { WAITING, READY, CRANKING, COMPLETE }
    data class Result(
        val phase: Phase, val restingVoltage: Double?, val minimumCrankVoltage: Double?,
        val chargingVoltage: Double?, val healthPercent: Int?, val weakBatteryWarning: Boolean
    )
    private var phase = Phase.WAITING
    private var lastTime: Long? = null
    private var crankStart = 0L
    private var resting: Double? = null
    private var minimum: Double? = null
    private var charging: Double? = null
    private var score: Int? = null

    fun update(timeMs: Long, voltage: Double?, rpm: Double?): Result {
        if (timeMs < 0 || lastTime?.let { timeMs <= it } == true) return result()
        val gap = lastTime?.let { timeMs - it } ?: 0L
        if (phase != Phase.COMPLETE && gap > 2000) clearEvidence()
        lastTime = timeMs
        if (voltage == null || !voltage.isFinite() || voltage !in 5.0..18.0 ||
            rpm == null || !rpm.isFinite() || rpm !in 0.0..8000.0) {
            if (phase != Phase.COMPLETE) clearEvidence()
            return result()
        }
        when (phase) {
            Phase.WAITING, Phase.READY -> {
                if (rpm == 0.0 && voltage in 11.0..13.2) { resting = voltage; phase = Phase.READY }
                else if (phase == Phase.READY && rpm > 0.0 && rpm < 500.0) {
                    minimum = voltage; crankStart = timeMs; phase = Phase.CRANKING
                } else if (rpm >= 500.0) clearEvidence()
            }
            Phase.CRANKING -> {
                if (timeMs - crankStart > 10_000 || rpm == 0.0) clearEvidence()
                else if (rpm < 500.0) minimum = minOf(minimum!!, voltage)
                else {
                    charging = voltage
                    score = (((minimum!! - 9.6) / (11.0 - 9.6)) * 100.0).toInt().coerceIn(0, 100)
                    phase = Phase.COMPLETE
                }
            }
            Phase.COMPLETE -> Unit
        }
        return result()
    }

    private fun result() = Result(phase, resting, minimum, charging, score, score?.let { it < 40 } ?: false)
    private fun clearEvidence() { phase = Phase.WAITING; resting = null; minimum = null; charging = null; score = null }
    fun reset() { clearEvidence(); lastTime = null; crankStart = 0L }
}
