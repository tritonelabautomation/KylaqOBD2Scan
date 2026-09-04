package com.example.protocol

/**
 * Dedicated OBD-II Mode 01 Capability Bitmap Decoder.
 *
 * Implements SAE J1979 / ISO 15031-5 PID availability discovery decoding.
 * Decodes the 32-bit (4-byte) bitmask returned in response to PID discovery queries
 * (e.g., 0100, 0120, 0140, 0160, 0180, 01A0, 01C0, 01E0).
 *
 * Bit Ordering:
 * Each query returns 4 bytes (32 bits).
 * - Byte 0, Bit 7 (MSB) -> basePid + 0x01
 * - Byte 0, Bit 6       -> basePid + 0x02
 * - ...
 * - Byte 3, Bit 1       -> basePid + 0x1F (31st PID)
 * - Byte 3, Bit 0 (LSB) -> basePid + 0x20 (32nd PID: indicates support for next 32 PIDs)
 */
object PidDiscoveryDecoder {

    /**
     * Decodes supported PIDs from a 4-byte availability bitmap for a given base PID.
     *
     * @param basePid The base PID offset (e.g. 0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0).
     * @param bitmap 4-byte array containing the 32-bit availability mask.
     * @return List of supported PID integer numbers.
     */
    fun decodeSupportedPids(basePid: Int, bitmap: ByteArray): List<Int> {
        if (bitmap.size < 4) return emptyList()
        val supported = mutableListOf<Int>()

        for (i in 0 until 32) {
            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8)
            val bit = ((bitmap[byteIndex].toInt() and 0xFF) ushr bitIndex) and 0x01
            if (bit == 1) {
                supported.add(basePid + (i + 1))
            }
        }
        return supported
    }

    /**
     * Checks if the 32nd PID is supported, which indicates support for the next 32-PID range block.
     *
     * @param bitmap 4-byte availability bitmap.
     * @return true if bit 0 of byte 3 is set (1), false otherwise.
     */
    fun hasNextRange(bitmap: ByteArray): Boolean {
        if (bitmap.size < 4) return false
        return (bitmap[3].toInt() and 0x01) != 0
    }

    /**
     * Extracts the 4-byte availability bitmap from ELM327 / CAN response lines.
     *
     * Handles:
     * - Spaced format: "41 00 BE 3E B8 13"
     * - CAN-framed format: "7E8 06 41 00 BE 3F B8 13"
     * - Compact/Unspaced format: "4100BE3EB813"
     * - Multiple lines / multi-ECU responses
     *
     * @param basePid The expected base PID (e.g. 0x00 for 0100).
     * @param responseLines Raw lines received from the OBD transport.
     * @return 4-byte ByteArray if successfully parsed, null if NO DATA, negative response, or invalid.
     */
    fun extractBitmap(basePid: Int, responseLines: List<String>): ByteArray? {
        val expectedService = "41"
        val expectedPidHex = "%02X".format(basePid).uppercase()

        for (line in responseLines) {
            val trimmed = line.trim().uppercase()

            // Discard error and informational tokens
            if (trimmed.isEmpty() ||
                trimmed.contains("NO DATA") ||
                trimmed.contains("ERROR") ||
                trimmed.contains("STOPPED") ||
                trimmed.contains("SEARCHING") ||
                trimmed.contains("UNABLE TO CONNECT") ||
                trimmed.contains("BUS INIT") ||
                trimmed.contains("?")
            ) {
                continue
            }

            // Check if response contains negative response code 7F
            if (trimmed.contains("7F")) {
                continue
            }

            // Strategy 1: Tokenize by whitespace
            val tokens = trimmed.split(Regex("[^0-9A-F]+")).filter { it.isNotBlank() }
            val matchIdx = tokens.indexOfFirst { it == expectedService }
            if (matchIdx != -1 && matchIdx + 5 < tokens.size && tokens[matchIdx + 1] == expectedPidHex) {
                val b0 = tokens[matchIdx + 2].toIntOrNull(16)
                val b1 = tokens[matchIdx + 3].toIntOrNull(16)
                val b2 = tokens[matchIdx + 4].toIntOrNull(16)
                val b3 = tokens[matchIdx + 5].toIntOrNull(16)
                if (b0 != null && b1 != null && b2 != null && b3 != null) {
                    return byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
                }
            }

            // Strategy 2: Compact hex match (e.g. "4100BE3EB813" or inside CAN frame "7E8064100BE3FB813")
            val hexOnly = trimmed.replace(Regex("[^0-9A-F]"), "")
            val pattern = "$expectedService$expectedPidHex"
            val index = hexOnly.indexOf(pattern)
            if (index != -1 && index + 4 + 8 <= hexOnly.length) {
                val hexBytes = hexOnly.substring(index + 4, index + 12)
                val bytes = hexBytes.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }
                if (bytes.size == 4) {
                    return bytes.toByteArray()
                }
            }
        }
        return null
    }

    /**
     * Decodes a discovery response directly from raw lines into structured discovery data.
     */
    fun decodeFromRawResponse(basePid: Int, responseLines: List<String>): DiscoveryRangeResult? {
        val bitmap = extractBitmap(basePid, responseLines) ?: return null
        val supported = decodeSupportedPids(basePid, bitmap)
        val hasNext = hasNextRange(bitmap)
        val bitmapHex = bitmap.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

        val allTested = (1..32).map { basePid + it }

        return DiscoveryRangeResult(
            basePid = basePid,
            bitmap = bitmap,
            bitmapHex = bitmapHex,
            supportedPids = supported,
            allTestedPids = allTested,
            hasNextRange = hasNext
        )
    }
}

/**
 * Result of decoding a single 32-PID availability block.
 */
data class DiscoveryRangeResult(
    val basePid: Int,
    val bitmap: ByteArray,
    val bitmapHex: String,
    val supportedPids: List<Int>,
    val allTestedPids: List<Int>,
    val hasNextRange: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DiscoveryRangeResult
        if (basePid != other.basePid) return false
        if (!bitmap.contentEquals(other.bitmap)) return false
        if (supportedPids != other.supportedPids) return false
        if (hasNextRange != other.hasNextRange) return false
        return true
    }

    override fun hashCode(): Int {
        var result = basePid
        result = 31 * result + bitmap.contentHashCode()
        result = 31 * result + supportedPids.hashCode()
        result = 31 * result + hasNextRange.hashCode()
        return result
    }
}
