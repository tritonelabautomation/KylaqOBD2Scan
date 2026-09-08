package com.example.engine

/**
 * Physical model of the Climatronic compressor load and its fuel cost.
 *
 * WHY A MODEL: SAE J1979 mode 01 exposes NO PID for compressor state, set
 * temperature or AUTO mode on the EA211/09G platform (the climate bus is a
 * separate comfort-CAN segment the ELM327 OBD channel never sees). The only
 * climate signal available over OBD is ambient air temperature, PID 0146,
 * which IS polled by default. Everything else is owner-tagged (AcTag) and
 * converted to kW / L-h here so the dashboard can teach AC behaviour.
 *
 * Added 2026-09-09 per owner request, purely additive: no existing engine,
 * scheduler or recorder logic was modified.
 */
object AcClimateModel {

    /** Fan/blower-only electrical load (no compressor). */
    const val BLOWER_KW = 0.15

    /** Compressor base load at zero delta-T (evaporator + clutch). */
    const val AC_BASE_KW = 1.1

    /** Extra compressor kW per kelvin of (ambient - setpoint). */
    const val KW_PER_DELTA_C = 0.10

    /** Physical cap for the Kylaq variable-displacement compressor. */
    const val AC_MAX_KW = 4.0

    /** AUTO mode modulates the compressor instead of running it flat out. */
    const val AUTO_MODULATION = 0.75

    /** Assumed delta-T when ambient is unknown (hot-day default). */
    const val FALLBACK_DELTA_C = 8.0

    /** Belt-drive -> wheel efficiency used to price compressor kW in fuel. */
    const val DRIVETRAIN_EFFICIENCY = 0.30

    const val FUEL_ENERGY_MJ_PER_L = 32.3

    /** ambient - setpoint, positive means the cabin must be cooled. */
    fun deltaC(ambientC: Double?, setTempC: Double?): Double? =
        if (ambientC != null && setTempC != null) ambientC - setTempC else null

    /** Estimated compressor+blower electrical/mechanical load in kW. */
    fun compressorLoadKw(
        acTag: String,
        autoMode: Boolean,
        ambientC: Double?,
        setTempC: Double?
    ): Double = when (acTag) {
        "AC" -> {
            val delta = (deltaC(ambientC, setTempC) ?: FALLBACK_DELTA_C).coerceAtLeast(0.0)
            val flat = (AC_BASE_KW + delta * KW_PER_DELTA_C).coerceAtMost(AC_MAX_KW)
            if (autoMode) flat * AUTO_MODULATION else flat
        }
        "BLOWER" -> BLOWER_KW
        else -> 0.0
    }

    /** Litres per hour the compressor load costs at [DRIVETRAIN_EFFICIENCY]. */
    fun fuelPenaltyLh(loadKw: Double): Double =
        loadKw / (DRIVETRAIN_EFFICIENCY * FUEL_ENERGY_MJ_PER_L / 3.6)

    /** Penalty as % of a known baseline consumption (null when baseline unknown). */
    fun penaltyPercent(penaltyLh: Double, baselineLh: Double?): Double? =
        if (baselineLh != null && baselineLh > 0.05) 100.0 * penaltyLh / baselineLh else null
}
