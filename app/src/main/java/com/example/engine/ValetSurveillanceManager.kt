package com.example.engine

import java.util.Locale

/**
 * Valet surveillance: cold-revving & speed audit while someone else has the keys.
 *
 * Arm when handing the car over. While armed, every telemetry tick is audited for:
 *
 *  * **Cold revving** — coolant still below [coldCoolantC] and RPM above [coldRevRpm]
 *    (EA211 oil pressure / turbo bearing abuse);
 *  * **Speed cap** — road speed above [speedLimitKmh] (default 80 km/h valet cap);
 *  * **High RPM** — even when warm, RPM above [highRpm] is logged.
 *
 * Events are debounced so a 2-second spike is one row, not twenty. Disarm returns the
 * session snapshot (max speed / max RPM / counts / clean flag).
 */
class ValetSurveillanceManager(
    private val coldCoolantC: Double = 60.0,
    private val coldRevRpm: Double = 2500.0,
    private val speedLimitKmh: Double = 80.0,
    private val highRpm: Double = 4500.0,
    private val debounceMs: Long = 2000L,
    private val maxEvents: Int = 80
) {

    enum class Kind { COLD_REV, SPEED, HIGH_RPM }

    data class AuditEvent(
        val timestampMs: Long,
        val kind: Kind,
        val rpm: Double?,
        val speedKmh: Double?,
        val coolantC: Double?,
        val detail: String
    )

    data class Snapshot(
        val armed: Boolean,
        val armedAtMs: Long?,
        val maxSpeedKmh: Double,
        val maxRpm: Double,
        val coldRevCount: Int,
        val overspeedCount: Int,
        val highRpmCount: Int,
        val events: List<AuditEvent>,
        val clean: Boolean,
        val message: String
    )

    private var armed: Boolean = false
    private var armedAtMs: Long? = null
    private var maxSpeedKmh: Double = 0.0
    private var maxRpm: Double = 0.0
    private var coldRevCount: Int = 0
    private var overspeedCount: Int = 0
    private var highRpmCount: Int = 0
    private val events = ArrayDeque<AuditEvent>()
    private val lastKindMs = HashMap<Kind, Long>()

    fun reset(keepArmed: Boolean = false) {
        if (!keepArmed) {
            armed = false
            armedAtMs = null
        }
        maxSpeedKmh = 0.0
        maxRpm = 0.0
        coldRevCount = 0
        overspeedCount = 0
        highRpmCount = 0
        events.clear()
        lastKindMs.clear()
    }

    fun arm(nowMs: Long): Snapshot {
        reset(keepArmed = false)
        armed = true
        armedAtMs = nowMs
        return snapshot()
    }

    fun disarm(): Snapshot {
        armed = false
        return snapshot()
    }

    fun isArmed(): Boolean = armed

    fun onSample(
        timestampMonotonicMs: Long,
        speedKmh: Double?,
        rpm: Double?,
        coolantC: Double? = null
    ): Snapshot {
        if (!armed) return snapshot()

        val speed = speedKmh ?: 0.0
        val engineRpm = rpm ?: 0.0
        if (speed > maxSpeedKmh) maxSpeedKmh = speed
        if (engineRpm > maxRpm) maxRpm = engineRpm

        val cold = coolantC != null && coolantC < coldCoolantC
        if (cold && engineRpm >= coldRevRpm) {
            emit(
                timestampMonotonicMs, Kind.COLD_REV, rpm, speedKmh, coolantC,
                String.format(
                    Locale.US,
                    "Cold rev %.0f rpm at coolant %.0f°C (limit %.0f rpm below %.0f°C).",
                    engineRpm, coolantC ?: 0.0, coldRevRpm, coldCoolantC
                )
            ) { coldRevCount++ }
        }
        if (speed > speedLimitKmh) {
            emit(
                timestampMonotonicMs, Kind.SPEED, rpm, speedKmh, coolantC,
                String.format(
                    Locale.US,
                    "Speed %.0f km/h exceeds valet cap %.0f km/h.",
                    speed, speedLimitKmh
                )
            ) { overspeedCount++ }
        }
        if (!cold && engineRpm >= highRpm) {
            emit(
                timestampMonotonicMs, Kind.HIGH_RPM, rpm, speedKmh, coolantC,
                String.format(Locale.US, "High RPM %.0f (audit ≥ %.0f).", engineRpm, highRpm)
            ) { highRpmCount++ }
        }
        return snapshot()
    }

    private fun emit(
        ts: Long,
        kind: Kind,
        rpm: Double?,
        speed: Double?,
        coolant: Double?,
        detail: String,
        onNew: () -> Unit
    ) {
        val last = lastKindMs[kind]
        if (last != null && ts - last < debounceMs) return
        lastKindMs[kind] = ts
        onNew()
        events.addLast(
            AuditEvent(
                timestampMs = ts,
                kind = kind,
                rpm = rpm,
                speedKmh = speed,
                coolantC = coolant,
                detail = detail
            )
        )
        while (events.size > maxEvents) events.removeFirst()
    }

    private fun snapshot(): Snapshot {
        val clean = coldRevCount == 0 && overspeedCount == 0 && highRpmCount == 0
        val message = when {
            !armed && armedAtMs == null -> "Valet surveillance disarmed."
            !armed && clean -> String.format(
                Locale.US,
                "Valet returned clean — max %.0f km/h / %.0f rpm.",
                maxSpeedKmh, maxRpm
            )
            !armed -> String.format(
                Locale.US,
                "Valet audit: %d cold-rev, %d overspeed, %d high-rpm (max %.0f km/h / %.0f rpm).",
                coldRevCount, overspeedCount, highRpmCount, maxSpeedKmh, maxRpm
            )
            clean -> String.format(
                Locale.US,
                "Valet armed — watching cold revs (>%.0f rpm below %.0f°C) and %.0f km/h cap.",
                coldRevRpm, coldCoolantC, speedLimitKmh
            )
            else -> String.format(
                Locale.US,
                "Valet LIVE: %d cold-rev, %d overspeed, %d high-rpm (max %.0f km/h / %.0f rpm).",
                coldRevCount, overspeedCount, highRpmCount, maxSpeedKmh, maxRpm
            )
        }
        return Snapshot(
            armed = armed,
            armedAtMs = armedAtMs,
            maxSpeedKmh = maxSpeedKmh,
            maxRpm = maxRpm,
            coldRevCount = coldRevCount,
            overspeedCount = overspeedCount,
            highRpmCount = highRpmCount,
            events = events.toList(),
            clean = clean,
            message = message
        )
    }
}
