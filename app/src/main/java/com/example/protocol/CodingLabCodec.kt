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
