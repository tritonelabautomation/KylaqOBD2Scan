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
        assertTrue(dtcs.contains("C0030"))
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
        val dtcs = DtcDecoder.extractDtcs("7E9430201", mode = 0x07)
        assertEquals("CAN ID 7E9 prefix must not block DTC decoding", 1, dtcs.size)
        assertEquals("P0201", dtcs[0])
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
        // DTC high-byte = 0x7F → category=3 (U); must NOT be misread as negative response.
        val dtcs = DtcDecoder.extractDtcs("437FF0", mode = 0x03)
        assertEquals("0x7F in DTC data bytes must decode correctly", 1, dtcs.size)
        assertTrue("Must produce a valid U-code DTC", dtcs[0].startsWith("U"))
    }
}
