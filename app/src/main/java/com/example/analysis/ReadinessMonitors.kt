package com.example.analysis

/**
 * SAE J1979 I/M readiness monitors - the self-tests the ECU runs on its own emission systems,
 * and the one screen every inspection scan tool shows (owner gap analysis 2026-09-19: PUC /
 * pollution-certificate prep). PIDs 0141 (this drive cycle) and 0101 (since codes were cleared)
 * share the four-byte layout; both were already in the catalogue as raw bitmasks with no decoder
 * and no UI, so the car's answer was unreachable.
 *
 * Layout (J1979): byte A bit0 = MIL on, bits1-3 = confirmed DTC count; every monitor then owns
 * one "supported" bit and one "complete" bit - B/C/D, low nibble supported, high nibble complete.
 * A monitor the car does not fit reports supported = 0 and is excluded from the READY verdict.
 */
object ReadinessMonitors {

    data class Monitor(
        val name: String,
        /** Continuous monitors run all the time; non-continuous need a specific drive phase. */
        val continuous: Boolean,
        val supported: Boolean,
        val complete: Boolean
    )

    data class Status(
        val milOn: Boolean,
        val confirmedDtcCount: Int,
        val monitors: List<Monitor>
    ) {
        val supportedCount: Int get() = monitors.count { it.supported }

        /** Inspection verdict: every fitted monitor finished its test, MIL off. */
        val ready: Boolean get() = !milOn && monitors.none { it.supported && !it.complete }
    }

    fun decode(a: Int, b: Int, c: Int, d: Int): Status {
        fun bit(x: Int, i: Int) = (x shr i) and 1 == 1
        return Status(
            milOn = bit(a, 0),
            confirmedDtcCount = (a shr 1) and 0x07,
            monitors = listOf(
                Monitor("Misfire", true, bit(b, 0), bit(b, 4)),
                Monitor("Fuel system", true, bit(b, 1), bit(b, 5)),
                Monitor("Comprehensive components", true, bit(b, 2), bit(b, 6)),
                Monitor("Catalyst", false, bit(c, 0), bit(c, 4)),
                Monitor("Heated catalyst", false, bit(c, 1), bit(c, 5)),
                Monitor("Evaporative system", false, bit(c, 2), bit(c, 6)),
                Monitor("Oxygen sensor", false, bit(c, 3), bit(c, 7)),
                Monitor("O2 sensor heater", false, bit(d, 0), bit(d, 4)),
                Monitor("A/C refrigerant", false, bit(d, 1), bit(d, 5)),
                Monitor("Secondary air", false, bit(d, 2), bit(d, 6)),
                Monitor("EGR / VVT", false, bit(d, 3), bit(d, 7))
            )
        )
    }

    /**
     * "41 41 00 77 BF 89" -> Status for pid "41". Null when the line is not a monitor answer
     * (NO DATA, UNSUPPORTED, a different PID) - the UI then says so instead of guessing.
     */
    fun fromResponseLine(line: String, pid: String): Status? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 6) return null
        if (!t[0].equals("41", ignoreCase = true) || !t[1].equals(pid, ignoreCase = true)) return null
        val bytes = t.subList(2, 6).map { it.toIntOrNull(16) ?: return null }
        return decode(bytes[0], bytes[1], bytes[2], bytes[3])
    }
}
