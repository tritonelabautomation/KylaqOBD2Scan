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
        val trimmed = line.trim().uppercase()
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

        // Check if first token is a 3 or 4 hex-digit or 8 hex-digit CAN ID (e.g. 7E8, 7DF, 18DAF110)
        val hasCanIdHeader = tokens.size >= 2 && tokens[0].matches(Regex("^[0-9A-F]{3,8}$")) &&
                tokens.drop(1).all { it.matches(Regex("^[0-9A-F]{1,2}$")) }

        val canId: String?
        val byteTokens: List<String>

        if (hasCanIdHeader) {
            canId = tokens[0]
            byteTokens = tokens.drop(1)
        } else {
            // Check if all tokens are hex bytes
            val allHex = tokens.all { it.matches(Regex("^[0-9A-F]{1,2}$")) }
            if (allHex) {
                canId = null
                byteTokens = tokens
            } else {
                // If it's something like "0: 49 02 01"
                if (tokens[0].endsWith(":") && tokens.size > 1) {
                    canId = null
                    byteTokens = tokens.drop(1).filter { it.matches(Regex("^[0-9A-F]{1,2}$")) }
                } else {
                    // Try parsing continuous hex string e.g. "410C0F2C"
                    val continuous = trimmed.replace(" ", "")
                    if (continuous.matches(Regex("^[0-9A-F]+$")) && continuous.length % 2 == 0) {
                        canId = null
                        byteTokens = continuous.chunked(2)
                    } else {
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
                }
            }
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
                // Single Frame: length is lower nibble
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
