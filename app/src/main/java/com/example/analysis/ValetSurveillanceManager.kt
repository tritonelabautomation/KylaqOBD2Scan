package com.example.analysis

/** Opt-in, in-memory audit. Each contiguous violation is one event, not one per poll.
 * Caller owns persistence/consent. No location collection or vehicle control.
 * A reconnect (>5s) ends an episode; unknown telemetry cannot establish compliance.
 */
class ValetSurveillanceManager(private val maxEvents: Int = 500) {
    init { require(maxEvents > 0) }
    enum class Type { COLD_ENGINE_REVVING, SPEEDING }
    data class Event(val type: Type, val startMs: Long, val endMs: Long, val peakValue: Double)
    private val audit = mutableListOf<Event>()
    private val active = mutableSetOf<Type>()
    private var enabled = false
    private var lastTime: Long? = null

    /** Starting a new session explicitly discards the old audit. */
    fun start() { audit.clear(); active.clear(); lastTime = null; enabled = true }
    fun stop(): List<Event> { enabled = false; active.clear(); return events() }
    fun events(): List<Event> = audit.toList()

    fun update(timeMs: Long, rpm: Double?, coolantC: Double?, speedKmh: Double?): List<Event> {
        if (!enabled || timeMs < 0 || lastTime?.let { timeMs <= it } == true) return events()
        if (lastTime?.let { timeMs - it > 5000 } == true) active.clear()
        lastTime = timeMs
        fun observe(type: Type, violates: Boolean?, value: Double?) {
            if (violates == null) { active.remove(type); return }
            if (!violates) { active.remove(type); return }
            if (type in active) {
                val index = audit.indexOfLast { it.type == type }
                if (index >= 0) audit[index] = audit[index].copy(endMs = timeMs, peakValue = maxOf(audit[index].peakValue, value!!))
                else active.remove(type)
            }
            if (type !in active) {
                audit.add(Event(type, timeMs, timeMs, value!!))
                active.add(type)
                if (audit.size > maxEvents) audit.removeAt(0)
            }
        }
        val validEngine = rpm != null && rpm.isFinite() && rpm in 0.0..8000.0 &&
            coolantC != null && coolantC.isFinite() && coolantC in -40.0..150.0
        observe(Type.COLD_ENGINE_REVVING, if (validEngine) rpm!! > 3200.0 && coolantC!! < 70.0 else null, rpm)
        observe(Type.SPEEDING, speedKmh?.takeIf { it.isFinite() && it in 0.0..250.0 }?.let { it > 55.0 }, speedKmh)
        return events()
    }
}
