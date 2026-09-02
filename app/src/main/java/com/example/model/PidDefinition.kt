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
    FUEL_RATE_MASS_10,    // ((A * 256) + B) / 10.0 (Mass: g/s) - PID 019D
    FUEL_PRESSURE_3_KPA,  // A * 3 (kPa gauge) - PID 010A
    MAF_100,              // ((A * 256) + B) / 100.0 (Air Flow: g/s) - PID 0110
    INJECTION_TIMING_128, // ((((A * 256) + B) - 26880) / 128.0) ° - PID 015D
    TRANSMISSION_GEAR_A4, // SAE J1979 PID 01A4 Actual Gear Ratio / Status
    RESEARCH_RAW,         // Preserve raw bytes without calculation
    CUSTOM_EXPRESSION     // Custom expression if user defined
}

/**
 * Priority polling tier to prevent bus overload
 */
enum class PollingPriority {
    FAST,   // 100-250ms: RPM, Speed, Throttle, Accelerator, Torque
    MEDIUM, // 400-800ms: Fuel rates, MAP, MAF, Load, Coolant, Rail Pressure
    SLOW    // 2000-5000ms: Fuel type, Ethanol %, Fuel level, Baro, Voltage, Ambient
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
    val defaultIntervalMs: Long = 250L,
    val enabled: Boolean = true,
    val decoderType: DecoderType = DecoderType.RESEARCH_RAW,
    val formulaDisplay: String = "",
    val isResearch: Boolean = false,
    val description: String = "",
    val priority: PollingPriority = PollingPriority.MEDIUM
)

object DefaultPidDefinitions {
    fun getDefaults(): List<PidDefinition> {
        return listOf(
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
                decoderType = DecoderType.FUEL_RATE_MASS_10,
                formulaDisplay = "((A * 256) + B) / 10.0",
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
                unit = "°",
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
                unit = "λ",
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
                defaultIntervalMs = 600L,
                enabled = true,
                decoderType = DecoderType.PERCENT_255,
                formulaDisplay = "A * 100 / 255",
                description = "Relative throttle plate opening",
                priority = PollingPriority.MEDIUM
            ),
            PidDefinition(
                id = "015D",
                service = "01",
                pid = "5D",
                name = "Engine Fuel Injection Timing",
                shortName = "Inj Timing",
                unit = "°",
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

            // SLOW TIER (Tank Level, Fuel Type, Ethanol, Voltage, Environment)
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
                description = "Radiator outlet or secondary coolant temperature",
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
            ),
            PidDefinition(
                id = "01A6",
                service = "01",
                pid = "A6",
                name = "Unknown Research PID 01A6",
                shortName = "Research [A6]",
                unit = "RAW",
                canHeader = "7DF",
                expectedRxId = "7E8",
                defaultIntervalMs = 1500L,
                enabled = true,
                decoderType = DecoderType.RESEARCH_RAW,
                formulaDisplay = "Raw Hex Preservation",
                isResearch = true,
                description = "EA211 experimental telemetry channel",
                priority = PollingPriority.SLOW
            )
        )
    }
}
