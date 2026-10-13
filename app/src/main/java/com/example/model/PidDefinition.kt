package com.example.model

/**
 * Strategy for decoding response
 */
enum class DecoderType {
    PERCENT_255,          // A * 100 / 255
    TEMP_MINUS_40,        // A - 40
    FUEL_TRIM,            // (A - 128) * 100 / 128
    RAW_A_KPA,            // A (kPa)
    RPM_FORMULA,          // ((A * 256) + B) / 4
    RAW_A_KMH,            // A (km/h)
    TIMING_ADVANCE,       // A / 2 - 64
    PERCENT_EVAP,         // A * 100 / 255
    VOLTAGE_1000,         // ((A * 256) + B) / 1000
    PERCENT_LOAD_255,     // ((A * 256) + B) / 2.55
    EQUIVALENCE_RATIO,    // ((A * 256) + B) / 32768
    CATALYST_TEMP,        // ((A * 256) + B) / 10 - 40
    FUEL_TYPE_ENUM,       // Enum lookup
    FUEL_SYSTEM_STATUS,   // Enum lookup
    FUEL_RAIL_PRESSURE,   // ((A * 256) + B) * 10
    TORQUE_PCT,           // A - 125
    TORQUE_NM,            // A * 256 + B
    FUEL_RATE_20,         // ((A * 256) + B) / 20.0 (Volume: L/h) - PID 015E
    FUEL_RATE_MASS_50,    // ((A * 256) + B) / 50.0 (Mass: g/s, 0.02/bit) - PID 019D, real-car calibrated 2026-09-13
    FUEL_PRESSURE_3_KPA,  // A * 3 (kPa gauge) - PID 010A
    MAF_100,              // ((A * 256) + B) / 100.0 (Air Flow: g/s) - PID 0110
    INJECTION_TIMING_128, // ((((A * 256) + B) - 26880) / 128.0) ° - PID 015D
    TRANSMISSION_GEAR_A4, // SAE J1979 PID 01A4 Actual Gear Ratio / Status
    LAMBDA_2B,            // ((A*256)+B)/32768 lambda, two bytes - J1979 PID 24-2B / 34-3B lambda half
    O2_TRIM_PAIR_2B,      // (A-128)*100/128 % and (B-128)*100/128 % - J1979 PID 55-58 secondary O2 trims
    ODOMETER_4B,          // ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km - J1979 PID A6 (car answered 00 00 86 EB = 3453.9 km)
    STEERING_ANGLE_SIGNED_10, // (Signed16(A, B)) / 10.0 (°): -720.0° to +720.0° - J1979-2 PID B5 / UDS DID 0200
    SIGNED_16_DIV_10,         // Signed16(A, B) / 10.0
    SIGNED_16_DIV_100,        // Signed16(A, B) / 100.0
    SIGNED_16_RAW,            // Signed16(A, B)
    PRESSURE_HPA_16,          // ((A * 256) + B) (hPa)
    MISFIRE_COUNT_16,         // (A * 256) + B
    KNOCK_RETARD_DIV_10,      // Signed16(A, B) / 10.0 (°CA)
    PRESSURE_BAR_10,          // ((A * 256) + B) / 10.0 (bar)
    PRESSURE_BAR_100,         // ((A * 256) + B) / 100.0 (bar)
    WHEEL_SPEED_100,          // ((A * 256) + B) / 100.0 (km/h)
    BATTERY_SOC_PCT,          // A * 100 / 255.0 (%)
    RESEARCH_RAW,             // Preserve raw bytes without calculation
    CUSTOM_EXPRESSION         // Custom expression if user defined
}

/**
 * Priority polling tier to prevent bus overload
 */
enum class PollingPriority(val floorMs: Long) {
    FAST(100L),   // 100-250ms: RPM, Speed, Throttle, Accelerator, Torque
    MEDIUM(400L), // 400-800ms: Fuel rates, MAP, MAF, Load, Coolant, Rail Pressure
    SLOW(2000L);  // 2000-5000ms: Fuel type, Ethanol %, Fuel level, Baro, Voltage, Ambient

    /**
     * Minimum poll interval for this tier (2026-09-13 root-cause fix, owner:
     * "AC/compressor related PIDs not updating correct"). 114 of 153 catalogue
     * entries never set an explicit interval and inherited the 250 ms constructor
     * default - so every validated SLOW PID (ambient 0146, trims, tank level, ...)
     * came due on EVERY serial round-robin pass. Since 2026-09-14 the interval is a
     * REQUIRED constructor parameter and all 153 catalogue entries declare one
     * explicitly (FAST 100-250 ms, MEDIUM 400-800 ms, SLOW 2000-5000 ms); this
     * floor remains as the runtime safety clamp. That inflated the cycle time,
     * burned ELM327 bandwidth on queries whose value cannot change that fast,
     * raised collision/timeout rates and made the whole board - AC tiles
     * included - update in lurches. The scheduler now clamps every interval to
     * at least the tier floor documented above.
     */
}

/**
 * Data-driven PID configuration item
 */
data class PidDefinition(
    val id: String,                         // e.g. "010C"
    val service: String,                    // e.g. "01"
    val pid: String,                        // e.g. "0C"
    val name: String,                       // e.g. "Engine RPM"
    val shortName: String,                  // e.g. "RPM"
    val unit: String,                       // e.g. "RPM"
    val canHeader: String = "7DF",          // Default functional broadcast, or "7E0" for ECM direct
    val expectedRxId: String = "7E8",       // Expected ECM CAN response ID
    // REQUIRED (no default since 2026-09-14, owner: "enabled PIDs have no explicit
    // polling interval"): every PID must declare its own poll interval so nothing can
    // silently inherit a constructor value again.
    val defaultIntervalMs: Long,
    val enabled: Boolean = true,
    val decoderType: DecoderType = DecoderType.RESEARCH_RAW,
    val formulaDisplay: String = "",
    val isResearch: Boolean = false,
    val description: String = "",
    val priority: PollingPriority = PollingPriority.MEDIUM,
    val hexPid: String = pid.uppercase(),
    val mode: String = service,
    val dataBytes: Int = 1,
    val supported: Boolean = false,
    val decoder: String = decoderType.name
) {
    /** Service acknowledgement byte of a positive response (service 0x01 -> 0x41). */
    val ackByte: Int? get() = service.trim().toIntOrNull(16)?.or(0x40)

    /** Numeric value of the requested [pid] (hex), or null when the request has no PID byte. */
    val pidValue: Int? get() = pid.trim().toIntOrNull(16)

    /**
     * True when [responseBytes] is a positive mode-01 response that echoes a *different*
     * PID than the one this definition requests.
     *
     * Why this matters: the ELM327 can deliver the answer to the previous request while the
     * current one is still being read. The Kylaq reference trace shows coolant frames
     * (`7E90341056F`, `7E80341056F`) landing inside a `010C` read window. Decoding those as
     * RPM yields INVALID_RESPONSE, which — if published — overwrites the good sample for
     * that ECU and blanks the dashboard. Callers must log and skip such frames instead of
     * feeding them to telemetry or capability promotion.
     *
     * Deliberately restricted to service 01: DTC (03/07) and VIN (09) responses do not echo
     * a PID in byte 2, so the comparison would misfire there.
     */
    fun isLateFrameForOtherPid(responseBytes: List<Int>): Boolean {
        if (service.trim().uppercase() != "01") return false
        val requested = pidValue ?: return false
        val ack = ackByte ?: return false
        val frameService = responseBytes.firstOrNull() ?: return false
        val framePid = responseBytes.getOrNull(1) ?: return false
        if (frameService != ack) return false
        return framePid != requested
    }
}

/**
 * Reconciles a PERSISTED PID definition with the shipped catalogue.
 *
 * `pid_definitions_json` is written on every settings change and used to be loaded back
 * verbatim, so a device that had run the app once kept the definitions it saved forever.
 * Every catalogue correction made after that point - a wrong J1979 name, a decoder that
 * printed a capability bitmap as a temperature, a research channel later proven to be the
 * odometer - silently never reached an installed app. The only escape was "Reset to
 * defaults", which also throws away the owner's CAN header, RX id and enable choices.
 * The 2026-09-17 J1979 correction pass is worthless on an upgraded device without this.
 *
 * Split of ownership:
 *  - the CATALOGUE owns what a PID *is*: name, shortName, unit, dataBytes, decoderType,
 *    formulaDisplay, isResearch, description, priority. It is code, it is unit-tested, and it
 *    is the only place a J1979 correction can be made.
 *  - the USER owns how it is *polled*: enabled, canHeader, expectedRxId, defaultIntervalMs.
 *  - exception: an entry the catalogue disables (the six range markers 00/20/40/60/80/A0 and
 *    every channel whose meaning is not established) cannot be switched back on, because the
 *    toggle would re-publish a bitmap or a guess as a measurement.
 *
 * PIDs the catalogue does not know (user-added custom entries) are returned untouched.
 */
object PidDefinitionReconciler {

    fun reconcile(saved: PidDefinition): PidDefinition {
        val known = StandardPidCatalog.getAllKnownPids()
            .firstOrNull { it.hexPid == saved.hexPid } ?: return saved
        return saved.copy(
            name = known.name,
            shortName = known.shortName,
            unit = known.unit,
            dataBytes = known.dataBytes,
            decoderType = known.decoderType,
            formulaDisplay = known.formulaDisplay,
            isResearch = known.isResearch,
            description = known.description,
            priority = known.priority,
            // the catalogue's polling band, but never faster than the user asked for
            defaultIntervalMs = maxOf(saved.defaultIntervalMs, known.priority.floorMs),
            enabled = saved.enabled && known.enabled
        )
    }
}

/**
 * Channels this specific car has PROVEN it answers, defined once and referenced from both the
 * shipped defaults (so they are polled) and the J1979 catalogue (so lookup() resolves them).
 *
 * Keeping them here rather than duplicating the definition is what stopped the catalogue from
 * carrying two entries for the same PID - ten of which existed before 2026-09-17, with the
 * later one silently winning the map.
 */
object ProvenChannels {

    /**
     * J1979 PID A6 = ODOMETER, ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km.
     * The 2026-09-16 discovery run on VIN MEXKPEPC2TG028855 recorded `41 A6 00 00 86 EB`
     * while the app printed "Unknown Research PID 01A6". 34539 / 10 = 3453.9 km, and the car
     * claims the PID in its 01A0 bitmap `14 00 00 00` (which sets A4 and A6 only).
     */
    val ODOMETER = PidDefinition(
        id = "01A6",
        service = "01",
        pid = "A6",
        name = "Odometer",
        shortName = "Odo",
        unit = "km",
        canHeader = "7DF",
        expectedRxId = "7E8",
        dataBytes = 4,
        decoderType = DecoderType.ODOMETER_4B,
        formulaDisplay = "((A * 2^24) + (B * 2^16) + (C * 2^8) + D) / 10",
        isResearch = false,
        enabled = true,
        description = "SAE J1979 PID A6 is the ODOMETER in km at 0.1 km resolution. The owner run of 2026-09-16 recorded `41 A6 00 00 86 EB` and the app printed 'Unknown Research PID 01A6'; the frame is 34539 / 10 = 3453.9 km. The car claims it in the 01A0 bitmap `14 00 00 00`, so it is a live channel, not research. The VIN is not in Mode 01 at all - it is Mode 09 PID 0902 - and a duplicate catalogue entry used to mislabel this PID as a partial VIN.",
        priority = PollingPriority.SLOW,
        defaultIntervalMs = 3000L
    )
}

object DefaultPidDefinitions {
    fun getDefaults(): List<PidDefinition> {
        return listOf(
            // Restored 2026-09-17: these nine entries were deleted in a "duplicate id"
            // cleanup that misread the catalogue. DefaultPidDefinitions.getDefaults() is the
            // list the scheduler polls, the dashboard fuel tiles resolve by `id` and
            // FuelPidLineageTest asserts on; the J1979 "additional" list below happens to use
            // the same four-character ids, so StandardPidCatalog's hexPid-keyed map lets the
            // later entry win. That shadowing is benign and pre-existing - deleting the
            // defaults copy was not: it removed live channels (010A and 0123 are dashboard
            // fuel tiles, 0145 relative throttle is a gear-pairing input) and broke three
            // tests. Kept deliberately, with the polling bands the dashboard was tuned on.
            PidDefinition(
                id = "0107",
                service = "01",
                pid = "07",
                name = "Long Term Fuel Trim Bank 1",
                shortName = "LTFT B1",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 1200L,
                enabled = true,
                decoderType = DecoderType.FUEL_TRIM,
                formulaDisplay = "(A - 128) * 100 / 128",
                description = "Long term adaptive fuel correction",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "010E",
                service = "01",
                pid = "0E",
                name = "Timing Advance",
                shortName = "Timing",
                unit = "\u00B0",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 500L,
                enabled = true,
                decoderType = DecoderType.TIMING_ADVANCE,
                formulaDisplay = "A / 2 - 64",
                description = "Ignition timing advance cylinder 1",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0123",
                service = "01",
                pid = "23",
                name = "Fuel Rail Pressure",
                shortName = "FRP",
                unit = "kPa",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 600L,
                enabled = true,
                decoderType = DecoderType.FUEL_RAIL_PRESSURE,
                formulaDisplay = "((A * 256) + B) * 10",
                description = "Fuel rail direct injection pressure",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "010A",
                service = "01",
                pid = "0A",
                name = "Fuel Pressure",
                shortName = "Fuel Press",
                unit = "kPa",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 1000L,
                enabled = true,
                decoderType = DecoderType.FUEL_PRESSURE_3_KPA,
                formulaDisplay = "A * 3",
                description = "Low-pressure fuel supply gauge pressure",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0144",
                service = "01",
                pid = "44",
                name = "Commanded Equivalence Ratio",
                shortName = "Lambda",
                unit = "\u03BB",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 600L,
                enabled = true,
                decoderType = DecoderType.EQUIVALENCE_RATIO,
                formulaDisplay = "((A * 256) + B) / 32768",
                description = "Target air-fuel equivalence ratio (Lambda)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0143",
                service = "01",
                pid = "43",
                name = "Absolute Load Value",
                shortName = "Abs Load",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 600L,
                enabled = true,
                decoderType = DecoderType.PERCENT_LOAD_255,
                formulaDisplay = "((A * 256) + B) / 2.55",
                description = "Normalized volumetric engine load",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0145",
                service = "01",
                pid = "45",
                name = "Relative Throttle Position",
                shortName = "Rel Throttle",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Relative throttle plate opening - a gear-pairing input",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "015D",
                service = "01",
                pid = "5D",
                name = "Engine Fuel Injection Timing",
                shortName = "Inj Timing",
                unit = "\u00B0",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 600L,
                enabled = true,
                decoderType = DecoderType.INJECTION_TIMING_128,
                formulaDisplay = "(((A * 256) + B) - 26880) / 128.0",
                description = "Start of fuel injection timing relative to TDC",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "012F",
                service = "01",
                pid = "2F",
                name = "Fuel Tank Level Input",
                shortName = "Fuel Level",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 3000L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Nominal fuel tank level percentage",
                priority = PollingPriority.SLOW
            ),
            // ─── 0100 Range marker: PIDs supported [01-20] ────────────────────
            PidDefinition(
                id = "0100", service = "01", pid = "00",
                name = "Mode 01 PID 00 - range marker (PIDs supported 01-20)",
                shortName = "Range 01-20", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 00 is the 32-bit availability bitmap for PID 01 to 20. It is a capability row, never a measurement. The owner run of 2026-09-16 got a garbled stale frame for TX 0100 and the valid 41 00 bitmap arrived one command late, so the whole 0x00 block - rpm, speed, coolant, MAP, throttle, fuel rate, 16 PIDs the app polls on every trip - vanished from a report about the car. PidDiscoveryDecoder.decodeSupportedPids excludes it from the supported list and hasNextRange reports it separately.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // FAST TIER (Dynamic powertrain controls)
            PidDefinition(
                id = "010C",
                service = "01",
                pid = "0C",
                name = "Engine RPM",
                shortName = "RPM",
                unit = "RPM",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.RPM_FORMULA,
                formulaDisplay = "((A * 256) + B) / 4",
                description = "Crankshaft rotational velocity",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "010D",
                service = "01",
                pid = "0D",
                name = "Vehicle Speed",
                shortName = "Speed",
                unit = "km/h",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 200L,
                enabled = true,
                decoderType = DecoderType.RAW_A_KMH,
                formulaDisplay = "A",
                description = "Vehicle wheel speed",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "0111",
                service = "01",
                pid = "11",
                name = "Throttle Position",
                shortName = "Throttle",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 200L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Absolute throttle position",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "0149",
                service = "01",
                pid = "49",
                name = "Accelerator Pedal Position D",
                shortName = "Pedal D",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 200L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Accelerator pedal position sensor D",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "014A",
                service = "01",
                pid = "4A",
                name = "Accelerator Pedal Position E",
                shortName = "Pedal E",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Accelerator pedal position sensor E",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "0162",
                service = "01",
                pid = "62",
                name = "Actual Engine Torque",
                shortName = "Torque %",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.TORQUE_PCT,
                formulaDisplay = "A - 125",
                description = "Actual engine percent torque output",
                priority = PollingPriority.FAST
            ),
            // UDS Service 0x22 Steering Wheel Angle (EPS J500 Module 44 - Method A)
            PidDefinition(
                id = "220200",
                service = "22",
                pid = "0200",
                name = "EPS Steering Wheel Angle (UDS)",
                shortName = "EPS Angle",
                unit = "°",
                canHeader = "714",
                expectedRxId = "77E",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.STEERING_ANGLE_SIGNED_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "UDS Service 0x22 DID 0200 G85 steering wheel angle from Power Steering Module 44 (J500)",
                priority = PollingPriority.FAST
            ),
            // ─── Extended UDS: Engine Module 01 (MED17.1.27) ─────────────────
            PidDefinition(
                id = "220202",
                service = "22",
                pid = "0202",
                name = "Engine Oil Temperature (UDS)",
                shortName = "Oil Temp",
                unit = "°C",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 800L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "UDS Service 0x22 DID 0202 Sump Oil Temperature Sensor G266",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "220203",
                service = "22",
                pid = "0203",
                name = "Specified Boost Pressure (UDS)",
                shortName = "Target Boost",
                unit = "hPa",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_HPA_16,
                formulaDisplay = "(A * 256) + B",
                description = "UDS Service 0x22 DID 0203 Target Charge Air Pressure (IDE00190)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "220204",
                service = "22",
                pid = "0204",
                name = "Actual Boost Pressure (UDS)",
                shortName = "Actual Boost",
                unit = "hPa",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_HPA_16,
                formulaDisplay = "(A * 256) + B",
                description = "UDS Service 0x22 DID 0204 Measured Manifold Boost Pressure (IDE00191)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "220205",
                service = "22",
                pid = "0205",
                name = "Cylinder 1 Misfires (UDS)",
                shortName = "Cyl 1 Misfires",
                unit = "count",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 1000L,
                enabled = true,
                decoderType = DecoderType.MISFIRE_COUNT_16,
                formulaDisplay = "(A * 256) + B",
                description = "UDS Service 0x22 DID 0205 Cylinder 1 Misfire Counter (IDE01962)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "220206",
                service = "22",
                pid = "0206",
                name = "Cylinder 2 Misfires (UDS)",
                shortName = "Cyl 2 Misfires",
                unit = "count",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 1000L,
                enabled = true,
                decoderType = DecoderType.MISFIRE_COUNT_16,
                formulaDisplay = "(A * 256) + B",
                description = "UDS Service 0x22 DID 0206 Cylinder 2 Misfire Counter (IDE01963)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "220207",
                service = "22",
                pid = "0207",
                name = "Cylinder 3 Misfires (UDS)",
                shortName = "Cyl 3 Misfires",
                unit = "count",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 1000L,
                enabled = true,
                decoderType = DecoderType.MISFIRE_COUNT_16,
                formulaDisplay = "(A * 256) + B",
                description = "UDS Service 0x22 DID 0207 Cylinder 3 Misfire Counter (IDE01964 1.0 TSI EA211)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "220208",
                service = "22",
                pid = "0208",
                name = "Cylinder 1 Knock Retard (UDS)",
                shortName = "Cyl 1 Knock",
                unit = "°CA",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.KNOCK_RETARD_DIV_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "UDS Service 0x22 DID 0208 Cylinder 1 Ignition Timing Retardation (IDE01970)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "220209",
                service = "22",
                pid = "0209",
                name = "Cylinder 2 Knock Retard (UDS)",
                shortName = "Cyl 2 Knock",
                unit = "°CA",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.KNOCK_RETARD_DIV_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "UDS Service 0x22 DID 0209 Cylinder 2 Ignition Timing Retardation (IDE01971)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "22020A",
                service = "22",
                pid = "020A",
                name = "Cylinder 3 Knock Retard (UDS)",
                shortName = "Cyl 3 Knock",
                unit = "°CA",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.KNOCK_RETARD_DIV_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "UDS Service 0x22 DID 020A Cylinder 3 Ignition Timing Retardation (IDE01972)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "22020B",
                service = "22",
                pid = "020B",
                name = "Direct Injection Rail Pressure (UDS)",
                shortName = "HP Rail Press",
                unit = "bar",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_BAR_10,
                formulaDisplay = "((A * 256) + B) / 10.0",
                description = "UDS Service 0x22 DID 020B High-Pressure Direct Injection Common Rail Pressure (IDE00201)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "22020C",
                service = "22",
                pid = "020C",
                name = "Pre-Turbine Exhaust Gas Temp (UDS)",
                shortName = "EGT pre-turbo",
                unit = "°C",
                canHeader = "7E0",
                expectedRxId = "7E8",
                defaultIntervalMs = 800L,
                enabled = true,
                decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10.0 - 40",
                description = "UDS Service 0x22 DID 020C Calculated & Measured Pre-Turbine Exhaust Gas Temp (IDE00193)",
                priority = PollingPriority.MEDIUM
            ),
            // ─── Extended UDS: Automatic Transmission Module 02 (6-AT AQ250) ──
            PidDefinition(
                id = "220220",
                service = "22",
                pid = "0220",
                name = "Transmission Fluid Temp (UDS)",
                shortName = "ATF Temp",
                unit = "°C",
                canHeader = "7E1",
                expectedRxId = "7E9",
                defaultIntervalMs = 2000L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "UDS Service 0x22 DID 0220 Automatic Transmission Fluid Temperature (G93 Sensor)",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "220221",
                service = "22",
                pid = "0221",
                name = "Torque Converter Slip (UDS)",
                shortName = "TC Slip",
                unit = "RPM",
                canHeader = "7E1",
                expectedRxId = "7E9",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.SIGNED_16_RAW,
                formulaDisplay = "Signed16(A, B)",
                description = "UDS Service 0x22 DID 0221 Torque Converter Lockup Clutch Slip RPM",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "220222",
                service = "22",
                pid = "0222",
                name = "Transmission Line Pressure (UDS)",
                shortName = "AT Line Press",
                unit = "bar",
                canHeader = "7E1",
                expectedRxId = "7E9",
                defaultIntervalMs = 500L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_BAR_100,
                formulaDisplay = "((A * 256) + B) / 100.0",
                description = "UDS Service 0x22 DID 0222 Transmission Main Hydraulic Line Pressure",
                priority = PollingPriority.MEDIUM
            ),
            // ─── Extended UDS: Chassis & ABS/ESC Module 03 (J104) ────────────
            PidDefinition(
                id = "2202B0",
                service = "22",
                pid = "02B0",
                name = "Wheel Speed Front-Left (UDS)",
                shortName = "Speed FL",
                unit = "km/h",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.WHEEL_SPEED_100,
                formulaDisplay = "((A * 256) + B) / 100.0",
                description = "UDS Service 0x22 DID 02B0 Front-Left Wheel Speed Sensor G47",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "2202B1",
                service = "22",
                pid = "02B1",
                name = "Wheel Speed Front-Right (UDS)",
                shortName = "Speed FR",
                unit = "km/h",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.WHEEL_SPEED_100,
                formulaDisplay = "((A * 256) + B) / 100.0",
                description = "UDS Service 0x22 DID 02B1 Front-Right Wheel Speed Sensor G45",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "2202B3",
                service = "22",
                pid = "02B3",
                name = "Brake Master Cylinder Pressure (UDS)",
                shortName = "Brake Press",
                unit = "bar",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_BAR_10,
                formulaDisplay = "((A * 256) + B) / 10.0",
                description = "UDS Service 0x22 DID 02B3 Brake Hydraulic Pressure Sensor G201 (0-200 bar)",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "2202B4",
                service = "22",
                pid = "02B4",
                name = "Lateral Acceleration G-Force (UDS)",
                shortName = "Lat G",
                unit = "G",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.SIGNED_16_DIV_100,
                formulaDisplay = "Signed16(A, B) / 100.0",
                description = "UDS Service 0x22 DID 02B4 Lateral Acceleration G-Force Sensor G200",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "2202B5",
                service = "22",
                pid = "02B5",
                name = "Yaw Rate Cornering (UDS)",
                shortName = "Yaw Rate",
                unit = "°/s",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.SIGNED_16_DIV_100,
                formulaDisplay = "Signed16(A, B) / 100.0",
                description = "UDS Service 0x22 DID 02B5 Yaw Rate Sensor G202",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "2202B6",
                service = "22",
                pid = "02B6",
                name = "Wheel Speed Rear-Left (UDS)",
                shortName = "Speed RL",
                unit = "km/h",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.WHEEL_SPEED_100,
                formulaDisplay = "((A * 256) + B) / 100.0",
                description = "UDS Service 0x22 DID 02B6 Rear-Left Wheel Speed Sensor G46",
                priority = PollingPriority.FAST
            ),
            PidDefinition(
                id = "2202B7",
                service = "22",
                pid = "02B7",
                name = "Wheel Speed Rear-Right (UDS)",
                shortName = "Speed RR",
                unit = "km/h",
                canHeader = "713",
                expectedRxId = "77D",
                defaultIntervalMs = 150L,
                enabled = true,
                decoderType = DecoderType.WHEEL_SPEED_100,
                formulaDisplay = "((A * 256) + B) / 100.0",
                description = "UDS Service 0x22 DID 02B7 Rear-Right Wheel Speed Sensor G44",
                priority = PollingPriority.FAST
            ),
            // ─── Extended UDS: Climatronic Module 08 (J255) ───────────────────
            PidDefinition(
                id = "220280",
                service = "22",
                pid = "0280",
                name = "A/C Refrigerant Pressure (UDS)",
                shortName = "A/C Press",
                unit = "bar",
                canHeader = "711",
                expectedRxId = "77B",
                defaultIntervalMs = 1000L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_BAR_10,
                formulaDisplay = "((A * 256) + B) / 10.0",
                description = "UDS Service 0x22 DID 0280 High-Pressure A/C Refrigerant Sensor G395 (0-35 bar)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "220281",
                service = "22",
                pid = "0281",
                name = "A/C Compressor Torque (UDS)",
                shortName = "A/C Torque",
                unit = "Nm",
                canHeader = "711",
                expectedRxId = "77B",
                defaultIntervalMs = 500L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_BAR_10,
                formulaDisplay = "((A * 256) + B) / 10.0",
                description = "UDS Service 0x22 DID 0281 Mechanical Engine Torque Absorbed by A/C Compressor",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "220282",
                service = "22",
                pid = "0282",
                name = "Evaporator Core Temp (UDS)",
                shortName = "Evap Temp",
                unit = "°C",
                canHeader = "711",
                expectedRxId = "77B",
                defaultIntervalMs = 2000L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "UDS Service 0x22 DID 0282 HVAC Evaporator Core Temperature G308",
                priority = PollingPriority.SLOW
            ),
            // ─── Extended UDS: Gateway & 12V Battery Module 19 (J533) ────────
            PidDefinition(
                id = "220260",
                service = "22",
                pid = "0260",
                name = "12V Battery State of Charge (UDS)",
                shortName = "Battery SoC",
                unit = "%",
                canHeader = "710",
                expectedRxId = "77A",
                defaultIntervalMs = 2500L,
                enabled = true,
                decoderType = DecoderType.BATTERY_SOC_PCT,
                formulaDisplay = "A * 100 / 255",
                description = "UDS Service 0x22 DID 0260 12V Lead-Acid Battery State of Charge (IDE01800)",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "220261",
                service = "22",
                pid = "0261",
                name = "12V Battery Internal Resistance (UDS)",
                shortName = "Battery mΩ",
                unit = "mΩ",
                canHeader = "710",
                expectedRxId = "77A",
                defaultIntervalMs = 5000L,
                enabled = true,
                decoderType = DecoderType.PRESSURE_BAR_10,
                formulaDisplay = "((A * 256) + B) / 10.0",
                description = "UDS Service 0x22 DID 0261 12V Battery Internal Resistance (Health Indicator)",
                priority = PollingPriority.SLOW
            ),
            // ─── 0160 Range marker: PIDs supported [61-80] ────────────────────
            PidDefinition(
                id = "0160", service = "01", pid = "60",
                name = "Mode 01 PID 60 - range marker (PIDs supported 61-80)",
                shortName = "Range 61-80", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 60 is the 32-bit availability bitmap for PID 61 to 80. It is a capability row, never a measurement. This is the hole that produced the phantom export row 'Mode 01 PID 60 = 6B 09 00 41': PID 60 had no catalogue entry, lookup() fell through to the generic research row, and the 0160 capability bitmap that answered was printed as if it were a measurement. The owner run answered `41 60 6B 09 00 41` = PID 62 63 65 67 68 6D 70 7A. PidDiscoveryDecoder.decodeSupportedPids excludes it from the supported list and hasNextRange reports it separately.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0161",
                service = "01",
                pid = "61",
                name = "Driver Demand Torque",
                shortName = "Demand Trq",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 250L,
                enabled = true,
                decoderType = DecoderType.TORQUE_PCT,
                formulaDisplay = "A - 125",
                description = "Driver demand engine torque percentage",
                priority = PollingPriority.FAST
            ),

            // MEDIUM TIER (Combustion, Fuel Rates, Pressures, Temps)
            PidDefinition(
                id = "015E",
                service = "01",
                pid = "5E",
                name = "Engine Fuel Rate (Volume)",
                shortName = "Fuel Vol",
                unit = "L/h",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.FUEL_RATE_20,
                formulaDisplay = "((A * 256) + B) / 20.0",
                description = "Volumetric engine fuel rate (L/h)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "019D",
                service = "01",
                pid = "9D",
                name = "Engine Fuel Rate (Mass)",
                shortName = "Fuel Mass",
                unit = "g/s",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.FUEL_RATE_MASS_50,
                formulaDisplay = "((A * 256) + B) / 50.0",
                description = "Mass engine fuel rate (g/s)",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0104",
                service = "01",
                pid = "04",
                name = "Calculated Engine Load",
                shortName = "Load",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Normalized indicated engine torque / load",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "010B",
                service = "01",
                pid = "0B",
                name = "Intake Manifold Absolute Pressure",
                shortName = "MAP",
                unit = "kPa",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.RAW_A_KPA,
                formulaDisplay = "A",
                description = "Intake manifold boost/vacuum pressure",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0110",
                service = "01",
                pid = "10",
                name = "MAF Air Flow Rate",
                shortName = "MAF",
                unit = "g/s",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.MAF_100,
                formulaDisplay = "((A * 256) + B) / 100.0",
                description = "Mass air flow sensor intake rate",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0105",
                service = "01",
                pid = "05",
                name = "Engine Coolant Temperature",
                shortName = "Coolant",
                unit = "°C",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 800L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "Engine cylinder head coolant temperature",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "010F",
                service = "01",
                pid = "0F",
                name = "Intake Air Temperature",
                shortName = "IAT",
                unit = "°C",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 800L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "Intake charge air temperature",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0106",
                service = "01",
                pid = "06",
                name = "Short Term Fuel Trim Bank 1",
                shortName = "STFT B1",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 600L,
                enabled = true,
                decoderType = DecoderType.FUEL_TRIM,
                formulaDisplay = "(A - 128) * 100 / 128",
                description = "Short term closed-loop fuel correction",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "01A4",
                service = "01",
                pid = "A4",
                name = "Transmission Actual Gear / Ratio",
                shortName = "Trans Gear",
                unit = "",
                canHeader = "7DF",
                expectedRxId = "7E9", // Commonly TCU
                defaultIntervalMs = 500L,
                enabled = true,
                decoderType = DecoderType.TRANSMISSION_GEAR_A4,
                formulaDisplay = "SAE J1979 Gear Ratio & Status",
                description = "Transmission actual gear ratio if supported by TCU/ECU",
                priority = PollingPriority.MEDIUM
            ),

            PidDefinition(
                id = "0151",
                service = "01",
                pid = "51",
                name = "Fuel Type",
                shortName = "Fuel Type",
                unit = "",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 5000L,
                enabled = true,
                decoderType = DecoderType.FUEL_TYPE_ENUM,
                formulaDisplay = "Enumeration lookup (Gasoline/Ethanol/etc)",
                description = "Vehicle fuel classification",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0152",
                service = "01",
                pid = "52",
                name = "Ethanol Fuel %",
                shortName = "Ethanol",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 5000L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Ethanol fuel percentage",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0142",
                service = "01",
                pid = "42",
                name = "Control Module Voltage",
                shortName = "Voltage",
                unit = "V",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 2000L,
                enabled = true,
                decoderType = DecoderType.VOLTAGE_1000,
                formulaDisplay = "((A * 256) + B) / 1000",
                description = "ECU supply voltage",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0146",
                service = "01",
                pid = "46",
                name = "Ambient Air Temperature",
                shortName = "Ambient",
                unit = "°C",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 3000L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "Outside ambient air temperature",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0133",
                service = "01",
                pid = "33",
                name = "Barometric Pressure",
                shortName = "Baro",
                unit = "kPa",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 3000L,
                enabled = true,
                decoderType = DecoderType.RAW_A_KPA,
                formulaDisplay = "A",
                description = "Ambient barometric pressure",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "013C",
                service = "01",
                pid = "3C",
                name = "Catalyst Temperature B1S1",
                shortName = "Cat Temp",
                unit = "°C",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 2000L,
                enabled = true,
                decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                description = "Catalytic converter bed temperature",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "012E",
                service = "01",
                pid = "2E",
                name = "Commanded EVAP Purge",
                shortName = "EVAP",
                unit = "%",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 2500L,
                enabled = true,
                decoderType = DecoderType.PERCENT_EVAP,
                formulaDisplay = "A * 100 / 255",
                description = "Commanded evaporative purge valve duty cycle",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0103",
                service = "01",
                pid = "03",
                name = "Fuel System Status",
                shortName = "Fuel Sys",
                unit = "Status",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 3000L,
                enabled = true,
                decoderType = DecoderType.FUEL_SYSTEM_STATUS,
                formulaDisplay = "Enum lookup",
                description = "Fuel system closed loop / open loop status",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0163",
                service = "01",
                pid = "63",
                name = "Engine Reference Torque",
                shortName = "Ref Torque",
                unit = "Nm",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 5000L,
                enabled = true,
                decoderType = DecoderType.TORQUE_NM,
                formulaDisplay = "A * 256 + B",
                description = "Engine reference nominal torque value",
                priority = PollingPriority.SLOW
            ),
            PidDefinition(
                id = "0167",
                service = "01",
                pid = "67",
                name = "Engine Coolant Temperature 2",
                shortName = "Coolant 2",
                unit = "°C",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 3000L,
                enabled = true,
                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                // DELIBERATELY NOT the J1979 2-byte form. SAE says PID 67 is 2 bytes in
                // 0.1 degC steps with a -40 offset, but the real car (owner run 2026-09-16,
                // frame 41 67 03 50 43) answers with FIVE payload bytes - neither form fits.
                // Under A - 40 the ECU sentinel 0x03 decodes to -37 degC and the plausibility
                // gate rejects it as "implausible raw - no data", which is the honest outcome
                // and the one pinned by coolant2_4167035043_sentinelRejectedAsNoData. Under
                // the 2-byte form the same frame yields (3*256+80)/10-40 = 44.4 degC: a
                // plausible-looking number invented out of a sentinel. NO-FAKE-VALUES wins
                // over the spec sheet until the payload is validated byte by byte.
                description = "Radiator outlet or secondary coolant temperature. On this car the ECU returns a no-sensor sentinel that the plausibility gate rejects - see the comment above.",
                dataBytes = 1,
                priority = PollingPriority.SLOW
            ),

            // RESEARCH PIDs (Explicitly flagged as research, raw byte preservation)
            PidDefinition(
                id = "016D",
                service = "01",
                pid = "6D",
                name = "Fuel Pressure Control (Research)",
                shortName = "Fuel Press [6D]",
                unit = "RAW",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.RESEARCH_RAW,
                formulaDisplay = "Raw Hex Preservation (Reverse Engineering)",
                isResearch = true,
                description = "EA211 Direct Injection High-Pressure Fuel Rail / Sensor Research PID",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "0170",
                service = "01",
                pid = "70",
                name = "Boost Pressure Control (Research)",
                shortName = "Boost Ctrl [70]",
                unit = "RAW",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 400L,
                enabled = true,
                decoderType = DecoderType.RESEARCH_RAW,
                formulaDisplay = "Raw Hex Preservation (Reverse Engineering)",
                isResearch = true,
                description = "EA211 1.0 TSI Turbocharger Wastegate & Boost Control Research PID",
                priority = PollingPriority.MEDIUM
            )
        ) + listOf(
            // The defaults list is what a fresh install polls and what an existing install
            // reconciles against, so a channel this car demonstrably answers belongs here too.
            // ProvenChannels.ODOMETER is referenced, NOT copied: one definition, two lists, and
            // no recursion (StandardPidCatalog.lookup() reads the lazy catalog that is itself
            // built from getDefaults(), so calling it here would deadlock the initializer).
            ProvenChannels.ODOMETER
        )
    }
}

/**
 * Comprehensive SAE J1979 standard OBD-II Mode 01 PID catalog.
 * Provides metadata (name, description, unit, byte count, formula) for discovered PIDs.
 */
object StandardPidCatalog {

    private val catalog: Map<String, PidDefinition> by lazy {
        val list = mutableListOf<PidDefinition>()

        // Include all default definitions first
        list.addAll(DefaultPidDefinitions.getDefaults())

        // Add additional standard SAE J1979 Mode 01 PIDs
        val additional = listOf(
            PidDefinition(
                id = "0101", service = "01", pid = "01",
                name = "Monitor Status Since DTCs Cleared",
                shortName = "Monitors", unit = "Bitmask",
                dataBytes = 4, description = "Status of OBD readiness monitors",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0102", service = "01", pid = "02",
                name = "Freeze DTC",
                shortName = "Freeze DTC", unit = "Code",
                dataBytes = 2, description = "DTC that triggered freeze frame storage",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0107", service = "01", pid = "07",
                name = "Long Term Fuel Trim Bank 1",
                shortName = "LTFT B1", unit = "%",
                dataBytes = 1, decoderType = DecoderType.FUEL_TRIM,
                formulaDisplay = "(A - 128) * 100 / 128",
                description = "Long-term secondary fuel adaptation trim for bank 1",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 1200L
            ),
            PidDefinition(
                id = "0108", service = "01", pid = "08",
                name = "Short Term Fuel Trim Bank 2",
                shortName = "STFT B2", unit = "%",
                dataBytes = 1, decoderType = DecoderType.FUEL_TRIM,
                formulaDisplay = "(A - 128) * 100 / 128",
                description = "Short-term fuel trim for cylinder bank 2",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "0109", service = "01", pid = "09",
                name = "Long Term Fuel Trim Bank 2",
                shortName = "LTFT B2", unit = "%",
                dataBytes = 1, decoderType = DecoderType.FUEL_TRIM,
                formulaDisplay = "(A - 128) * 100 / 128",
                description = "Long-term fuel trim for cylinder bank 2",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "010A", service = "01", pid = "0A",
                name = "Fuel Pressure (Gauge)",
                shortName = "Fuel Press", unit = "kPa",
                dataBytes = 1, decoderType = DecoderType.FUEL_PRESSURE_3_KPA,
                formulaDisplay = "A * 3",
                description = "Low-pressure fuel system gauge pressure",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "010E", service = "01", pid = "0E",
                name = "Timing Advance",
                shortName = "Timing", unit = "°",
                dataBytes = 1, decoderType = DecoderType.TIMING_ADVANCE,
                formulaDisplay = "A / 2 - 64",
                description = "Ignition timing advance before top dead center (BTDC)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "0112", service = "01", pid = "12",
                name = "Commanded Secondary Air Status",
                shortName = "Sec Air", unit = "Status",
                dataBytes = 1, description = "Secondary air injection status",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0113", service = "01", pid = "13",
                name = "Oxygen Sensors Present (2 Banks)",
                shortName = "O2 Present", unit = "Bitmask",
                dataBytes = 1, description = "Bitmask indicating oxygen sensors present across 2 banks",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0114", service = "01", pid = "14",
                name = "O2 Sensor 1 Voltage & Short Term Trim",
                shortName = "O2 B1S1", unit = "V",
                dataBytes = 2, description = "Bank 1 Sensor 1 oxygen sensor output voltage",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "0115", service = "01", pid = "15",
                name = "O2 Sensor 2 Voltage & Short Term Trim",
                shortName = "O2 B1S2", unit = "V",
                dataBytes = 2, description = "Bank 1 Sensor 2 post-cat oxygen sensor output",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "011C", service = "01", pid = "1C",
                name = "OBD Standard Conformance",
                shortName = "OBD Std", unit = "Enum",
                dataBytes = 1, description = "OBD standard requirements to which vehicle is certified",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "011F", service = "01", pid = "1F",
                name = "Run Time Since Engine Start",
                shortName = "Run Time", unit = "s",
                dataBytes = 2, description = "Accumulated engine running seconds since start",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 0120 Range marker: PIDs supported [21-40] ────────────────────
            PidDefinition(
                id = "0120", service = "01", pid = "20",
                name = "Mode 01 PID 20 - range marker (PIDs supported 21-40)",
                shortName = "Range 21-40", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 20 is the 32-bit availability bitmap for PID 21 to 40. It is a capability row, never a measurement. In the owner run of 2026-09-16 the 41 00 bitmap was received during TX 0120 and correctly rejected for base 0x20, which is why the 0x20 block is UNKNOWN rather than empty - it was never decoded at all. PidDiscoveryDecoder.decodeSupportedPids excludes it from the supported list and hasNextRange reports it separately.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0121", service = "01", pid = "21",
                name = "Distance Traveled With MIL On",
                shortName = "MIL Dist", unit = "km",
                dataBytes = 2, description = "Cumulative distance driven while malfunction indicator lamp is active",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0122", service = "01", pid = "22",
                name = "Fuel Rail Pressure (Vacuum Relative)",
                shortName = "Rail Press Vac", unit = "kPa",
                dataBytes = 2, description = "Fuel rail pressure relative to manifold vacuum",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "0123", service = "01", pid = "23",
                name = "Fuel Rail Gauge Pressure",
                shortName = "Rail Press", unit = "kPa",
                dataBytes = 2, decoderType = DecoderType.FUEL_RAIL_PRESSURE,
                formulaDisplay = "((A * 256) + B) * 10",
                description = "High-pressure direct injection rail pressure",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "012C", service = "01", pid = "2C",
                name = "Commanded EGR",
                shortName = "Cmd EGR", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Commanded exhaust gas recirculation valve position",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "012D", service = "01", pid = "2D",
                name = "EGR Error",
                shortName = "EGR Error", unit = "%",
                dataBytes = 1, description = "EGR system position error relative to setpoint",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "012F", service = "01", pid = "2F",
                name = "Fuel Tank Level Input",
                shortName = "Fuel Level", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Fuel level sender input from tank sender unit",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0130", service = "01", pid = "30",
                name = "Warm-ups Since Codes Cleared",
                shortName = "Warm-ups", unit = "Count",
                dataBytes = 1, description = "Number of engine warm-up cycles since diagnostic memory reset",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0131", service = "01", pid = "31",
                name = "Distance Traveled Since Codes Cleared",
                shortName = "Clr Dist", unit = "km",
                dataBytes = 2, description = "Odometer distance traveled since DTCs reset",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0132", service = "01", pid = "32",
                name = "Evaporative System Vapor Pressure",
                shortName = "EVAP Press", unit = "Pa",
                dataBytes = 2, description = "Fuel tank evaporative emission pressure sensor",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "013D", service = "01", pid = "3D",
                name = "Catalyst Temperature Bank 2 Sensor 1",
                shortName = "Cat B2S1", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                description = "Cylinder bank 2 pre-catalyst bed temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0140 Range marker: PIDs supported [41-60] ────────────────────
            PidDefinition(
                id = "0140", service = "01", pid = "40",
                name = "Mode 01 PID 40 - range marker (PIDs supported 41-60)",
                shortName = "Range 41-60", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 40 is the 32-bit availability bitmap for PID 41 to 60. It is a capability row, never a measurement. The owner run answered `41 40 FE D0 84 01` = PID 41 42 43 44 45 46 47 49 4A 4C 51 56. PidDiscoveryDecoder.decodeSupportedPids excludes it from the supported list and hasNextRange reports it separately.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0141", service = "01", pid = "41",
                name = "Monitor Status This Drive Cycle",
                shortName = "Drive Monitors", unit = "Bitmask",
                dataBytes = 4, description = "Readiness status of system monitors during current ignition drive cycle",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "0143", service = "01", pid = "43",
                name = "Absolute Load Value",
                shortName = "Abs Load", unit = "%",
                dataBytes = 2, decoderType = DecoderType.PERCENT_LOAD_255,
                formulaDisplay = "((A * 256) + B) / 2.55",
                description = "Normalized thermodynamic air charge mass per stroke",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "0144", service = "01", pid = "44",
                name = "Commanded Equivalence Ratio (Lambda)",
                shortName = "Cmd Lambda", unit = "λ",
                dataBytes = 2, decoderType = DecoderType.EQUIVALENCE_RATIO,
                formulaDisplay = "((A * 256) + B) / 32768",
                description = "Target air-fuel equivalence ratio commanded by ECU",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            PidDefinition(
                id = "0145", service = "01", pid = "45",
                name = "Relative Throttle Position",
                shortName = "Rel Throttle", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Relative throttle angle above learned idle stop",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "0147", service = "01", pid = "47",
                name = "Absolute Throttle Position B",
                shortName = "Throttle B", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Secondary throttle potentiometer sensor B",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "0148", service = "01", pid = "48",
                name = "Absolute Throttle Position C",
                shortName = "Throttle C", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Throttle angle channel C",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "014C", service = "01", pid = "4C",
                name = "Commanded Throttle Actuator",
                shortName = "Cmd Throttle", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Electronic throttle body drive motor commanded duty",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            PidDefinition(
                id = "014D", service = "01", pid = "4D",
                name = "Time Run With MIL On",
                shortName = "MIL Time", unit = "min",
                dataBytes = 2, description = "Engine operating minutes with check engine lamp illuminated",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "014E", service = "01", pid = "4E",
                name = "Time Since Trouble Codes Cleared",
                shortName = "Clr Time", unit = "min",
                dataBytes = 2, description = "Engine operating minutes accumulated since DTC memory clear",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "015C", service = "01", pid = "5C",
                name = "Engine Oil Temperature",
                shortName = "Oil Temp", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                description = "Engine crankcase sump oil temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            PidDefinition(
                id = "015D", service = "01", pid = "5D",
                name = "Fuel Injection Timing",
                shortName = "Inj Timing", unit = "°",
                dataBytes = 2, decoderType = DecoderType.INJECTION_TIMING_128,
                formulaDisplay = "((A * 256 + B) - 26880) / 128",
                description = "Main fuel injection pulse start angle relative to TDC",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),

            // ─── 0153 Absolute Evap Vapor Pressure ───────────────────────
            PidDefinition(
                id = "0153", service = "01", pid = "53",
                name = "Absolute Evap Vapor Pressure",
                shortName = "Evap Abs", unit = "kPa",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Absolute evaporative emission system vapor pressure",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0154 Evap Vapor Pressure (Relative) ─────────────────────
            PidDefinition(
                id = "0154", service = "01", pid = "54",
                name = "Evap Vapor Pressure (Relative)",
                shortName = "Evap Rel", unit = "kPa",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Evap system vapor pressure relative to atmospheric",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0155 short term secondary O2 trim, bank 1+3 ──────────────────
            PidDefinition(
                id = "0155", service = "01", pid = "55",
                name = "Secondary O2 Sensor Short Term Fuel Trim (Bank 1+3)",
                shortName = "ST O2 B1+3",
                dataBytes = 2, decoderType = DecoderType.O2_TRIM_PAIR_2B,
                formulaDisplay = "(A - 128) * 100 / 128 % ; (B - 128) * 100 / 128 %",
                description = "SAE J1979 PID 55 = short term secondary oxygen sensor fuel trim, bank 1+3, two bytes of (X - 128) * 100 / 128 %. It is NOT an oxygen sensor lambda/voltage channel - a revision of this audit mislabelled the whole 55-59 range that way and then invented a sensor voltage out of byte A. The owner run of 2026-09-16 answered it: `41 55 80 80` = +0.0 % / +0.0 %%, the post-cat trims of a closed-loop engine at stoichiometry.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%"
            ),
            // ─── 0156 long term secondary O2 trim, bank 1+3 ───────────────────
            PidDefinition(
                id = "0156", service = "01", pid = "56",
                name = "Secondary O2 Sensor Long Term Fuel Trim (Bank 1+3)",
                shortName = "LT O2 B1+3",
                dataBytes = 2, decoderType = DecoderType.O2_TRIM_PAIR_2B,
                formulaDisplay = "(A - 128) * 100 / 128 % ; (B - 128) * 100 / 128 %",
                description = "SAE J1979 PID 56 = long term secondary oxygen sensor fuel trim, bank 1+3, two bytes of (X - 128) * 100 / 128 %. It is NOT an oxygen sensor lambda/voltage channel - a revision of this audit mislabelled the whole 55-59 range that way and then invented a sensor voltage out of byte A. The owner run answered `41 56 7F 00` = -0.8 %%, and the second byte is the -100 %% no-sensor sentinel of the bank a 3-cylinder does not have, which the decoder suppresses instead of printing.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%"
            ),
            // ─── 0157 short term secondary O2 trim, bank 2+4 ──────────────────
            PidDefinition(
                id = "0157", service = "01", pid = "57",
                name = "Secondary O2 Sensor Short Term Fuel Trim (Bank 2+4)",
                shortName = "ST O2 B2+4",
                dataBytes = 2, decoderType = DecoderType.O2_TRIM_PAIR_2B,
                formulaDisplay = "(A - 128) * 100 / 128 % ; (B - 128) * 100 / 128 %",
                description = "SAE J1979 PID 57 = short term secondary oxygen sensor fuel trim, bank 2+4, two bytes of (X - 128) * 100 / 128 %. It is NOT an oxygen sensor lambda/voltage channel - a revision of this audit mislabelled the whole 55-59 range that way and then invented a sensor voltage out of byte A. Not claimed by this car (bit clear in the 0140 bitmap FE D0 84 01).",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%"
            ),
            // ─── 0158 long term secondary O2 trim, bank 2+4 ───────────────────
            PidDefinition(
                id = "0158", service = "01", pid = "58",
                name = "Secondary O2 Sensor Long Term Fuel Trim (Bank 2+4)",
                shortName = "LT O2 B2+4",
                dataBytes = 2, decoderType = DecoderType.O2_TRIM_PAIR_2B,
                formulaDisplay = "(A - 128) * 100 / 128 % ; (B - 128) * 100 / 128 %",
                description = "SAE J1979 PID 58 = long term secondary oxygen sensor fuel trim, bank 2+4, two bytes of (X - 128) * 100 / 128 %. It is NOT an oxygen sensor lambda/voltage channel - a revision of this audit mislabelled the whole 55-59 range that way and then invented a sensor voltage out of byte A. Not claimed by this car (bit clear in the 0140 bitmap FE D0 84 01).",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%"
            ),
            // ─── 0159 long term secondary O2 trim, bank 2+4 ───────────────────
            PidDefinition(
                id = "0159", service = "01", pid = "59",
                name = "Secondary O2 Sensor Long Term Fuel Trim (Bank 2+4)",
                shortName = "LT O2 B2+4",
                dataBytes = 2, decoderType = DecoderType.O2_TRIM_PAIR_2B,
                formulaDisplay = "(A - 128) * 100 / 128 % ; (B - 128) * 100 / 128 %",
                description = "SAE J1979 PID 59 = long term secondary oxygen sensor fuel trim, bank 2+4, two bytes of (X - 128) * 100 / 128 %. It is NOT an oxygen sensor lambda/voltage channel - a revision of this audit mislabelled the whole 55-59 range that way and then invented a sensor voltage out of byte A. Not claimed by this car (bit clear in the 0140 bitmap FE D0 84 01).",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%"
            ),
            // ─── 015A Relative accelerator pedal position (J1979) ─────────────
            PidDefinition(
                id = "015A", service = "01", pid = "5A",
                name = "Relative Accelerator Pedal Position",
                shortName = "Pedal Rel",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "SAE J1979 PID 5A = RELATIVE accelerator pedal position, A * 100 / 255 %%. This entry has now been wrong twice: first as 'Generator Speed (Alternator RPM)' decoded in kPa, then as a second engine coolant temperature decoded A - 40. Neither meaning exists in J1979. The bit is clear in this car's 0140 bitmap, so no frame has ever validated it - the spec name is recorded and the entry stays disabled until the car answers.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%",
                enabled = false
            ),
            // ─── 015B Hybrid battery pack life (disabled: no pack) ────────────
            PidDefinition(
                id = "015B", service = "01", pid = "5B",
                name = "Hybrid Battery Pack Remaining Life",
                shortName = "Hyb Batt",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                isResearch = false,
                enabled = false,
                description = "SAE J1979 PID 5B = remaining life of a hybrid battery pack, A * 100 / 255 %%. It was catalogued here as a third engine coolant temperature decoded A - 40, and then as 'vendor specific, not in J1979' - both wrong. The Kylaq 1.0 TSI has no hybrid pack and the bit is clear in the 0140 bitmap, so the entry stays disabled: a petrol car must never show a hybrid-battery number.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "%",
                formulaDisplay = "A * 100 / 255"
            ),
            // ─── 015F Emission requirements enum (NOT oil life) ───────────────
            PidDefinition(
                id = "015F", service = "01", pid = "5F",
                name = "Emission Requirements (Design Standard)",
                shortName = "Emiss Std",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "SAE J1979 PID 5F = an ENUM of the emission standard the vehicle was designed to meet, not a percentage. It was catalogued as 'Engine Oil Life Remaining' decoded A * 100 / 255 %%, which would have printed the standard code as a fake oil-life number. This car DOES claim the PID (bit set in the 0140 bitmap FE D0 84 01), so the enum table has to be written and unit-tested before it is enabled; until then it is disabled research raw.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "",
                formulaDisplay = "enum: 1=OBD-II(CARB) 2=OBD 3=OBD+OBD-II 4=OBD-I 5=not compliant 6=EOBD 7=OBD-II+EOBD",
                isResearch = true,
                enabled = false
            ),
            // ─── 0164 Engine Reference Torque (extended) ────────────────
            PidDefinition(
                id = "0164", service = "01", pid = "64",
                name = "Engine Reference Torque (Extended)",
                shortName = "Ref Tq Ext", unit = "Nm",
                dataBytes = 2, decoderType = DecoderType.TORQUE_NM,
                description = "Extended reference torque for current engine operating point",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 0165 Auxiliary input/output bitmap (boost claim withdrawn) ───
            PidDefinition(
                id = "0165", service = "01", pid = "65",
                name = "Auxiliary Input / Output Status",
                shortName = "Aux IO",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                formulaDisplay = "bitmap - layout not established",
                description = "SAE J1979 PID 65 = 'auxiliary input / output supported', a two-byte bitmap. It is NOT turbocharger boost pressure and not one byte of kPa. The owner run answered `41 65 10 10`; read as boost that is 16 kPa, plausible for a warm-idle manifold but resting on a name the standard does not use, so nothing is published until a load sweep proves the layout. The real pressure channels on this car stay PID 0B (MAP) and PID 6F/70 (compressor inlet / boost control). Before this pass it was dataBytes = 2 + RESEARCH_RAW at FAST priority, polling an unknown bitmap 6 times a second.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "",
                isResearch = true,
                enabled = false
            ),
            // ─── 0166 Charge Air Cooler Temperature ───────────────────────
            PidDefinition(
                id = "0166", service = "01", pid = "66",
                name = "Charge Air Cooler Temperature",
                shortName = "Charge T", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Intercooler / charge air cooler outlet temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0168 Intake Air Temperature 2 (alt) ─────────────────────
            PidDefinition(
                id = "0168", service = "01", pid = "68",
                name = "Intake Air Temperature 2 (Alt)",
                shortName = "IAT 2 Alt", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                // Same ruling as PID 67: the J1979 2-byte form was tried on paper and
                // reverted, because the only real evidence we hold (owner run 2026-09-16) is
                // a sentinel rejection, not a validated temperature. Changing the formula
                // without a car sample risks turning a rejected sentinel into a believable
                // number. Re-test with a logged frame before touching this again.
                description = "Secondary intake air temperature (post-throttle body, EA211). The owner run 2026-09-16 returned an implausible raw payload that the honesty gate rejected; kept as 1-byte A - 40 until a real frame is validated.",
                formulaDisplay = "A - 40",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0169 Boost Pressure (alternative) ────────────────────────
            PidDefinition(
                id = "0169", service = "01", pid = "69",
                name = "Boost Pressure (Alternative)",
                shortName = "Boost 2", unit = "kPa",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Alternative boost pressure encoding (kPa relative)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 016A VVT Phase (Intake Cam) ──────────────────────────────
            PidDefinition(
                id = "016A", service = "01", pid = "6A",
                name = "VVT Phase (Intake Cam)",
                shortName = "VVT Phase", unit = "°",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Variable valve timing phase angle (intake camshaft)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 016B VVT Control Duty ───────────────────────────────────
            PidDefinition(
                id = "016B", service = "01", pid = "6B",
                name = "VVT Control Duty",
                shortName = "VVT Duty", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "Variable valve timing actuator control duty cycle",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 016C Coolant Pump Status ─────────────────────────────────
            PidDefinition(
                id = "016C", service = "01", pid = "6C",
                name = "Coolant Pump Status",
                shortName = "Coolant Pump", unit = "",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Electric coolant pump operating state (bitmask)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 016E Wastegate Actuator Command ──────────────────────────
            PidDefinition(
                id = "016E", service = "01", pid = "6E",
                name = "Wastegate Actuator Command",
                shortName = "WG Cmd", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "Turbocharger wastegate actuator position command",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 016F Engine Coolant Flow Rate ────────────────────────────
            PidDefinition(
                id = "016F", service = "01", pid = "6F",
                name = "Engine Coolant Flow Rate",
                shortName = "Coolant Flow", unit = "L/min",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Engine coolant volumetric flow rate",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0171 Control Module Power Supply ─────────────────────────
            PidDefinition(
                id = "0171", service = "01", pid = "71",
                name = "Control Module Power Supply",
                shortName = "CM Pwr", unit = "V",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Control module internal supply voltage (0.1V units)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0172 Exhaust Gas Pressure ────────────────────────────────
            PidDefinition(
                id = "0172", service = "01", pid = "72",
                name = "Exhaust Gas Pressure",
                shortName = "Exh P", unit = "kPa",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Exhaust back-pressure upstream of turbocharger",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0173 Turbocharger Speed ──────────────────────────────────
            PidDefinition(
                id = "0173", service = "01", pid = "73",
                name = "Turbocharger Speed",
                shortName = "Turbo RPM", unit = "RPM",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Turbocharger shaft rotational speed (RPM)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 0174 Turbocharger Temperature 1/2 ──────────────────────────
            PidDefinition(
                id = "0174", service = "01", pid = "74",
                name = "Turbocharger Temperature 1/2",
                shortName = "Turbo T 1", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Turbocharger bearing/turbine temperature (B1, B2)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0175 Turbocharger Temperature 3/4 ─────────────────────────
            PidDefinition(
                id = "0175", service = "01", pid = "75",
                name = "Turbocharger Temperature 3/4",
                shortName = "Turbo T 2", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Turbocharger compressor outlet temperature (B1, B2)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0176 Charge Air Cooler Temperature (alt) ─────────────────
            PidDefinition(
                id = "0176", service = "01", pid = "76",
                name = "Charge Air Cooler Temp (Alt)",
                shortName = "CAC T Alt", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Alternative charge air cooler temperature sensor",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0177 EGT Bank 1 / Bank 2 ─────────────────────────────────
            PidDefinition(
                id = "0177", service = "01", pid = "77",
                name = "Exhaust Gas Temperature B1/B2",
                shortName = "EGT B1/B2", unit = "°C",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "Exhaust gas temperature bank 1 and bank 2",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0178 Exhaust Gas Temperature Bank 1 (J1979) ───────────────
            PidDefinition(
                id = "0178", service = "01", pid = "78",
                name = "Exhaust Gas Temperature Bank 1",
                shortName = "EGT B1", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                description = "SAE J1979 PID 78 is exhaust gas temperature bank 1 in 0.1 degC steps with a -40 offset, not an O2 sensor on a bank 3 that does not exist on a 3-cylinder engine.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0179 Exhaust Gas Temperature Bank 2 (J1979) ───────────────
            PidDefinition(
                id = "0179", service = "01", pid = "79",
                name = "Exhaust Gas Temperature Bank 2",
                shortName = "EGT B2", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                enabled = false,
                description = "SAE J1979 PID 79 is exhaust gas temperature bank 2. This engine has one bank, so it stays disabled unless a run proves the car answers it.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 017A DPF temperature (diesel block, unproven here) ───────────
            PidDefinition(
                id = "017A", service = "01", pid = "7A",
                name = "DPF Temperature [7A]",
                shortName = "DPF T",
                dataBytes = 9, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "J1979 lists PID 7A as a diesel particulate filter temperature block (9 bytes). The owner run returned 7 bytes (07 00 0E 00 0B 00 00) from a PETROL car with a three-way catalyst, so neither the byte count nor the meaning is established. Research raw, nothing published, entry disabled. Previously catalogued as an O2 sensor on bank 4 - a 3-cylinder engine has one bank.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "degC",
                enabled = false
            ),
            // ─── 017B DPF pressure (diesel block, never claimed) ──────────────
            PidDefinition(
                id = "017B", service = "01", pid = "7B",
                name = "DPF Pressure [7B]",
                shortName = "DPF P",
                dataBytes = 9, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "J1979 lists PID 7B as a diesel particulate filter pressure block (9 bytes) - not an O2 sensor on bank 4 and not a PM soot/ash loading. This petrol car never claimed the bit. Research raw until a validated response defines the layout.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "kPa",
                enabled = false
            ),
            // ─── 017C EGR Temperature ─────────────────────────────────────
            PidDefinition(
                id = "017C", service = "01", pid = "7C",
                name = "Exhaust Gas Recirculation Temperature",
                shortName = "EGR Temp", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Exhaust gas recirculation inlet/outlet temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 017D Throttle Position (Alternative) ─────────────────────
            PidDefinition(
                id = "017D", service = "01", pid = "7D",
                name = "Throttle Position (Alternative)",
                shortName = "TPS Alt", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "Alternative throttle position encoding (0-100%)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 017E Engine Running Time ──────────────────────────────────
            PidDefinition(
                id = "017E", service = "01", pid = "7E",
                name = "Engine Running Time",
                shortName = "Run Time", unit = "min",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Total engine operating time since start (minutes)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 017F Engine Run Time for AECD #13 (J1979) ─────────────────
            PidDefinition(
                id = "017F", service = "01", pid = "7F",
                name = "Engine Run Time for AECD #13",
                shortName = "AECD 13", unit = "RAW",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 7F is engine run time for AECD #13, not a post-DPF NOx reading. Not claimed by this car (bit clear in the 0160 block bitmap).",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0180 RANGE MARKER - not a data PID (J1979) ────────────────
            PidDefinition(
                id = "0180", service = "01", pid = "80",
                name = "Supported PIDs [81-A0] (range marker)",
                shortName = "Range 81-A0", unit = "Bitmap",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 80 is the availability bitmap for PIDs 81-A0, NOT a diesel particulate filter temperature, and never on a petrol engine. Disabled and excluded from discovery since 2026-09-17: the owner export listed it as a supported data PID and decoded the lagged 01A0 bitmap as its value.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0181 Engine Run Time for AECD #1 (J1979) ──────────────────
            PidDefinition(
                id = "0181", service = "01", pid = "81",
                name = "Engine Run Time for AECD #1",
                shortName = "AECD 1", unit = "RAW",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 81 is engine run time for AECD #1, not DPF soot load.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0182 Engine Run Time for AECD #2 (J1979) ──────────────────
            PidDefinition(
                id = "0182", service = "01", pid = "82",
                name = "Engine Run Time for AECD #2",
                shortName = "AECD 2", unit = "RAW",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 82 is engine run time for AECD #2, not DPF ash load.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0183 NOx Sensor (J1979) - bitmap-supported on this car ────
            PidDefinition(
                id = "0183", service = "01", pid = "83",
                name = "NOx Sensor [83]",
                shortName = "NOx [83]", unit = "RAW",
                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 83 = NOx sensor (5 bytes), not DPF regeneration status. The real 0180-block bitmap of this car (00 24 00 0D) sets this bit, so the Kylaq claims it, but the 2026-09-16 run never validated it because the 0180 answer was consumed by the lagged-bitmap bug. Research raw until a validated response defines the bit layout.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // 0184 Manifold Surface Temperature (SAE J1979)
            PidDefinition(
                id = "0184", service = "01", pid = "84",
                name = "Manifold Surface Temperature",
                shortName = "Manifold T", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                enabled = false,
                description = "SAE J1979 PID 84: manifold surface temperature. Not claimed by the 0180 bitmap of this car, so it stays disabled unless a run proves otherwise.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // 0185 NOx Reagent System (SAE J1979) - bitmap-supported on this car
            PidDefinition(
                id = "0185", service = "01", pid = "85",
                name = "NOx Reagent System [85]",
                shortName = "Reagent [85]", unit = "RAW",
                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 85 = NOx reagent system. It was missing from the catalog entirely. Claimed by the real 0180 bitmap of this car; never validated because of the lagged-bitmap bug. Research raw until captured.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0186 PM sensor (J1979, never claimed by this car) ────────────
            PidDefinition(
                id = "0186", service = "01", pid = "86",
                name = "Particulate Matter (PM) Sensor [86]",
                shortName = "PM [86]", unit = "RAW",
                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 86 = particulate-matter sensor (5 bytes), not an estimated fuel filament degradation - no such standard PID exists. NOT claimed by this car: the real 0180 bitmap of the 2026-09-16 run is `00 24 00 0D`, which sets PID 8B, 8E, 9D and 9E only. An earlier revision of this audit mis-decoded that bitmap BY HAND as 83/85/86 and reported three NOx/PM channels as 'hidden by a bug'; that claim is withdrawn - the car never claimed them.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                enabled = false
            ),
            // ─── 0187 Intake MAP (J1979) - not claimed by this car ──────────
            PidDefinition(
                id = "0187", service = "01", pid = "87",
                name = "Intake Manifold Absolute Pressure [87]",
                shortName = "MAP [87]", unit = "kPa",
                dataBytes = 5, decoderType = DecoderType.RAW_A_KPA,
                formulaDisplay = "A (kPa)",
                enabled = false,
                description = "SAE J1979 PID 87 = intake manifold absolute pressure (a 5-byte block in the extended tables), not an estimated fuel injector correction. This car does NOT claim it (bit clear in the 0180 bitmap `00 24 00 0D`) and the 2026-09-16 run never validated it, so no frame exists to define the layout. The MAP channel this car does use is PID 0B, claimed by the 0x00 bitmap. Kept catalogued with the standard's name and disabled: no number is published until the car speaks.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                isResearch = true
            ),
            // ─── 018A Run time for AECD #16-#20 (J1979) ───────────────────────
            PidDefinition(
                id = "018A", service = "01", pid = "8A",
                name = "Engine Run Time for AECD #16-#20",
                shortName = "AECD 16-20",
                dataBytes = 21, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 8A = engine run time for auxiliary emission control devices #16 to #20 (21 bytes). It was catalogued here as a vendor-specific 'actual fuel injection quantity per stroke (mm3)' polled at MEDIUM priority / 500 ms - an invented meaning requested twice a second. Not claimed by this car.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "s",
                formulaDisplay = "21-byte AECD run time block",
                enabled = false
            ),
            // ─── 0188 SCR induce system (J1979, diesel-only) ──────────────
            PidDefinition(
                id = "0188", service = "01", pid = "88",
                name = "SCR Induce System [88]",
                shortName = "SCR Induce", unit = "",
                dataBytes = 13, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 88 = selective catalytic reduction induce system (13 bytes), a diesel/AdBlue channel. Not claimed by this petrol car.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 018E Engine friction percent torque - CLAIMED by this car ───
            PidDefinition(
                id = "018E", service = "01", pid = "8E",
                name = "Engine Friction - Percent Torque",
                shortName = "Friction %", unit = "%",
                dataBytes = 1, decoderType = DecoderType.TORQUE_PCT,
                formulaDisplay = "A - 125",
                isResearch = true,
                description = "SAE J1979 PID 8E = engine friction percent torque, one byte, A - 125 %%, range -125..130. This car CLAIMS it (bit set in the 0180 bitmap `00 24 00 0D`) and the 2026-09-16 run never validated it because the catalogue had no entry for it at all, so the discovery sweep had nothing to TX. With PID 61 (driver demand) and PID 62 (actual torque) it closes the torque balance: demand - friction - accessory load = wheel torque, which is exactly what the power model needs. Highest-value gap in the catalogue as of this pass.",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 018B Fuel Pump Command ─────────────────────────────────
            PidDefinition(
                id = "018B", service = "01", pid = "8B",
                name = "Fuel Pump Command",
                shortName = "FP Cmd", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "The J1979 tables this project cites list PID 8B as 'diesel aftertreatment' (7 bytes); the value this car returns is a single byte that the export printed as 31.8 %% under A * 100 / 255. A petrol Kylaq has no diesel aftertreatment block, so the vendor meaning (fuel pump command, low-pressure circuit) is kept - but it is flagged research, because the evidence for it is a plausible number, not a specification. Claimed by the 0180 bitmap `00 24 00 0D`.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                isResearch = true
            ),
            // ─── 018D Engine Start Enable ───────────────────────────────
            PidDefinition(
                id = "018D", service = "01", pid = "8D",
                name = "Engine Start Enable",
                shortName = "Start Enable", unit = "",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Engine start enable signal status (bitmask)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 018F Vendor-specific, no J1979 definition ─────────────────
            PidDefinition(
                id = "018F", service = "01", pid = "8F",
                name = "Vendor-Specific [8F] (not in J1979)",
                shortName = "Vendor [8F]", unit = "RAW",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "PID 8F has no SAE J1979 definition in the tables this project cites. It was catalogued as a secondary engine oil temperature decoded A - 40, which is an invented meaning, so it is now disabled research raw instead.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0191 Intake Air Mass ──────────────────────────────────
            PidDefinition(
                id = "0191", service = "01", pid = "91",
                name = "Intake Air Mass Flow Rate",
                shortName = "Air Mass", unit = "g/s",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Intake manifold air mass flow rate (g/s)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 0192 Turbo Vapor Pressure ──────────────────────────────
            PidDefinition(
                id = "0192", service = "01", pid = "92",
                name = "Turbo Vapor Pressure",
                shortName = "Turbo Vap", unit = "kPa",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Vapor pressure at turbo compressor inlet",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0193 Battery Voltage / Module Voltage ───────────────────
            PidDefinition(
                id = "0193", service = "01", pid = "93",
                name = "Module Voltage (Alternative)",
                shortName = "Module V", unit = "V",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Alternative module voltage (e.g. 0.01V units)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0194 NOx Reagent (DEF/AdBlue) ─────────────────────────
            PidDefinition(
                id = "0194", service = "01", pid = "94",
                name = "NOx Reagent (DEF/AdBlue)",
                shortName = "DEF Level", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "Diesel exhaust fluid (AdBlue) tank level",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0195 NOx Reagent Consumption ───────────────────────────
            PidDefinition(
                id = "0195", service = "01", pid = "95",
                name = "NOx Reagent Consumption",
                shortName = "DEF Use", unit = "L/h",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "DEF/AdBlue consumption rate",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0196 NOx Reagent Range ────────────────────────────────
            PidDefinition(
                id = "0196", service = "01", pid = "96",
                name = "NOx Reagent Range",
                shortName = "DEF Range", unit = "km",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Estimated DEF/AdBlue driving range (km)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0197 SCR Inducement System Status ──────────────────────
            PidDefinition(
                id = "0197", service = "01", pid = "97",
                name = "SCR Inducement System Status",
                shortName = "SCR Induc", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "Selective Catalytic Reduction inducement system state",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0198 SCR Catalyst Temperature ──────────────────────────
            PidDefinition(
                id = "0198", service = "01", pid = "98",
                name = "SCR Catalyst Temperature",
                shortName = "SCR Temp", unit = "°C",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Selective Catalytic Reduction catalyst temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 0199 SCR Catalyst Efficiency ───────────────────────────
            PidDefinition(
                id = "0199", service = "01", pid = "99",
                name = "SCR Catalyst Efficiency",
                shortName = "SCR Eff", unit = "%",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Selective Catalytic Reduction catalyst NOx efficiency",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 019A Hybrid / EV vehicle system data (J1979) ───────────────
            PidDefinition(
                id = "019A", service = "01", pid = "9A",
                name = "Hybrid / EV Vehicle System Data [9A]",
                shortName = "Hybrid Data", unit = "",
                dataBytes = 7, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 9A = hybrid / EV vehicle system data (7 bytes). Not claimed by this petrol car.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 019B Diesel exhaust fluid sensor data (J1979) ────────────
            PidDefinition(
                id = "019B", service = "01", pid = "9B",
                name = "Diesel Exhaust Fluid Sensor Data [9B]",
                shortName = "DEF Data", unit = "",
                dataBytes = 7, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "SAE J1979 PID 9B = diesel exhaust fluid (AdBlue) sensor data (7 bytes). Not claimed by this petrol car.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 019C O2 sensor data block (J1979, 17 bytes) ──────────────
            PidDefinition(
                id = "019C", service = "01", pid = "9C",
                name = "Oxygen Sensor Data [9C]",
                shortName = "O2 Data", unit = "",
                dataBytes = 17, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "J1979 lists PID 9C as a 17-byte oxygen sensor data block (multi-sensor, WWH-OBD era). Not claimed by this car's 0180 bitmap. A 17-byte block cannot be split into honest per-sensor values without a validated frame, so it stays disabled research raw.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 019E Engine exhaust flow rate - CLAIMED and ANSWERED ───────
            PidDefinition(
                id = "019E", service = "01", pid = "9E",
                name = "Engine Exhaust Flow Rate",
                shortName = "Exh Flow", unit = "kg/h",
                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 9E = engine exhaust flow rate in kg/h (5-byte block). This car CLAIMS it (bit set in the 0180 bitmap `00 24 00 0D`) and ANSWERED it in the 2026-09-16 run with `41 9E 00 20`. The scaling of those bytes is not defined in the tables this project cites, so the raw pair is recorded and NO number is published - an earlier revision of this audit guessed '32/255 = 25.6 % load', which is exactly the kind of invented value the no-fake-values rule forbids. Needs one validated frame, or a load sweep against MAF, before it gets a decoder.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01A0 Range marker: PIDs supported [A1-C0] ────────────────────
            PidDefinition(
                id = "01A0", service = "01", pid = "A0",
                name = "Mode 01 PID A0 - range marker (PIDs supported A1-C0)",
                shortName = "Range A1-C0",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "SAE J1979 PID A0 is the availability bitmap for PID A1 to C0 - the same kind of row as PID 00/20/40/60/80. It is NOT a transmission sump temperature. Catalogued as TEMP_MINUS_40 it printed '-20 degC' in the owner export of 2026-09-16: a fabricated gearbox oil temperature from a car that never reported one. Disabled here and excluded from the discovery list by PidDiscoveryDecoder.decodeSupportedPids.",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L,
                unit = "",
                isResearch = true,
                enabled = false
            ),
            // ─── 01A1 Clutch Status / PRNDL ────────────────────────────
            PidDefinition(
                id = "01A1", service = "01", pid = "A1",
                name = "Clutch / PRNDL Status",
                shortName = "PRNDL", unit = "",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Transmission clutch / PRNDL status (bitmask)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 01A2 Transmission Oil Pressure ────────────────────────
            PidDefinition(
                id = "01A2", service = "01", pid = "A2",
                name = "Transmission Oil Pressure",
                shortName = "T Oil P", unit = "kPa",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Automatic transmission line pressure",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01A3 Transmission Oil Temperature ─────────────────────
            PidDefinition(
                id = "01A3", service = "01", pid = "A3",
                name = "Transmission Oil Temperature",
                shortName = "T Oil T", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Automatic transmission oil (sump) temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01A5 OBD Requirements / Vehicle Identification ────────────
            PidDefinition(
                id = "01A5", service = "01", pid = "A5",
                name = "OBD Requirements / Vehicle ID",
                shortName = "OBD Req", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "OBD requirements and vehicle identification data",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01A6 Odometer - defined once in ProvenChannels, referenced here ─
            ProvenChannels.ODOMETER,
            // ─── 01A7 OBD Vehicle ID / Calibration ──────────────────────
            PidDefinition(
                id = "01A7", service = "01", pid = "A7",
                name = "Vehicle ID / Calibration ID",
                shortName = "Cal ID", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "Vehicle calibration identification (partial / encoded)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01A8 OBD ECU Name ─────────────────────────────────────
            PidDefinition(
                id = "01A8", service = "01", pid = "A8",
                name = "ECU Name",
                shortName = "ECU Name", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "ECU name / part number (partial / encoded)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01A9 OBD Readiness Monitor Status ───────────────────
            PidDefinition(
                id = "01A9", service = "01", pid = "A9",
                name = "OBD Readiness Monitor Status",
                shortName = "Readiness", unit = "",
                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,
                description = "Comprehensive OBD readiness/monitor status",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01AA Fuel Rail Pressure (injection) ─────────────────
            PidDefinition(
                id = "01AA", service = "01", pid = "AA",
                name = "Fuel Rail Pressure (Injection)",
                shortName = "FRP Inj", unit = "kPa",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "High pressure fuel rail pressure (common rail)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 01AB Fuel Rail Pressure (vacuum) ────────────────────
            PidDefinition(
                id = "01AB", service = "01", pid = "AB",
                name = "Fuel Rail Pressure (Vacuum)",
                shortName = "FRP Vac", unit = "kPa",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Low pressure fuel rail pressure (vacuum side)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01AC Battery Voltage (extended) ─────────────────────
            PidDefinition(
                id = "01AC", service = "01", pid = "AC",
                name = "Battery Voltage (Extended)",
                shortName = "Batt V Ext", unit = "V",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Battery voltage (0.001V units, alternator direct)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01AD Battery Current ─────────────────────────────────
            PidDefinition(
                id = "01AD", service = "01", pid = "AD",
                name = "Battery Current",
                shortName = "Batt I", unit = "A",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Battery current (amperes, signed)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01AE Battery Temperature ─────────────────────────────
            PidDefinition(
                id = "01AE", service = "01", pid = "AE",
                name = "Battery Temperature",
                shortName = "Batt T", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Battery pack temperature (HEV/PHEV/EV)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01B0 Hybrid/EV System Voltage ───────────────────────
            PidDefinition(
                id = "01B0", service = "01", pid = "B0",
                name = "Hybrid/EV System Voltage",
                shortName = "HV Voltage", unit = "V",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "High-voltage system voltage (HEV/EV)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01B1 Hybrid/EV System Current ───────────────────────
            PidDefinition(
                id = "01B1", service = "01", pid = "B1",
                name = "Hybrid/EV System Current",
                shortName = "HV Current", unit = "A",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "High-voltage system current (HEV/EV)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01B2 Hybrid Battery Pack State of Charge ────────────
            PidDefinition(
                id = "01B2", service = "01", pid = "B2",
                name = "Hybrid Battery Pack SOC",
                shortName = "HV SOC", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "High-voltage battery state of charge (HEV/PHEV/EV)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01B5 ESC Steering Wheel Angle ─────────────────────────
            PidDefinition(
                id = "01B5", service = "01", pid = "B5",
                name = "Steering Wheel Angle",
                shortName = "Steer Ang", unit = "°",
                dataBytes = 2, decoderType = DecoderType.STEERING_ANGLE_SIGNED_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "Live steering wheel angle (degrees, signed: Left negative / Right positive, 0.1° resolution)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 220200 UDS EPS Power Steering Angle (J500 / IDE00384) ──
            PidDefinition(
                id = "220200", service = "22", pid = "0200",
                name = "EPS Steering Wheel Angle (UDS)",
                shortName = "EPS Angle", unit = "°",
                canHeader = "714", expectedRxId = "77E",
                dataBytes = 2, decoderType = DecoderType.STEERING_ANGLE_SIGNED_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "UDS EPS G85 steering wheel angle from Power Steering Module 44 (J500)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 2202B2 UDS ABS Steering Angle & Dynamics (J104 / IDE00815) ──
            PidDefinition(
                id = "2202B2", service = "22", pid = "02B2",
                name = "ABS Steering Angle (UDS)",
                shortName = "ABS Steer", unit = "°",
                canHeader = "713", expectedRxId = "77D",
                dataBytes = 2, decoderType = DecoderType.STEERING_ANGLE_SIGNED_10,
                formulaDisplay = "Signed16(A, B) / 10.0",
                description = "UDS G85 steering wheel angle from ABS/ESC Brake Module 03 (J104)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 01B6 Lateral Acceleration ────────────────────────────
            PidDefinition(
                id = "01B6", service = "01", pid = "B6",
                name = "Lateral Acceleration",
                shortName = "Lat G", unit = "g",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Lateral acceleration (g, signed)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 01B7 Longitudinal Acceleration ───────────────────────
            PidDefinition(
                id = "01B7", service = "01", pid = "B7",
                name = "Longitudinal Acceleration",
                shortName = "Long G", unit = "g",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Longitudinal acceleration (g, signed)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 01B8 Yaw Rate ────────────────────────────────────────
            PidDefinition(
                id = "01B8", service = "01", pid = "B8",
                name = "Yaw Rate",
                shortName = "Yaw Rate", unit = "°/s",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Yaw rate (degrees per second, signed)",
                priority = PollingPriority.MEDIUM,
                defaultIntervalMs = 500L
            ),
            // ─── 01BA Brake Pedal Position ────────────────────────────
            PidDefinition(
                id = "01BA", service = "01", pid = "BA",
                name = "Brake Pedal Position",
                shortName = "Brake Pos", unit = "%",
                dataBytes = 1, decoderType = DecoderType.PERCENT_255,
                description = "Brake pedal travel position (0-100%)",
                priority = PollingPriority.FAST,
                defaultIntervalMs = 150L
            ),
            // ─── 01BB Cruise Control Status ───────────────────────────
            PidDefinition(
                id = "01BB", service = "01", pid = "BB",
                name = "Cruise Control Status",
                shortName = "CC Status", unit = "",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Cruise control system status (bitmask)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01BC Cruise Control Set Speed ───────────────────────
            PidDefinition(
                id = "01BC", service = "01", pid = "BC",
                name = "Cruise Control Set Speed",
                shortName = "CC Speed", unit = "km/h",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                description = "Cruise control set speed (km/h)",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            ),
            // ─── 01BD Ambient Air Temperature ────────────────────────
            PidDefinition(
                id = "01BD", service = "01", pid = "BD",
                name = "Ambient Air Temperature",
                shortName = "Amb T", unit = "°C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Outside / ambient air temperature",
                priority = PollingPriority.SLOW,
                defaultIntervalMs = 3000L
            )
        )

        for (item in additional) {
            if (list.none { it.id.equals(item.id, ignoreCase = true) }) {
                list.add(item)
            }
        }

        list.associateBy { it.hexPid }
    }

    /**
     * Resolves a PidDefinition for a discovered hex PID (e.g. "0C", "010C").
     */
    fun lookup(hexPid: String, isSupported: Boolean = true): PidDefinition {
        val clean = hexPid.uppercase().removePrefix("01").padStart(2, '0')
        val fullId = "01$clean"

        val found = catalog[clean] ?: catalog[fullId]
        if (found != null) {
            return found.copy(supported = isSupported)
        }

        // Generic fallback for any uncataloged standard PID
        return PidDefinition(
            id = fullId,
            service = "01",
            pid = clean,
            name = "Mode 01 PID $clean",
            shortName = "PID $clean",
            unit = "RAW",
            dataBytes = 1,
            decoderType = DecoderType.RESEARCH_RAW,
            formulaDisplay = "Raw Value",
            supported = isSupported,
            isResearch = true,
            description = "Standard SAE J1979 OBD-II Mode 01 Parameter ($clean) whose meaning is " +
                "not catalogued yet, so it is captured as research raw and never presented as a " +
                "known physical value.",
            // Conservative explicit interval for uncatalogued discoveries (2026-09-14:
            // no PID may rely on a constructor default any more). Tightened 2026-09-17:
            // "Apply to Live Polling" adds every discovered PID with enabled = true, and a
            // MEDIUM / 500 ms slot for a channel of unknown meaning only burns bus bandwidth
            // that the FAST channels (rpm, speed, throttle, torque) need for gear and power
            // pairing. Unknown things are recorded slowly and honestly, never polled fast.
            priority = PollingPriority.SLOW,
            defaultIntervalMs = 3000L
        )
    }

    fun getAllKnownPids(): List<PidDefinition> = catalog.values.toList()
}
