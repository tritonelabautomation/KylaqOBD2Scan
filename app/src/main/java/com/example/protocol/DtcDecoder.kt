package com.example.protocol

object DtcDecoder {
    /**
     * Decodes a 2-byte sequence into a standard 5-character OBD-II DTC (e.g., "P0104")
     */
    fun decodeDtcHex(hex: String): String? {
        if (hex.length != 4) return null
        
        val highByte = hex.substring(0, 2).toIntOrNull(16) ?: return null
        val lowByte = hex.substring(2, 4).toIntOrNull(16) ?: return null
        
        if (highByte == 0 && lowByte == 0) return null // Padding
        
        val systemCategory = (highByte and 0xC0) shr 6
        val systemChar = when (systemCategory) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            3 -> 'U'
            else -> 'P'
        }
        
        val secondChar = (highByte and 0x30) shr 4
        
        val thirdChar = (highByte and 0x0F).toString(16).uppercase()
        val fourthChar = (lowByte and 0xF0) shr 4
        val fifthChar = (lowByte and 0x0F)
        
        return "$systemChar$secondChar$thirdChar${fourthChar.toString(16).uppercase()}${fifthChar.toString(16).uppercase()}"
    }

    /**
     * Decodes a Mode 03 or Mode 07 payload into a list of DTC strings.
     * Payload expected WITHOUT the service byte (e.g. if ECU replied 43 01 04, payload is 0104).
     *
     * FIX P0-5: Strictly validates the response shape before decoding to prevent
     * garbage/numeric interpretation of malformed or non-positive responses.
     *
     * @param payloadHex hex payload (with or without service byte 43/47)
     * @param mode 0x03 for active DTCs, 0x07 for pending DTCs. Used to determine the
     *             expected positive response service byte.
     * @return list of decoded DTC codes; empty list if the response is invalid or negative.
     */
    fun extractDtcs(payloadHex: String, mode: Int = 0x03): List<String> {
        val cleanHex = payloadHex.replace(" ", "").uppercase()
        if (cleanHex.length < 2) return emptyList()

        // Reject negative responses (7F service NRC) and any response that doesn't start
        // with the expected positive service byte (43 for Mode 03, 47 for Mode 07).
        val expectedAck = (mode + 0x40) and 0xFF
        val expectedAckHex = "%02X".format(expectedAck)
        if (cleanHex.startsWith("7F")) {
            // Negative response - caller should handle NRC separately.
            return emptyList()
        }
        if (!cleanHex.startsWith(expectedAckHex)) {
            // Not a positive response for the requested mode. Don't decode.
            return emptyList()
        }

        val dtcs = mutableListOf<String>()
        val dataHex = cleanHex.substring(2)

        // Mode 03 / 07 payload is a raw list of 2-byte DTCs; no count byte.
        // Also reject zero-padded pairs (00 00) so we don't emit bogus DTCs.
        var i = 0
        while (i + 4 <= dataHex.length) {
            val chunk = dataHex.substring(i, i + 4)
            val dtc = decodeDtcHex(chunk)
            if (dtc != null) {
                dtcs.add(dtc)
            }
            i += 4
        }

        return dtcs.distinct()
    }
}
