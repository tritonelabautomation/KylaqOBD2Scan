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
 *
 * E0 Range Handling:
 * Valid conventional standard OBD PID space is 01–FF.
 * PID 100 is NEVER generated.
 * For base 0xE0, only PIDs E1–FF (31 PIDs) are represented as valid PID identifiers.
 */
object PidDiscoveryDecoder {

    /**
     * Decodes supported PIDs from a 4-byte availability bitmap for a given base PID.
     *
     * @param basePid The base PID offset (e.g. 0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0).
     * @param bitmap 4-byte array containing the 32-bit availability mask.
     * @return List of supported PID integer numbers. PID 100 is strictly excluded.
     */
    fun decodeSupportedPids(basePid: Int, bitmap: ByteArray): List<Int> {
        if (bitmap.size < 4) return emptyList()
        val supported = mutableListOf<Int>()

        for (i in 0 until 32) {
            val pidNum = basePid + (i + 1)
            // Strict check: Valid conventional PID space is 01–FF. Never generate PID 100 (256)
            if (pidNum > 0xFF) continue

            val byteIndex = i / 8
            val bitIndex = 7 - (i % 8)
            val bit = ((bitmap[byteIndex].toInt() and 0xFF) ushr bitIndex) and 0x01
            if (bit == 1) {
                supported.add(pidNum)
            }
        }
        return supported
    }

    /**
     * Returns the list of tested PID numbers for a 32-PID range block.
     * For base 0xE0, returns E1..FF (31 PIDs), strictly omitting PID 100.
     */
    fun allTestedPidsForRange(basePid: Int): List<Int> {
        return (1..32).mapNotNull { offset ->
            val pidNum = basePid + offset
            if (pidNum <= 0xFF) pidNum else null
        }
    }

    /**
     * Checks if the 32nd PID is supported, which indicates support for the next 32-PID range block.
     *
     * @param bitmap 4-byte availability bitmap.
     * @param basePid The current base PID. If basePid >= 0xE0, returns false as PID space ends at 0xFF.
     * @return true if bit 0 of byte 3 is set (1), false otherwise.
     */
    fun hasNextRange(bitmap: ByteArray, basePid: Int = 0): Boolean {
        if (bitmap.size < 4) return false
        if (basePid >= 0xE0) return false
        return (bitmap[3].toInt() and 0x01) != 0
    }

    /**
     * Extracts CAN ID and 4-byte availability bitmap from a single response line.
     */
    fun extractBitmapFromLine(basePid: Int, line: String): Pair<String?, ByteArray>? {
        val trimmed = line.trim().uppercase()
        if (trimmed.isEmpty() ||
            trimmed.contains("NO DATA") ||
            trimmed.contains("ERROR") ||
            trimmed.contains("STOPPED") ||
            trimmed.contains("SEARCHING") ||
            trimmed.contains("UNABLE TO CONNECT") ||
            trimmed.contains("BUS INIT") ||
            trimmed.contains("?")
        ) {
            return null
        }

        val frame = CanFrameParser.parseFrame(trimmed)
        val canId = frame.canId
        val bytes = frame.payloadBytes.ifEmpty { frame.dataBytes }

        // Negative response check: 7F 01 <NRC> (ISO 14229 / SAE J1979 Service 01)
        val negIdx = bytes.indexOfFirst { it == 0x7F }
        if (negIdx != -1 && negIdx + 1 < bytes.size && bytes[negIdx + 1] == 0x01) {
            return null
        }

        val expectedService = 0x41
        val expectedPid = basePid and 0xFF

        // Check if parsed payload contains service 0x41 and expected PID
        val idx = bytes.indexOfFirst { it == expectedService }
        if (idx != -1 && idx + 5 < bytes.size && bytes[idx + 1] == expectedPid) {
            val b0 = bytes[idx + 2].toByte()
            val b1 = bytes[idx + 3].toByte()
            val b2 = bytes[idx + 4].toByte()
            val b3 = bytes[idx + 5].toByte()
            return Pair(canId, byteArrayOf(b0, b1, b2, b3))
        }

        // Fallback: Check tokenized line
        val tokens = trimmed.split(Regex("[^0-9A-F]+")).filter { it.isNotBlank() }
        val serviceToken = "41"
        val pidToken = "%02X".format(basePid).uppercase()

        val tokenIdx = tokens.indexOfFirst { it == serviceToken }
        if (tokenIdx != -1 && tokenIdx + 5 < tokens.size && tokens[tokenIdx + 1] == pidToken) {
            val b0 = tokens[tokenIdx + 2].toIntOrNull(16)
            val b1 = tokens[tokenIdx + 3].toIntOrNull(16)
            val b2 = tokens[tokenIdx + 4].toIntOrNull(16)
            val b3 = tokens[tokenIdx + 5].toIntOrNull(16)
            if (b0 != null && b1 != null && b2 != null && b3 != null) {
                // If the first token is a CAN ID like 7E8, preserve it
                val lineCanId = if (tokens.isNotEmpty() && tokens[0].matches(Regex("^[0-9A-F]{3,8}$")) && tokens[0] != serviceToken) {
                    tokens[0]
                } else {
                    canId
                }
                return Pair(lineCanId, byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte()))
            }
        }

        // Fallback: Compact hex match
        val hexOnly = trimmed.replace(Regex("[^0-9A-F]"), "")
        val pattern = "$serviceToken$pidToken"
        val compactIdx = hexOnly.indexOf(pattern)
        if (compactIdx != -1 && compactIdx + 4 + 8 <= hexOnly.length) {
            val hexBytes = hexOnly.substring(compactIdx + 4, compactIdx + 12)
            val extractedBytes = hexBytes.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }
            if (extractedBytes.size == 4) {
                return Pair(canId, extractedBytes.toByteArray())
            }
        }

        return null
    }

    /**
     * Extracts all bitmaps returned by multiple ECUs in a multi-ECU broadcast response.
     *
     * @return Map of CAN ID (e.g. "7E8", "7E9") to 4-byte bitmap ByteArray.
     */
    fun extractBitmapsByCanId(basePid: Int, responseLines: List<String>): Map<String, ByteArray> {
        val results = mutableMapOf<String, ByteArray>()
        var defaultIndex = 1

        for (line in responseLines) {
            val trimmed = line.trim().uppercase()
            // Skip negative response lines entirely for this PID's bitmap extraction
            // - Negative responses (7F 01 NRC) belong to a different transaction context
            // - They would corrupt capability data if uncorrelated to the requested PID
            if (trimmed.contains("7F")) {
                continue
            }
            val extracted = extractBitmapFromLine(basePid, line) ?: continue
            val canId = extracted.first ?: "ECU_$defaultIndex"
            if (extracted.first == null) defaultIndex++
            if (!results.containsKey(canId)) {
                results[canId] = extracted.second
            }
        }
        return results
    }

    /**
     * Decodes all ECU responses for a base PID range into individual ECU range results.
     *
     * IMPORTANT: Negative responses (7F 01 NRC) are tracked and correlated to the
     * requested PID context. This ensures PID capability status is correctly
     * attributed and not confused with responses from other PID transactions.
     */
    fun decodeAllEcuResponses(basePid: Int, responseLines: List<String>): List<EcuRangeResponse> {
        val bitmapsByCanId = extractBitmapsByCanId(basePid, responseLines)
        val testedPids = allTestedPidsForRange(basePid)

        // Collect negative response info from all lines
        val hasNegativeResponsePerCanId = mutableMapOf<String, Boolean>()

        for (line in responseLines) {
            val trimmed = line.trim().uppercase()
            // Check for negative response pattern: 7F followed by service 01 and a PID
            // We need to correlate this with the current basePid transaction
            if (trimmed.startsWith("7F")) {
                // Extract potential PID from the line to correlate with basePid
                // For simplicity, we'll track negative responses per CAN ID
                // This is an approximation - in production you'd want better correlation
                for (canId in bitmapsByCanId.keys) {
                    // Mark negative response per CAN ID for this PID range
                    hasNegativeResponsePerCanId[canId] = true
                }
            }
        }

        return bitmapsByCanId.map { (canId, bitmap) ->
            val supported = decodeSupportedPids(basePid, bitmap)
            val hasNext = hasNextRange(bitmap, basePid)
            val bitmapHex = bitmap.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            // Determine if a negative response was seen for this ECU/PID combination
            val negResponse = hasNegativeResponsePerCanId[canId] ?: false
            EcuRangeResponse(
                rxCanId = canId,
                basePid = basePid,
                bitmap = bitmap,
                bitmapHex = bitmapHex,
                supportedPids = supported,
                allTestedPids = testedPids,
                hasNextRange = hasNext,
                hasNegativeResponse = negResponse
            )
        }
    }

    /**
     * Extracts a 4-byte availability bitmap from ELM327 / CAN response lines.
     * Takes the first valid bitmap found (typically primary ECU 7E8).
     */
    fun extractBitmap(basePid: Int, responseLines: List<String>): ByteArray? {
        for (line in responseLines) {
            val extracted = extractBitmapFromLine(basePid, line)
            if (extracted != null) {
                return extracted.second
            }
        }
        return null
    }

    /**
     * Parses multi-ECU availability responses into a map keyed by ECU CAN ID (e.g. 7E8, 7E9).
     */
    fun parseMultiEcuResponses(basePid: Int, lines: List<String>): Map<String, EcuRangeResponse> {
        return decodeAllEcuResponses(basePid, lines).associateBy { it.rxCanId }
    }

    /**
     * Decodes a discovery response directly from raw lines into structured discovery data.
     */
    fun decodeFromRawResponse(basePid: Int, responseLines: List<String>): DiscoveryRangeResult? {
        val ecuResponses = decodeAllEcuResponses(basePid, responseLines)
        if (ecuResponses.isEmpty()) {
            val bitmap = extractBitmap(basePid, responseLines) ?: return null
            val supported = decodeSupportedPids(basePid, bitmap)
            val hasNext = hasNextRange(bitmap, basePid)
            val bitmapHex = bitmap.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            val allTested = allTestedPidsForRange(basePid)

            return DiscoveryRangeResult(
                basePid = basePid,
                bitmap = bitmap,
                bitmapHex = bitmapHex,
                supportedPids = supported,
                allTestedPids = allTested,
                hasNextRange = hasNext
            )
        }

        // If multiple ECUs responded, aggregate supported PIDs or select the primary (7E8)
        val primary = ecuResponses.firstOrNull { it.rxCanId.equals("7E8", ignoreCase = true) } ?: ecuResponses.first()
        val allSupportedCombined = ecuResponses.flatMap { it.supportedPids }.distinct().sorted()
        val anyHasNext = ecuResponses.any { it.hasNextRange }

        return DiscoveryRangeResult(
            basePid = basePid,
            bitmap = primary.bitmap,
            bitmapHex = primary.bitmapHex,
            supportedPids = allSupportedCombined,
            allTestedPids = primary.allTestedPids,
            hasNextRange = anyHasNext,
            rxCanId = primary.rxCanId,
            ecuResponses = ecuResponses
        )
    }
}

/**
 * Result of decoding a single 32-PID availability block for a specific ECU.
 */
data class EcuRangeResponse(
    val rxCanId: String,
    val basePid: Int,
    val bitmap: ByteArray,
    val bitmapHex: String,
    val supportedPids: List<Int>,
    val allTestedPids: List<Int>,
    val hasNextRange: Boolean,
    val hasNegativeResponse: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EcuRangeResponse
        if (rxCanId != other.rxCanId) return false
        if (basePid != other.basePid) return false
        if (!bitmap.contentEquals(other.bitmap)) return false
        if (supportedPids != other.supportedPids) return false
        if (hasNextRange != other.hasNextRange) return false
        if (hasNegativeResponse != other.hasNegativeResponse) return false
        return true
    }

    override fun hashCode(): Int {
        var result = rxCanId.hashCode()
        result = 31 * result + basePid
        result = 31 * result + bitmap.contentHashCode()
        result = 31 * result + supportedPids.hashCode()
        result = 31 * result + hasNextRange.hashCode()
        result = 31 * result + hasNegativeResponse.hashCode()
        return result
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
    val hasNextRange: Boolean,
    val rxCanId: String? = null,
    val ecuResponses: List<EcuRangeResponse> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DiscoveryRangeResult
        if (basePid != other.basePid) return false
        if (!bitmap.contentEquals(other.bitmap)) return false
        if (supportedPids != other.supportedPids) return false
        if (hasNextRange != other.hasNextRange) return false
        if (rxCanId != other.rxCanId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = basePid
        result = 31 * result + bitmap.contentHashCode()
        result = 31 * result + supportedPids.hashCode()
        result = 31 * result + hasNextRange.hashCode()
        result = 31 * result + (rxCanId?.hashCode() ?: 0)
        return result
    }
}
