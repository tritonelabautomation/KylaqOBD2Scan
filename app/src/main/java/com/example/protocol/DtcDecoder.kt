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
     * Decodes a Mode 03 / 07 / 0A payload into a list of DTC strings.
     *
     * FIX (DTCs never decoded from a real adapter): callers hand this function the raw
     * ELM327 text, which is CAN-framed and may contain spaces or other noise, e.g.
     * `"7E8 03 43 01 04"` or `"7E803430104"`. The previous implementation only accepted
     * a payload that *started* with the ack byte, so every real response was rejected and
     * scan sessions always reported `dtcCount = 0`.
     *
     * The decoder now:
     *  1. strips every non-hex character (spaces, `>`, prompt noise, stray letters),
     *  2. tolerates an optional leading CAN id (11-bit `7E8` / 29-bit `18DAF110`) and the
     *     ISO-TP single-frame PCI byte (`7E8 06 43 02 01 04 01 08`),
     *  3. still requires the positive ack for the *requested* mode (43 / 47 / 4A), so a
     *     mode-01 ack or a mode-07 answer to a mode-03 query yields nothing,
     *  4. still rejects negative responses (`7F <service> <NRC>`), with or without a
     *     CAN id prefix,
     *  5. honours the SAE J1979 DTC count byte (`43 02 01 04 01 08` = P0104 + P0108) when it
     *     is self-consistent, and otherwise treats the body as a flat list of DTC pairs,
     *  6. still suppresses `00 00` padding and de-duplicates.
     *
     * @param payloadHex hex payload, with or without CAN header / PCI / service byte
     * @param mode 0x03 active, 0x07 pending, 0x0A permanent
     * @return list of decoded DTC codes; empty list if the response is invalid or negative.
     */
    fun extractDtcs(payloadHex: String, mode: Int = 0x03): List<String> {
        // 1. Normalise: keep hex digits only.
        val cleanHex = payloadHex.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        if (cleanHex.length < 2) return emptyList()

        val expectedAck = (mode + 0x40) and 0xFF
        val expectedAckHex = "%02X".format(expectedAck)

        // 2. Locate the positive-response ack, tolerating a CAN id and/or the ISO-TP PCI
        //    byte in front of it. All offsets are in hex characters:
        //      0  payload only (what IsoTpParser hands us: "430201040108")
        //      2  PCI byte only, headers off          ("06430201040108")
        //      3  11-bit CAN id                       ("7E8430201040108")
        //      5  11-bit CAN id + PCI                 ("7E806430201040108")
        //      8  29-bit CAN id                       ("18DAF11043...")
        //     10  29-bit CAN id + PCI                 ("18DAF1100643...")
        var dataStart = -1
        for (prefixLen in listOf(0, 2, 3, 5, 8, 10)) {
            if (prefixLen + 2 > cleanHex.length) continue
            val candidate = cleanHex.substring(prefixLen)
            // Negative response (7F <service> <NRC>) — never a DTC list.
            if (candidate.startsWith("7F")) return emptyList()
            if (candidate.startsWith(expectedAckHex)) {
                dataStart = prefixLen + 2
                break
            }
        }
        if (dataStart < 0 || dataStart > cleanHex.length) return emptyList()

        val dtcs = mutableListOf<String>()
        val bodyBytes = cleanHex.substring(dataStart).chunked(2)
            .filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16) }
        val dataHex = stripDtcCountByte(bodyBytes)

        // Whatever is left is a plain list of 2-byte DTCs.
        // Zero-padded pairs (00 00) are rejected by decodeDtcHex.
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

    /**
     * Removes the SAE J1979 DTC count byte when one is present.
     *
     * A real adapter answers `>03` with `43 <count> <DTC hi> <DTC lo> ...`, e.g.
     * `7E8 06 43 02 01 04 01 08` = two DTCs (P0104, P0108). Decoding that as a flat list of
     * pairs starts at the count byte and invents codes (P0201, P0401). Plenty of callers and
     * fixtures, however, pass the DTC bytes *without* the count, so the count is only honoured
     * when it is self-consistent:
     *
     *  * `count` exactly accounts for the remaining bytes (`43 02 01 04 01 08`), or
     *  * `count` accounts for a prefix and everything after it is `00` frame padding
     *    (`43 02 01 04 01 08 00 00`).
     *
     * Anything else — notably the even-length, count-less fixtures such as `43 01 04 00 00`
     * — is decoded as a flat pair list, preserving the previous behaviour.
     */
    private fun stripDtcCountByte(body: List<Int>): String {
        val declared = body.firstOrNull()
        if (declared == null || declared <= 0 || body.size < 3) return body.toDtcHex()

        // A count byte plus `count` two-byte DTCs is always an odd number of bytes.
        if ((body.size - 1) % 2 != 0) return body.toDtcHex()

        val payloadLength = 1 + (2 * declared)
        val pairsAvailable = (body.size - 1) / 2
        val exact = declared == pairsAvailable
        val zeroPadded = declared < pairsAvailable &&
            body.size >= payloadLength &&
            body.drop(payloadLength).all { it == 0x00 }

        return if (exact || zeroPadded) {
            body.subList(1, minOf(payloadLength, body.size)).toDtcHex()
        } else {
            body.toDtcHex()
        }
    }

    private fun List<Int>.toDtcHex(): String = joinToString("") { "%02X".format(it and 0xFF) }
}
