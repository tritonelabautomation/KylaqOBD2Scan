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
     * Payload expected without the service byte (e.g. if ECU replied 43 01 04, payload is 0104)
     */
    fun extractDtcs(payloadHex: String): List<String> {
        val cleanHex = payloadHex.replace(" ", "")
        val dtcs = mutableListOf<String>()
        
        // Skip service byte if present (43 for Mode 03, 47 for Mode 07)
        val dataHex = if (cleanHex.startsWith("43") || cleanHex.startsWith("47")) {
            cleanHex.substring(2)
        } else {
            cleanHex
        }

        // Often the first byte is the number of DTCs? Actually Mode 03/07 does NOT contain the count byte.
        // It's just a raw list of DTCs.
        
        for (i in 0 until (dataHex.length - 3) step 4) {
            val chunk = dataHex.substring(i, i + 4)
            val dtc = decodeDtcHex(chunk)
            if (dtc != null) {
                dtcs.add(dtc)
            }
        }
        
        return dtcs.distinct()
    }
}
