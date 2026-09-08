package com.example.analysis

/**
 * Per-ride driver-behaviour recorder ("ride X-ray"): accumulates pedal/brake/coast/neutral state
 * seconds (from DrivingStateEngine), per-gear seconds and shift events (from the 6-AT gear
 * estimate, with an rpm-drop fallback), plus elevation gain/loss from GPS altitude. Everything
 * the owner asked for - "brake pressed, accelerator pressed, nothing pressed, neutral coasting"
 * - quantified per ride and durable across restarts via [RideCodec].
 */
class RideBehaviorRecorder {

    enum class ModeTag { D, S, M }

    data class ShiftEvent(
        val gearFrom: Int?,
        val gearTo: Int?,
        val rpmAtShift: Double,
        val durationMs: Long
    )

    data class RideSummary(
        val dateUtc: String,
        val durationSec: Double,
        val distanceKm: Double,
        val stateSeconds: Map<String, Double>,
        val gearSeconds: List<Double>,
        val elevationGainM: Double,
        val elevationLossM: Double,
        val shiftCount: Int,
        val avgUpshiftRpm: Double?,
        val maxUpshiftRpm: Double?,
        val avgShiftDurationMs: Long?,
        val modeTag: String,
        val converterSlipSec: Double = 0.0,
        val converterLockedSec: Double = 0.0,
        val maxSlipRpm: Double? = null
    ) {
        /** Fraction of measured drivetrain time spent with the converter NOT locked. */
        val converterSlipShare: Double
            get() {
                val total = converterSlipSec + converterLockedSec
                return if (total > 0.0) converterSlipSec / total else 0.0
            }

        /** The Aisin D-map shifts ~2.0-2.5k rpm; S/M hold past ~3k. Detects "prolonged" sport shifts. */
        val sportLikeShiftMap: Boolean get() = (avgUpshiftRpm ?: 0.0) > 3000.0

        fun stateShare(name: String): Double =
            if (durationSec > 0) (stateSeconds[name] ?: 0.0) / durationSec else 0.0
    }

    var modeTag: ModeTag = ModeTag.D

    private val stateSeconds = mutableMapOf<String, Double>()
    private val gearSeconds = DoubleArray(7)
    private var elevationGainM = 0.0
    private var elevationLossM = 0.0
    private var lastAltitudeM: Double? = null
    // Nullable timestamps: tsMs == 0 is a LEGITIMATE first sample (relative clocks),
    // so 0L cannot double as "no sample yet" without swallowing the first interval.
    private var firstTsMs: Long? = null
    private var lastTsMs: Long? = null
    private var lastStateName: String? = null
    private var lastSpeedKmh: Double? = null
    private var distanceKm = 0.0
    private var lastGear: Int? = null
    private var lastGearTsMs = 0L
    private val shifts = mutableListOf<ShiftEvent>()
    private var lastRpm: Double? = null
    private var lastRpmTsMs = 0L
    private var pendingShiftRpm: Double? = null
    private var pendingShiftTsMs = 0L
    // Torque-converter health: expected-vs-actual deviation seconds (from TransmissionEngine).
    private var converterSlipSec = 0.0
    private var converterLockedSec = 0.0
    private var maxSlipRpm: Double? = null
    private var lastSlipRpm: Double? = null
    private var lastLocked: Boolean? = null

    fun reset(tag: ModeTag = modeTag) {
        modeTag = tag
        stateSeconds.clear()
        gearSeconds.fill(0.0)
        elevationGainM = 0.0
        elevationLossM = 0.0
        lastAltitudeM = null
        firstTsMs = null
        lastTsMs = null
        lastStateName = null
        lastSpeedKmh = null
        distanceKm = 0.0
        lastGear = null
        lastGearTsMs = 0L
        shifts.clear()
        lastRpm = null
        lastRpmTsMs = 0L
        pendingShiftRpm = null
        pendingShiftTsMs = 0L
        converterSlipSec = 0.0
        converterLockedSec = 0.0
        maxSlipRpm = null
        lastSlipRpm = null
        lastLocked = null
    }

    fun onSample(
        tsMs: Long,
        stateName: String,
        rpm: Double?,
        speedKmh: Double?,
        gear: Int?,
        altitudeM: Double?,
        slipRpm: Double? = null,
        converterLocked: Boolean? = null
    ) {
        val prevTs = lastTsMs
        val dt = if (prevTs != null) ((tsMs - prevTs) / 1000.0).coerceIn(0.0, 5.0) else 0.0
        if (firstTsMs == null) firstTsMs = tsMs

        if (dt > 0.0) {
            // Credit the elapsed interval to the PREVIOUS sample's state/gear/speed: the
            // seconds between t-1 and t were spent in whatever was observed at t-1.
            lastStateName?.let { stateSeconds[it] = (stateSeconds[it] ?: 0.0) + dt }
            lastSpeedKmh?.let { distanceKm += it * dt / 3600.0 }
            lastGear?.takeIf { it in 1..6 }?.let { gearSeconds[it] += dt }
            // Converter health: seconds the drivetrain ran locked vs slipping (previous sample).
            lastLocked?.let { locked ->
                if (locked) {
                    converterLockedSec += dt
                } else {
                    converterSlipSec += dt
                    lastSlipRpm?.takeIf { it > 0.0 }?.let { s ->
                        maxSlipRpm = maxOf(maxSlipRpm ?: s, s)
                    }
                }
            }
        }
        lastTsMs = tsMs
        lastStateName = stateName
        lastSpeedKmh = speedKmh

        val g = gear?.takeIf { it in 1..6 }
        if (g != null) {
            if (lastGear != null && g != lastGear) {
                // rpm BEFORE the drop is the shift point the TCU chose (D vs S vs M evidence).
                shifts.add(ShiftEvent(lastGear, g, lastRpm ?: rpm ?: 0.0, tsMs - lastGearTsMs))
                pendingShiftRpm = null
            }
            if (g != lastGear) lastGearTsMs = tsMs
            lastGear = g
        } else if (rpm != null && lastRpm != null && tsMs - lastRpmTsMs < 2000L) {
            // Fallback: torque-converter upshows as a 350-1500 rpm drop at (nearly) steady speed.
            val drop = lastRpm!! - rpm
            if (pendingShiftRpm == null && drop in 350.0..1500.0) {
                pendingShiftRpm = lastRpm
                pendingShiftTsMs = lastRpmTsMs
            } else if (pendingShiftRpm != null && drop < 100.0 && rpm > 800.0) {
                shifts.add(ShiftEvent(null, null, pendingShiftRpm!!, tsMs - pendingShiftTsMs))
                pendingShiftRpm = null
            }
        }

        altitudeM?.let { alt ->
            lastAltitudeM?.let { prev ->
                val d = alt - prev
                // 1 m noise gate: GPS altitude jitters more than real gradients per sample.
                if (d > 1.0) elevationGainM += d else if (d < -1.0) elevationLossM += -d
            }
            lastAltitudeM = alt
        }

        lastRpm = rpm
        lastRpmTsMs = tsMs
        lastSlipRpm = slipRpm
        lastLocked = converterLocked
    }

    fun summary(dateUtc: String): RideSummary {
        val upshiftRpms = shifts.map { it.rpmAtShift }.filter { it > 500.0 }
        return RideSummary(
            dateUtc = dateUtc,
            durationSec = firstTsMs?.let { f -> lastTsMs?.let { l -> if (l > f) (l - f) / 1000.0 else 0.0 } } ?: 0.0,
            distanceKm = distanceKm,
            stateSeconds = stateSeconds.toMap(),
            gearSeconds = gearSeconds.toList(),
            elevationGainM = elevationGainM,
            elevationLossM = elevationLossM,
            shiftCount = shifts.size,
            avgUpshiftRpm = upshiftRpms.takeIf { it.isNotEmpty() }?.average(),
            maxUpshiftRpm = upshiftRpms.maxOrNull(),
            avgShiftDurationMs = shifts.map { it.durationMs }.takeIf { it.isNotEmpty() }
                ?.average()?.toLong(),
            modeTag = modeTag.name,
            converterSlipSec = converterSlipSec,
            converterLockedSec = converterLockedSec,
            maxSlipRpm = maxSlipRpm
        )
    }
}

/** Durable one-line encoding of a ride X-ray (settings "ride_log"). */
object RideCodec {

    fun encode(s: RideBehaviorRecorder.RideSummary): String {
        val states = s.stateSeconds.entries.joinToString(",") { "${it.key}=%.0f".format(it.value) }
        val gears = s.gearSeconds.drop(1).joinToString(",") { "%.0f".format(it) }
        return listOf(
            "r2",
            com.example.data.ExpenseCodec.esc(s.dateUtc),
            "%.0f".format(s.durationSec),
            "%.2f".format(s.distanceKm),
            com.example.data.ExpenseCodec.esc(states),
            gears,
            "%.0f".format(s.elevationGainM),
            "%.0f".format(s.elevationLossM),
            s.shiftCount.toString(),
            s.avgUpshiftRpm?.let { "%.0f".format(it) } ?: "-",
            s.maxUpshiftRpm?.let { "%.0f".format(it) } ?: "-",
            s.avgShiftDurationMs?.toString() ?: "-",
            s.modeTag,
            "%.0f".format(s.converterSlipSec),
            "%.0f".format(s.converterLockedSec),
            s.maxSlipRpm?.let { "%.0f".format(it) } ?: "-"
        ).joinToString("|")
    }

    fun decode(line: String): RideBehaviorRecorder.RideSummary? {
        val p = line.split('|')
        // "r2" (16 fields) carries converter health; legacy "r1" (13 fields) still decodes.
        val isR2 = p.size == 16 && p[0] == "r2"
        val isR1 = p.size == 13 && p[0] == "r1"
        if (!isR2 && !isR1) return null
        return try {
            val states = com.example.data.ExpenseCodec.unesc(p[4])
                .split(',')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to (it.substringAfter('=').toDoubleOrNull() ?: 0.0) }
            // encode() drops index 0, so restore the dummy 0th entry at the FRONT.
            val gears = p[5].split(',').mapNotNull { it.toDoubleOrNull() }.toMutableList()
            while (gears.size < 6) gears.add(0.0)
            gears.add(0, 0.0)
            RideBehaviorRecorder.RideSummary(
                dateUtc = com.example.data.ExpenseCodec.unesc(p[1]),
                durationSec = p[2].toDoubleOrNull() ?: 0.0,
                distanceKm = p[3].toDoubleOrNull() ?: 0.0,
                stateSeconds = states,
                gearSeconds = gears.take(7),
                elevationGainM = p[6].toDoubleOrNull() ?: 0.0,
                elevationLossM = p[7].toDoubleOrNull() ?: 0.0,
                shiftCount = p[8].toIntOrNull() ?: 0,
                avgUpshiftRpm = p[9].toDoubleOrNull(),
                maxUpshiftRpm = p[10].toDoubleOrNull(),
                avgShiftDurationMs = p[11].toLongOrNull(),
                modeTag = p[12],
                converterSlipSec = if (isR2) p[13].toDoubleOrNull() ?: 0.0 else 0.0,
                converterLockedSec = if (isR2) p[14].toDoubleOrNull() ?: 0.0 else 0.0,
                maxSlipRpm = if (isR2) p[15].toDoubleOrNull() else null
            )
        } catch (e: Exception) {
            null
        }
    }
}
