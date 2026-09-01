package com.example.protocol

/**
 * Raw CAN frame model extracted from ELM327 output
 */
data class RawCanFrame(
    val rawLine: String,
    val canId: String?,
    val dataBytes: List<Int>,
    val dataHex: String,
    val isIsoTp: Boolean,
    val pciType: IsoTpPciType,
    val payloadBytes: List<Int>
)

enum class IsoTpPciType {
    SINGLE_FRAME,         // 0x0_
    FIRST_FRAME,          // 0x1_
    CONSECUTIVE_FRAME,    // 0x2_
    FLOW_CONTROL,         // 0x3_
    NON_ISO_TP            // Direct payload (e.g. when ELM327 headers off or custom)
}

/**
 * Parses individual CAN frame lines from ELM327 output
 */
object CanFrameParser {

    /**
     * Parses a single line of ELM327 RX text into a structured CAN frame.
     * Examples:
     * "7E8 03 41 0C 0F 2C" -> canId: "7E8", pci: SINGLE_FRAME, payload: [0x41, 0x0C, 0x0F, 0x2C]
     * "7E8 10 14 49 02 01 00 00 00" -> canId: "7E8", pci: FIRST_FRAME
     * "7E8 21 31 32 33 34 35 36 37" -> canId: "7E8", pci: CONSECUTIVE_FRAME
     * "41 0C 0F 2C" (headers off) -> canId: null, pci: NON_ISO_TP
     */
    fun parseFrame(line: String): RawCanFrame {
        var trimmed = line.trim().uppercase()
        
        // Strip line indexing prefixes like "0:", "1:", "01:", etc.
        if (trimmed.matches(Regex("^[0-9A-F]{1,3}:.*"))) {
            trimmed = trimmed.substringAfter(":").trim()
        }

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            return RawCanFrame(
                rawLine = line,
                canId = null,
                dataBytes = emptyList(),
                dataHex = "",
                isIsoTp = false,
                pciType = IsoTpPciType.NON_ISO_TP,
                payloadBytes = emptyList()
            )
        }

        var canId: String? = null
        val byteTokens: MutableList<String> = mutableListOf()

        if (tokens.size >= 2) {
            // Space separated: Check if first token is a 3, 4 or 8 hex-digit CAN ID (e.g. 7E8, 7DF, 07E8, 18DAF110)
            val firstToken = tokens[0]
            val restTokens = tokens.drop(1)
            val restAreHexBytes = restTokens.all { it.matches(Regex("^[0-9A-F]{1,2}$")) }

            if (firstToken.matches(Regex("^[0-9A-F]{3,8}$")) && restAreHexBytes) {
                canId = firstToken
                byteTokens.addAll(restTokens.map { if (it.length == 1) "0$it" else it })
            } else if (tokens.all { it.matches(Regex("^[0-9A-F]{1,2}$")) }) {
                canId = null
                byteTokens.addAll(tokens.map { if (it.length == 1) "0$it" else it })
            }
        }

        if (byteTokens.isEmpty()) {
            // Single continuous hex string or unspaced tokens (e.g. "7E904410C0000", "7E803410D00", "410C0000")
            val cleanHex = trimmed.replace(" ", "")
            if (cleanHex.matches(Regex("^[0-9A-F]+$"))) {
                // Check 29-bit CAN ID (8 hex chars header + even length payload >= 2)
                if (cleanHex.length >= 10 && (cleanHex.startsWith("18DA") || cleanHex.startsWith("18DB") || cleanHex.startsWith("18EA") || cleanHex.startsWith("18EC")) && (cleanHex.length - 8) % 2 == 0) {
                    canId = cleanHex.substring(0, 8)
                    byteTokens.addAll(cleanHex.substring(8).chunked(2))
                }
                // Check 11-bit CAN ID (3 hex chars header e.g. 7E8, 7E9, 7DF, 7E0..7EF where remainder is even and >= 2)
                else if (cleanHex.length >= 5 && (cleanHex.length - 3) % 2 == 0 && (cleanHex.startsWith("7E") || cleanHex.startsWith("7D") || cleanHex.startsWith("7F") || cleanHex.startsWith("18") || cleanHex.take(3).matches(Regex("^[0-9A-F]{3}$")))) {
                    canId = cleanHex.substring(0, 3)
                    byteTokens.addAll(cleanHex.substring(3).chunked(2))
                }
                // Check 4-char CAN ID (e.g. 07E8 where remainder is even and >= 2)
                else if (cleanHex.length >= 6 && cleanHex.startsWith("07E") && (cleanHex.length - 4) % 2 == 0) {
                    canId = cleanHex.substring(0, 4)
                    byteTokens.addAll(cleanHex.substring(4).chunked(2))
                }
                // Check raw byte sequence without header (even length)
                else if (cleanHex.length % 2 == 0) {
                    canId = null
                    byteTokens.addAll(cleanHex.chunked(2))
                }
            }
        }

        if (byteTokens.isEmpty()) {
            return RawCanFrame(
                rawLine = line,
                canId = null,
                dataBytes = emptyList(),
                dataHex = "",
                isIsoTp = false,
                pciType = IsoTpPciType.NON_ISO_TP,
                payloadBytes = emptyList()
            )
        }

        val dataBytes = byteTokens.mapNotNull { it.toIntOrNull(16) }
        val dataHex = dataBytes.joinToString("") { "%02X".format(it) }

        if (dataBytes.isEmpty()) {
            return RawCanFrame(
                rawLine = line,
                canId = canId,
                dataBytes = emptyList(),
                dataHex = "",
                isIsoTp = false,
                pciType = IsoTpPciType.NON_ISO_TP,
                payloadBytes = emptyList()
            )
        }

        // Check ISO 15765-2 PCI Byte
        val firstByte = dataBytes[0]
        val pciNibble = (firstByte ushr 4) and 0x0F

        return when (pciNibble) {
            0 -> {
                // Single Frame: length is lower nibble (1..7)
                val length = firstByte and 0x0F
                val payload = if (length in 1..(dataBytes.size - 1)) {
                    dataBytes.subList(1, 1 + length)
                } else if (dataBytes.size > 1) {
                    dataBytes.drop(1)
                } else {
                    dataBytes
                }
                RawCanFrame(
                    rawLine = line,
                    canId = canId,
                    dataBytes = dataBytes,
                    dataHex = dataHex,
                    isIsoTp = true,
                    pciType = IsoTpPciType.SINGLE_FRAME,
                    payloadBytes = payload
                )
            }
            1 -> {
                // First Frame: 12-bit length in lower nibble and byte 1
                RawCanFrame(
                    rawLine = line,
                    canId = canId,
                    dataBytes = dataBytes,
                    dataHex = dataHex,
                    isIsoTp = true,
                    pciType = IsoTpPciType.FIRST_FRAME,
                    payloadBytes = if (dataBytes.size > 2) dataBytes.drop(2) else emptyList()
                )
            }
            2 -> {
                // Consecutive Frame: sequence number in lower nibble
                RawCanFrame(
                    rawLine = line,
                    canId = canId,
                    dataBytes = dataBytes,
                    dataHex = dataHex,
                    isIsoTp = true,
                    pciType = IsoTpPciType.CONSECUTIVE_FRAME,
                    payloadBytes = if (dataBytes.size > 1) dataBytes.drop(1) else emptyList()
                )
            }
            3 -> {
                // Flow Control Frame
                RawCanFrame(
                    rawLine = line,
                    canId = canId,
                    dataBytes = dataBytes,
                    dataHex = dataHex,
                    isIsoTp = true,
                    pciType = IsoTpPciType.FLOW_CONTROL,
                    payloadBytes = emptyList()
                )
            }
            else -> {
                // Direct OBD response without ISO-TP PCI byte (or ELM formatted)
                RawCanFrame(
                    rawLine = line,
                    canId = canId,
                    dataBytes = dataBytes,
                    dataHex = dataHex,
                    isIsoTp = false,
                    pciType = IsoTpPciType.NON_ISO_TP,
                    payloadBytes = dataBytes
                )
            }
        }
    }
}
