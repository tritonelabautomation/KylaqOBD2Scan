package com.example

import com.example.protocol.CodingLabCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coding Lab helpers: DID/header normalisation, 0x22 decode, NRC mapping, ASCII. */
class CodingLabCodecTest {

    @Test
    fun `normalizeDid accepts and pads hex variants`() {
        assertEquals("F190", CodingLabCodec.normalizeDid("f190"))
        assertEquals("F190", CodingLabCodec.normalizeDid("0xF190"))
        assertEquals("0010", CodingLabCodec.normalizeDid("10"))
        assertNull(CodingLabCodec.normalizeDid("G190"))
        assertNull(CodingLabCodec.normalizeDid(""))
        assertNull(CodingLabCodec.normalizeDid("F1900")) // 5 hex chars is not a 16-bit DID
    }

    @Test
    fun `normalizeHeader accepts 3 or 8 hex`() {
        assertEquals("7E0", CodingLabCodec.normalizeHeader("7e0"))
        assertEquals("007E0001", CodingLabCodec.normalizeHeader("007e0001"))
        assertNull(CodingLabCodec.normalizeHeader("7G0"))
        assertNull(CodingLabCodec.normalizeHeader("7E"))
    }

    @Test
    fun `readRequest builds UDS 0x22 payload`() {
        assertEquals("22F190", CodingLabCodec.readRequest("F190"))
    }

    @Test
    fun `decodePositive strips 62 echo across multiline responses`() {
        // Each frame that carries the 62+DID echo is stripped and concatenated
        // (multi-ECU responses); continuation frames without the header are skipped.
        val payload = CodingLabCodec.decodePositive(listOf("62 F190 54 4D42 4A53 4843 4831 3134"), "F190")
        assertEquals("544D424A53484348313134", payload)
        val twoEcu = CodingLabCodec.decodePositive(listOf("62F190 AA", "62F190 BB"), "F190")
        assertEquals("AABB", twoEcu)
    }

    @Test
    fun `negativeNrc detects 7F responses and maps names`() {
        assertEquals("31", CodingLabCodec.negativeNrc("7F 22 31"))
        assertEquals("33", CodingLabCodec.negativeNrc("7F2233"))
        assertNull(CodingLabCodec.negativeNrc("62 F190 54 4D"))
        assertTrue(CodingLabCodec.nrcName("33").contains("securityAccessDenied"))
        assertTrue(CodingLabCodec.nrcName("31").contains("requestOutOfRange"))
    }

    @Test
    fun `hexToAscii decodes VIN and masks non-printables`() {
        val vin = "4D4F44454C56494E313233" // "MODELVIN123"
        assertEquals("MODELVIN123", CodingLabCodec.hexToAscii(vin))
        assertNotNull(CodingLabCodec.hexToAscii("00FF")) // non-printable -> dots, no crash
    }
}
