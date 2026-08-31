package com.example.model

object ProfileDefinitions {

    val standardObdRequests = listOf(
        DiagnosticRequest("0104", "Engine Load", "01", "04", "Calculated engine load", DecoderType.PERCENT_255),
        DiagnosticRequest("0105", "Coolant Temp", "01", "05", "Engine coolant temperature", DecoderType.TEMP_MINUS_40),
        DiagnosticRequest("0106", "STFT Bank 1", "01", "06", "Short-term fuel trim Bank 1", DecoderType.FUEL_TRIM),
        DiagnosticRequest("0107", "LTFT Bank 1", "01", "07", "Long-term fuel trim Bank 1", DecoderType.FUEL_TRIM),
        DiagnosticRequest("010B", "Intake MAP", "01", "0B", "Intake manifold absolute pressure", DecoderType.RAW_A_KPA),
        DiagnosticRequest("010C", "Engine RPM", "01", "0C", "Engine RPM", DecoderType.RPM_FORMULA),
        DiagnosticRequest("010D", "Vehicle Speed", "01", "0D", "Vehicle speed", DecoderType.RAW_A_KMH),
        DiagnosticRequest("010E", "Timing Advance", "01", "0E", "Ignition timing advance", DecoderType.TIMING_ADVANCE),
        DiagnosticRequest("010F", "Intake Air Temp", "01", "0F", "Intake air temperature", DecoderType.TEMP_MINUS_40),
        DiagnosticRequest("0111", "Throttle Position", "01", "11", "Throttle position", DecoderType.PERCENT_255),
        DiagnosticRequest("0142", "ECU Voltage", "01", "42", "Control module voltage", DecoderType.VOLTAGE_1000),
        DiagnosticRequest("0144", "Equivalence Ratio", "01", "44", "Commanded equivalence ratio", DecoderType.EQUIVALENCE_RATIO),
        DiagnosticRequest("0145", "Rel Throttle Pos", "01", "45", "Relative throttle position", DecoderType.PERCENT_255),
        DiagnosticRequest("0149", "Accel Pedal D", "01", "49", "Accelerator pedal position D", DecoderType.PERCENT_255),
        DiagnosticRequest("014C", "Cmd Throttle", "01", "4C", "Commanded throttle actuator", DecoderType.PERCENT_255)
    )

    val vagExperimentalRequests = listOf(
        DiagnosticRequest("01A6", "VW Candidate A6", "01", "A6", "Experimental EA211 value", DecoderType.RESEARCH_RAW),
        DiagnosticRequest("01B0", "VW Candidate B0", "01", "B0", "Candidate Boost pressure target", DecoderType.RESEARCH_RAW),
        DiagnosticRequest("01B1", "VW Candidate B1", "01", "B1", "Candidate Boost pressure actual", DecoderType.RESEARCH_RAW),
        DiagnosticRequest("01C0", "VW Candidate C0", "01", "C0", "Candidate Oil temp", DecoderType.RESEARCH_RAW)
    )

    val udsExperimentalRequests = listOf(
        DiagnosticRequest("22F40C", "UDS RPM", "22", "F40C", "Engine RPM (UDS fallback)", DecoderType.RESEARCH_RAW, isUds = true),
        DiagnosticRequest("22F40D", "UDS Speed", "22", "F40D", "Vehicle Speed (UDS fallback)", DecoderType.RESEARCH_RAW, isUds = true),
        DiagnosticRequest("220200", "UDS VW Generic 1", "22", "0200", "VAG specific block 0200", DecoderType.RESEARCH_RAW, isUds = true),
        DiagnosticRequest("221000", "UDS VW Generic 2", "22", "1000", "VAG specific block 1000", DecoderType.RESEARCH_RAW, isUds = true)
    )

    val profiles = listOf(
        DiagnosticProfile(
            id = "standard_obd",
            name = "Standard OBD-II",
            type = ProfileType.STANDARD_OBD,
            description = "Standard ISO 15765-4 CAN diagnostics. Tests official SAE J1979 PIDs.",
            requests = standardObdRequests
        ),
        DiagnosticProfile(
            id = "vag_10tsi",
            name = "VW/VAG 1.0 TSI - Experimental",
            type = ProfileType.VAG_EXPERIMENTAL,
            description = "Tests candidate manufacturer-specific PIDs on the 1.0 TSI EA211 engine.",
            requests = vagExperimentalRequests
        ),
        DiagnosticProfile(
            id = "uds_readonly",
            name = "UDS Read-Only - Experimental",
            type = ProfileType.UDS_EXPERIMENTAL,
            description = "Tests ReadDataByIdentifier (0x22) for UDS. Strictly read-only.",
            requests = udsExperimentalRequests
        ),
        DiagnosticProfile(
            id = "discovery",
            name = "1.0 TSI Discovery",
            type = ProfileType.DISCOVERY,
            description = "Iterates through PIDs and DIDs to record responses/timeouts for analysis.",
            requests = standardObdRequests + vagExperimentalRequests + udsExperimentalRequests
        )
    )
}
