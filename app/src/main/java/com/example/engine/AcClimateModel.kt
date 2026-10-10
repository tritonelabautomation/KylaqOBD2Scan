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
 * HARDWARE EVIDENCE (2026-09-13, owner photo of the compressor nameplate +
 * VAG parts-catalogue cross-reference, see docs/reference/kylaq-ac-compressor-hardware.md):
 *  * part 2QD 820 803 (C) - VW Group catalogue title: "A/c compressor WITH
 *    ELECTRO-MAGNETIC COUPLING", i.e. an electromagnetic CLUTCH is fitted;
 *  * compressor type "7VL" (mfr. no. FM10S14C/G on the label): 7-cylinder
 *    VARIABLE-displacement unit (Sanden SD7V family lineage), 6PK pulley
 *    Ø 110-115 mm, made in China;
 *  * consequences this model honours:
 *     - clutch OPEN (AC OFF) => the pulley free-wheels => ZERO belt drag, so the
 *       OFF tag prices at exactly 0.0 kW (not a clutchless minimum-displacement
 *       parasitic floor);
 *     - variable displacement => absorbed power follows COOLING DEMAND (delta-T),
 *       not engine rpm - which is exactly the shape of [compressorLoadKw];
 *     - VW load management drops the clutch at wide-open throttle/kickdown; ride
 *       level AC tags average over whole rides so this needs no term here;
 *     - [AUTO_MODULATION] stays a DOCUMENTED ASSUMPTION: without comfort-CAN
 *       access the app cannot observe the control-valve duty AUTO commands.
 *
 * Added 2026-09-09 per owner request, purely additive: no existing engine,
 * scheduler or recorder logic was modified.
 */
object AcClimateModel {

    // ---- hardware identity (owner nameplate photo 2026-09-13 + VAG catalogue) ----
    const val HW_PART_NUMBER = "2QD 820 803"
    const val HW_COMPRESSOR_TYPE = "7VL variable-displacement (7-cyl, FM10S14 mfr. no.)"
    const val HW_CLUTCH = "electromagnetic clutch (VW: electro-magnetic coupling)"
    const val HW_PULLEY = "6PK, Ø 110-115 mm"

    /** Fan/blower-only electrical load (no compressor). */
    const val BLOWER_KW = 0.15

    /**
     * Compressor base load at zero delta-T: internal friction at minimum swash-plate
     * displacement with the clutch ENGAGED (the clutch itself is on/off, not a load
     * term - with it open the whole compressor prices at 0.0 kW, see HW_CLUTCH).
     */
    const val AC_BASE_KW = 1.1

    /** Extra compressor kW per kelvin of (ambient - setpoint). */
    const val KW_PER_DELTA_C = 0.10

    /**
     * Physical cap for the Kylaq variable-displacement compressor: full swash-plate
     * stroke at high head pressure for a 7VL-class (~140-160 cc/rev) unit.
     */
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

    /**
     * LIVE-DISPLAY GATE (2026-09-14, owner: bedroom screenshots showed 1.43/1.90 kW
     * and +0.53/+0.71 L/h with NO OBD link - pure model output from the assumed
     * delta-T, presented as readings). Without an actual adapter link there is no
     * telemetry behind any climate number, so the display layer must print nothing.
     * Returns null unless [connected]; the un-gated [compressorLoadKw] stays for
     * documented worked examples (Fuel Savings Guide) and tests.
     */
    fun liveCompressorLoadKw(
        connected: Boolean,
        acTag: String,
        autoMode: Boolean,
        ambientC: Double?,
        setTempC: Double?
    ): Double? = if (!connected) null else compressorLoadKw(acTag, autoMode, ambientC, setTempC)

    /** Litres per hour the compressor load costs at [DRIVETRAIN_EFFICIENCY]. */
    fun fuelPenaltyLh(loadKw: Double): Double =
        loadKw / (DRIVETRAIN_EFFICIENCY * FUEL_ENERGY_MJ_PER_L / 3.6)

    /** Penalty as % of a known baseline consumption (null when baseline unknown). */
    fun penaltyPercent(penaltyLh: Double, baselineLh: Double?): Double? =
        if (baselineLh != null && baselineLh > 0.05) 100.0 * penaltyLh / baselineLh else null
}
