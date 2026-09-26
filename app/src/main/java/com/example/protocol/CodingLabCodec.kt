package com.example.protocol

/**
 * Read-only UDS Coding Lab helpers (added 2026-09-09).
 *
 * The app's SafetyValidator allows service 0x22 (ReadDataByIdentifier) and blocks
 * 0x2E writes, 0x27 security access, 0x10 session control and all flash services -
 * so this lab can INSPECT adaptation/DID contents exactly like a scout tool, but
 * can never modify vehicle coding. See docs/reference/vag-coding-research.md for
 * why theme/sport-menu coding needs ODIS/OBDeleven-class seed-key access.
 */
object CodingLabCodec {

    /** Accepts "F190", "f190", "0xF190"; pads 2-hex input to a 16-bit DID. Null when invalid. */
    fun normalizeDid(input: String): String? {
        val raw = input.trim().removePrefix("0x").removePrefix("0X").uppercase()
        if (!raw.matches(Regex("^[0-9A-F]{2,4}$"))) return null
        return raw.padStart(4, '0')
    }

    /** Accepts 3-hex (7E0) or 8-hex (007E0001 style) CAN headers. */
    fun normalizeHeader(input: String): String? {
        val raw = input.trim().removePrefix("0x").removePrefix("0X").uppercase()
        return if (raw.matches(Regex("^[0-9A-F]{3}$")) || raw.matches(Regex("^[0-9A-F]{8}$"))) raw else null
    }

    fun readRequest(did: String): String = "22$did"

    /** UDS negative-response codes relevant to 0x22 reads. */
    fun nrcName(code: String): String = when (code.uppercase()) {
        "10" -> "generalReject"
        "11" -> "serviceNotSupported"
        "12" -> "subFunctionNotSupported"
        "13" -> "incorrectMessageLengthOrFormat"
        "31" -> "requestOutOfRange (DID not present in this ECU)"
        "33" -> "securityAccessDenied (coding data locked behind seed-key)"
        "7E" -> "subFunctionNotSupportedInActiveSession"
        else -> "NRC 0x$code"
    }

    /** True with the NRC byte when the response is a UDS negative response for [service]. */
    fun negativeNrc(hexPayload: String, service: String = "22"): String? {
        val clean = hexPayload.replace(" ", "").uppercase()
        return if (clean.startsWith("7F$service") && clean.length >= 6) clean.substring(4, 6) else null
    }

    /** Joins multi-ECU/multi-frame 62<DID> payloads and strips the echo header. */
    fun decodePositive(lines: List<String>, did: String): String {
        val sb = StringBuilder()
        for (line in lines) {
            val clean = line.replace(" ", "").uppercase()
            val idx = clean.indexOf("62$did")
            if (idx >= 0) sb.append(clean.substring(idx + 4 + 2))
        }
        return sb.toString()
    }

    /**
     * Classifies a raw 0x22 response for the ECU sweep: POSITIVE (62+DID echo),
     * NRC:<code> (module present but refused - security/session), or SILENT
     * (nothing came back - no module at that header).
     */
    fun classifyResponse(raw: String, did: String): String {
        val clean = raw.replace(" ", "").uppercase()
        if (clean.contains("62$did")) return "POSITIVE"
        val idx = clean.indexOf("7F22")
        if (idx >= 0 && clean.length >= idx + 6) return "NRC:" + clean.substring(idx + 4, idx + 6)
        if (clean.isBlank() || clean.contains("NODATA") || clean.contains("BUSINIT") ||
            clean.contains("BUSBUSY") || clean.contains("STOPPED") || clean.contains("CANTXERROR") ||
            clean.contains("BUSERROR") || clean.contains("UNABLETOCONNECT")
        ) return "SILENT"
        return "SILENT"
    }

    /** Headers swept by the lab, in VAG-conventional order. */
    val SWEEP_HEADERS = listOf("7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7")

    /** Complete multi-ECU headers across powertrain, chassis, body, safety and infotainment. */
    val ALL_MODULE_HEADERS = listOf("7E0", "7E1", "710", "711", "713", "714", "715", "716", "740")

    /** Human-readable ECU name for a CAN request header. */
    fun ecuNameForHeader(header: String): String = when (header.uppercase()) {
        "7E0" -> "01 Engine (ECM MED17.1.27)"
        "7E1" -> "02 Transmission (TCU 6-AT)"
        "710" -> "09 BCM / 19 Gateway (J519/J533)"
        "711" -> "08 Climatronic (J255)"
        "713" -> "03 ABS / Brakes (J104)"
        "714" -> "44 EPS (J500) / 17 Cluster (J285)"
        "715" -> "15 Airbag (J234)"
        "716" -> "76 Park Assist (J446)"
        "740" -> "5F Infotainment (MIB3)"
        else -> "ECU Header $header"
    }

    /** Pre-configured MQB-A0 adaptation channels for inspection. */
    val MQB_ADAPTATIONS = listOf(
        MqbAdaptationPreset(
            id = "NEEDLE_STAGING",
            name = "Gauge Needle Sweep / Staging",
            moduleName = "17 Instrument Cluster (J285)",
            canHeader = "714",
            did = "040F",
            description = "Sweeps speedometer and tachometer needles to max on ignition startup.",
            defaultInterpretation = "Channel 040F / Staging"
        ),
        MqbAdaptationPreset(
            id = "ACOUSTIC_LOCK",
            name = "Acoustic Lock Confirmation (Horn Chirp)",
            moduleName = "09 Central Electrics (BCM)",
            canHeader = "710",
            did = "0225",
            description = "Acoustic horn confirmation when all doors and boot are locked via key fob.",
            defaultInterpretation = "Channel 0225 / Acoustic Lock"
        ),
        MqbAdaptationPreset(
            id = "COMFORT_BLINKER",
            name = "Convenience Turn Signal Cycles",
            moduleName = "09 Central Electrics (BCM)",
            canHeader = "710",
            did = "0245",
            description = "Configures touch-turn signal flash count (3, 4, or 5 cycles).",
            defaultInterpretation = "Channel 0245 / Comfort Blinker Cycles"
        ),
        MqbAdaptationPreset(
            id = "DRL_MENU",
            name = "DRL Infotainment Menu Toggle",
            moduleName = "09 Central Electrics (BCM)",
            canHeader = "710",
            did = "0268",
            description = "Exposes Daytime Running Lights ON/OFF checkbox in the touchscreen settings menu.",
            defaultInterpretation = "Channel 0268 / DRL Menu Switch"
        ),
        MqbAdaptationPreset(
            id = "TEAR_WIPE",
            name = "Tear-Drop After-Wipe (Wipers)",
            moduleName = "09 Central Electrics (BCM)",
            canHeader = "710",
            did = "028C",
            description = "Executes an automatic single sweep 5 seconds after windshield washer spray.",
            defaultInterpretation = "Channel 028C / Tear-Drop Wiping"
        ),
        MqbAdaptationPreset(
            id = "MIRROR_AUTOFOLD",
            name = "Mirror Auto-Fold on Lock",
            moduleName = "09 Central Electrics (BCM)",
            canHeader = "710",
            did = "02AE",
            description = "Folds side mirrors automatically upon central locking confirmation.",
            defaultInterpretation = "Channel 02AE / Mirror Folding"
        ),
        MqbAdaptationPreset(
            id = "LAP_TIMER",
            name = "Digital Cluster Lap Timer",
            moduleName = "17 Instrument Cluster (J285)",
            canHeader = "714",
            did = "0412",
            description = "Enables racing lap timer and oil temp page inside the multi-function display.",
            defaultInterpretation = "Channel 0412 / Lap Timer"
        )
    )

    /** Hex pairs to printable ASCII (VIN, part numbers...). Non-printables become '.'. */
    fun hexToAscii(hex: String): String {
        val clean = hex.replace(" ", "")
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < clean.length) {
            val v = clean.substring(i, i + 2).toIntOrNull(16)
            if (v != null && v in 32..126) sb.append(v.toChar()) else sb.append('.')
            i += 2
        }
        return sb.toString()
    }
}

data class MqbAdaptationPreset(
    val id: String,
    val name: String,
    val moduleName: String,
    val canHeader: String,
    val did: String,
    val description: String,
    val defaultInterpretation: String
)
