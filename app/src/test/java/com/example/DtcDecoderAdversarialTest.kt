package com.example

import com.example.protocol.DtcDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 adversarial test suite for DtcDecoder.
 * Tests Mode 03 (stored) and Mode 07 (pending) DTC extraction.
 * Threat model: negative responses, garbage, CAN ID false positives, mode mismatch.
 */
class DtcDecoderAdversarialTest {

    // ── Positive responses ──────────────────────────────────────────────────

    @Test
    fun testMode03_singleDtc_correctlyDecoded() {
        val dtcs = DtcDecoder.extractDtcs("430104", mode = 0x03)
        assertEquals(1, dtcs.size)
        assertEquals("P0104", dtcs[0])
    }

    @Test
    fun testMode03_multipleDtcs_correctlyDecoded() {
        val dtcs = DtcDecoder.extractDtcs("4301048030901231B345", mode = 0x03)
        assertEquals(4, dtcs.size)
        assertTrue(dtcs.contains("P0104"))
        // FIX: 0x80 0x30 -> bits 7..6 = 0b10 -> chassis ("B"), not "C". The old expectation
        // contradicted SAE J2012 lettering (00=P, 01=C, 10=B, 11=U).
        assertTrue("0x8030 must decode to the chassis code B0030", dtcs.contains("B0030"))
    }

    @Test
    fun testMode07_singleDtc_correctlyDecoded() {
        val dtcs = DtcDecoder.extractDtcs("470201", mode = 0x07)
        assertEquals(1, dtcs.size)
        assertEquals("P0201", dtcs[0])
    }

    // ── Negative responses (7F) ─────────────────────────────────────────────

    @Test
    fun testMode03_negativeResponse_returnsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("7F0311", mode = 0x03)
        assertTrue("Negative response must return empty list", dtcs.isEmpty())
    }

    @Test
    fun testMode07_negativeResponse_returnsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("7F0722", mode = 0x07)
        assertTrue("Negative response must return empty list", dtcs.isEmpty())
    }

    @Test
    fun testNegativeResponse_longForm_returnsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("7E87F0311", mode = 0x03)
        assertTrue("Negative response with CAN ID prefix must return empty list", dtcs.isEmpty())
    }

    // ── CAN ID "7E8" / "7E9" false positive ──────────────────────────────

    @Test
    fun testCanId7E8_notFalsePositive_negativeResponse() {
        val dtcs = DtcDecoder.extractDtcs("7E8430104", mode = 0x03)
        assertEquals("CAN ID 7E8 prefix must not block DTC decoding", 1, dtcs.size)
        assertEquals("P0104", dtcs[0])
    }

    @Test
    fun testCanId7E9_notFalsePositive_negativeResponse() {
        // FIX: the fixture used a 0x43 ack while querying mode 0x07, which is a mode
        // mismatch that MUST yield nothing (see testMode07Query_withMode03Ack_returnsEmpty).
        // The intent of this test is "a CAN id prefix must not block decoding", so the
        // payload now carries the correct 0x47 ack for the requested mode.
        val dtcs = DtcDecoder.extractDtcs("7E9470201", mode = 0x07)
        assertEquals("CAN ID 7E9 prefix must not block DTC decoding", 1, dtcs.size)
        assertEquals("P0201", dtcs[0])
    }

    @Test
    fun testCanId7E9_withModeMismatchedAck_returnsEmpty() {
        // A mode-03 ack (43) must not be accepted for a mode-07 query, prefix or not.
        assertTrue(DtcDecoder.extractDtcs("7E9430201", mode = 0x07).isEmpty())
    }

    // ── Mode mismatch — wrong ack byte ─────────────────────────────────────

    @Test
    fun testMode03Query_withMode07Ack_returnsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("470104", mode = 0x03)
        assertTrue("Mode 07 ack for mode 03 query must return empty list", dtcs.isEmpty())
    }

    @Test
    fun testMode07Query_withMode03Ack_returnsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("430201", mode = 0x07)
        assertTrue("Mode 03 ack for mode 07 query must return empty list", dtcs.isEmpty())
    }

    // ── Zero-padding suppression ────────────────────────────────────────────

    @Test
    fun testZeroPadding_00_00_suppressed() {
        val dtcs = DtcDecoder.extractDtcs("4301040000", mode = 0x03)
        assertEquals("00 00 padding must be suppressed", 1, dtcs.size)
        assertEquals("P0104", dtcs[0])
    }

    @Test
    fun testAllZeroPayload_returnsEmpty() {
        val dtcs = DtcDecoder.extractDtcs("430000", mode = 0x03)
        assertTrue("All-zero DTC payload must return empty list", dtcs.isEmpty())
    }

    // ── Garbage / malformed payloads ────────────────────────────────────────

    @Test
    fun testTooShortPayload_returnsEmpty() {
        assertTrue(DtcDecoder.extractDtcs("43", mode = 0x03).isEmpty())
    }

    @Test
    fun testEmptyPayload_returnsEmpty() {
        assertTrue(DtcDecoder.extractDtcs("", mode = 0x03).isEmpty())
    }

    @Test
    fun testGarbageHex_returnsEmpty() {
        assertTrue(DtcDecoder.extractDtcs("DEADBEEF", mode = 0x03).isEmpty())
    }

    @Test
    fun testSpacesInPayload_stripped() {
        val dtcs = DtcDecoder.extractDtcs("43 01 04", mode = 0x03)
        assertEquals("Spaces must be stripped before processing", 1, dtcs.size)
        assertEquals("P0104", dtcs[0])
    }

    @Test
    fun testLowercaseHex_normalized() {
        val dtcs = DtcDecoder.extractDtcs("43p0104", mode = 0x03)
        assertEquals("Lowercase hex must be normalized", 1, dtcs.size)
        assertEquals("P0104", dtcs[0])
    }

    // ── Distinct DTCs only ─────────────────────────────────────────────────

    @Test
    fun testDuplicateDtcs_deduplicated() {
        val dtcs = DtcDecoder.extractDtcs("4301040104", mode = 0x03)
        assertEquals("Duplicate DTCs must be deduplicated", 1, dtcs.size)
        assertEquals("P0104", dtcs[0])
    }

    // ── 0x7F in DTC data bytes — NOT a false positive ───────────────────

    @Test
    fun test0x7F_inDtcDataBytes_notFalsePositive() {
        // DTC high-byte = 0x7F -> bits 7..6 = 0b01 -> chassis ("C"). The point of the test
        // is that 0x7F inside the DTC data must NOT be misread as a negative response.
        // FIX: the old expectation asked for a "U" code, which 0x7F can never produce.
        val dtcs = DtcDecoder.extractDtcs("437FF0", mode = 0x03)
        assertEquals("0x7F in DTC data bytes must decode correctly", 1, dtcs.size)
        assertTrue("Must produce a valid C-code DTC", dtcs[0].startsWith("C"))
    }

    @Test
    fun testUcodeDecoded_fromHighByteC0() {
        // bits 7..6 = 0b11 -> network ("U"), e.g. U0100 lost communication with ECM.
        // 43 | C0 10 | C0 11  -> two network DTCs (U0010, U0011)
        val dtcs = DtcDecoder.extractDtcs("43C010C011", mode = 0x03)
        assertEquals(2, dtcs.size)
        assertTrue("Must produce U-codes", dtcs.all { it.startsWith("U") })
    }
    // ── Real SAE J1979 wire format: `<ack> <count> <DTC bytes...>` ───────────

    @Test
    fun testMode03_countByteIsNotDecodedAsADtc() {
        // ">03" answered by the engine ECU:  7E8 06 43 02 01 04 01 08
        // = ack 43, TWO DTCs (P0104, P0108). Decoding the body as a flat pair list starts
        // at the count byte and invents codes (P0201, P0401) — which is what this app did.
        val dtcs = DtcDecoder.extractDtcs("430201040108", mode = 0x03)
        assertEquals(listOf("P0104", "P0108"), dtcs)
    }

    @Test
    fun testMode03_rawElmLineWithCanIdAndPciByte() {
        // Exactly what the transport hands over before ISO-TP reassembly:
        // 11-bit CAN id (7E8) + single-frame PCI (06) + ack (43) + count + DTCs.
        val dtcs = DtcDecoder.extractDtcs("7E806430201040108", mode = 0x03)
        assertEquals(listOf("P0104", "P0108"), dtcs)
    }

    @Test
    fun testMode03_rawElmLineWithSpaces() {
        val dtcs = DtcDecoder.extractDtcs("7E8 06 43 02 01 04 01 08", mode = 0x03)
        assertEquals(listOf("P0104", "P0108"), dtcs)
    }

    @Test
    fun testMode03_singleDtcWithCountByte() {
        // 43 01 01 04 -> count = 1 -> P0104
        assertEquals(listOf("P0104"), DtcDecoder.extractDtcs("43010104", mode = 0x03))
    }

    @Test
    fun testMode03_countByteWithZeroFramePadding() {
        // Adapters that pad the frame out to 8 bytes: 43 02 01 04 01 08 00 00
        assertEquals(listOf("P0104", "P0108"), DtcDecoder.extractDtcs("4302010401080000", mode = 0x03))
    }

    @Test
    fun testMode03_countLessBodyStillDecodes() {
        // Fixtures and some callers omit the count byte; an even-length body that the count
        // cannot account for (43 01 04 00 00) must still decode as a flat pair list.
        assertEquals(listOf("P0104"), DtcDecoder.extractDtcs("4301040000", mode = 0x03))
    }

    @Test
    fun testMode07_countByteWithThreePendingDtcs() {
        // 47 03 | 01 04 | 01 08 | 01 0C
        assertEquals(listOf("P0104", "P0108", "P010C"),
            DtcDecoder.extractDtcs("470301040108010C", mode = 0x07))
    }

    @Test
    fun testMode0A_permanentDtcsRequireAck4A() {
        // 4A 01 C0 10 -> U0010 (lost communication with ECM)
        assertEquals(listOf("U0010"), DtcDecoder.extractDtcs("4A01C010", mode = 0x0A))
        assertTrue("a mode-03 ack must not be accepted for a mode-0A query",
            DtcDecoder.extractDtcs("4301C010", mode = 0x0A).isEmpty())
    }
}
