package com.example

import com.example.model.DecoderType
import com.example.model.PidDefinition
import com.example.protocol.PidDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 adversarial test suite for PidDecoder.
 * Tests that PidDecoder correctly handles malformed, misrouted, and
 * adversarial OBD responses — never producing silently incorrect telemetry.
 */
class PidDecoderAdversarialTest {

    private val rpmPid = PidDefinition(
        id = "010C", service = "01", pid = "0C",
        name = "Engine RPM", shortName = "RPM", unit = "RPM",
        decoderType = DecoderType.RPM_FORMULA, isResearch = false
    )

    private val researchPid = PidDefinition(
        id = "01FF", service = "01", pid = "FF",
        name = "Research PID", shortName = "RFFD", unit = "",
        decoderType = DecoderType.RESEARCH_RAW, isResearch = true
    )

    private val speedPid = PidDefinition(
        id = "010D", service = "01", pid = "0D",
        name = "Vehicle Speed", shortName = "SPD", unit = "km/h",
        decoderType = DecoderType.RAW_A_KMH, isResearch = false
    )

    private val throttlePid = PidDefinition(
        id = "0111", service = "01", pid = "11",
        name = "Throttle Position", shortName = "Throttle", unit = "%",
        decoderType = DecoderType.PERCENT_255, isResearch = false
    )

    private val fuelTypePid = PidDefinition(
        id = "0151", service = "01", pid = "51",
        name = "Fuel Type", shortName = "Fuel", unit = "",
        decoderType = DecoderType.FUEL_TYPE_ENUM, isResearch = false
    )

    // ── Category 1: Wrong PID byte ────────────────────────────────────────

    @Test
    fun testWrongPidByte_returnsInvalidResponse() {
        val payload = listOf(0x41, 0x0D, 0x12, 0x34)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Wrong PID byte must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
        assertTrue(result.rawPayloadHex.isNotEmpty())
    }

    @Test
    fun testWrongServiceAck_returnsInvalidResponse() {
        val payload = listOf(0x42, 0x0C, 0x12, 0x34)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Wrong service ack must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
    }

    // ── Category 2: Malformed payload ─────────────────────────────────────

    @Test
    fun testSingleBytePayload_returnsInvalidResponse() {
        val payload = listOf(0x41)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Single byte payload must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
    }

    @Test
    fun testServiceAckPresent_butPidMismatch_returnsInvalidResponse() {
        val payload = listOf(0x41, 0x00)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Service ack present but PID mismatch must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
    }

    @Test
    fun testGarbagePayload_returnsInvalidResponse() {
        val payload = listOf(0xFF, 0xFF, 0xFF, 0xFF)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Garbage payload must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
    }

    @Test
    fun testNumericLookingGarbage_returnsInvalidResponse() {
        val payload = listOf(0x10, 0x20, 0x30, 0x40)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Numeric-looking garbage must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
    }

    // ── Category 3: Negative response (0x7F) ───────────────────────────────

    @Test
    fun testNegativeResponse_returnsInvalidResponse() {
        val payload = listOf(0x7F, 0x01, 0x11)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Negative response (0x7F) must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
    }

    @Test
    fun testNegativeResponse_wrongService_returnsInvalidResponse() {
        val payload = listOf(0x7F, 0x09, 0x22)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("0x7F with wrong service must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
    }

    // ── Category 4: Empty payload ───────────────────────────────────────────

    @Test
    fun testEmptyPayload_returnsNoData() {
        val payload = emptyList<Int>()
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("Empty payload must produce NO DATA", "NO DATA", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
        assertTrue("dataBytes must be empty for no-data", result.dataBytes.isEmpty())
    }

    // ── Category 5: Research PID loose mode ────────────────────────────────

    @Test
    fun testResearchPid_acceptsServiceAckOnly() {
        val payload = listOf(0x41, 0xFF, 0xAB, 0xCD)
        val result = PidDecoder.decode(researchPid, payload)
        assertNotEquals("Research PID with matching ack must NOT produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertTrue(result.dataBytes.isNotEmpty())
    }

    @Test
    fun testResearchPid_acceptsRawPayloadWithoutHeader() {
        val payload = listOf(0x12, 0x34, 0x56)
        val result = PidDecoder.decode(researchPid, payload)
        assertNotEquals("Research PID with raw payload must NOT produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
        assertFalse(result.isKnown)
    }

    @Test
    fun testResearchPid_rejectsNegativeResponseHeader() {
        val payload = listOf(0x7F, 0x01, 0x11)
        val result = PidDecoder.decode(researchPid, payload)
        assertEquals("Research PID with 0x7F header must produce INVALID_RESPONSE",
            "INVALID_RESPONSE", result.displayValue)
    }

    // ── Category 6: 0x7F in data bytes — NOT a false positive ─────────────

    @Test
    fun test0x7F_inDataBytes_notFalsePositive() {
        // Valid positive response 41 0C where byte index 2 is 0x7F (RPM data A=0x7F)
        // RPM = ((127*256)+128)/4 = 8160 rpm — must decode correctly
        val payload = listOf(0x41, 0x0C, 0x7F, 0x80)
        val result = PidDecoder.decode(rpmPid, payload)
        assertNotEquals("0x7F in data bytes must NOT be rejected as negative response",
            "INVALID_RESPONSE", result.displayValue)
        assertNotNull("Numeric value must be computed from data bytes", result.numericValue)
        assertTrue(result.isKnown)
    }

    @Test
    fun test0x7F_inSpeedData_notFalsePositive() {
        val payload = listOf(0x41, 0x0D, 0x7F)
        val result = PidDecoder.decode(speedPid, payload)
        assertNotEquals("0x7F in speed data byte must NOT be rejected",
            "INVALID_RESPONSE", result.displayValue)
        assertEquals(127.0, result.numericValue!!, 0.01)
    }

    @Test
    fun test0x7F_asFuelTypeEnumValue_notFalsePositive() {
        val payload = listOf(0x41, 0x51, 0x7F)
        val result = PidDecoder.decode(fuelTypePid, payload)
        assertNotEquals("0x7F as fuel type enum value must NOT be rejected",
            "INVALID_RESPONSE", result.displayValue)
        assertTrue(result.displayValue != "INVALID_RESPONSE" && result.displayValue != "NO DATA")
    }

    // ── Category 7: Valid positive responses — no regression ───────────────

    @Test
    fun testValidRpmResponse_correctlyDecoded() {
        // A=0x0D, B=0x80 → ((13*256)+128)/4 = 864 rpm
        val payload = listOf(0x41, 0x0C, 0x0D, 0x80)
        val result = PidDecoder.decode(rpmPid, payload)
        assertNotEquals("Valid RPM response must decode",
            "INVALID_RESPONSE", result.displayValue)
        assertNotEquals("Valid RPM response must decode",
            "NO DATA", result.displayValue)
        assertEquals(864.0, result.numericValue!!, 0.01)
        assertTrue(result.isKnown)
        assertEquals("RPM", result.unit)
    }

    @Test
    fun testValidSpeedResponse_correctlyDecoded() {
        val payload = listOf(0x41, 0x0D, 0x64)  // 100 km/h
        val result = PidDecoder.decode(speedPid, payload)
        assertEquals(100.0, result.numericValue!!, 0.01)
        assertTrue(result.isKnown)
        assertEquals("km/h", result.unit)
    }

    @Test
    fun testValidThrottleResponse_correctlyDecoded() {
        val payload = listOf(0x41, 0x11, 0xFF)  // 100%
        val result = PidDecoder.decode(throttlePid, payload)
        assertEquals("Throttle 0xFF = 100%", 100.0, result.numericValue!!, 0.01)
        assertTrue(result.isKnown)
    }

    // ── Category 8: Invalid responses have no telemetry effect ─────────────

    @Test
    fun testInvalidResponse_noNumericValue_noTelemetryContamination() {
        val payload = listOf(0x7F, 0x01, 0x11)
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("INVALID_RESPONSE", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
        assertTrue("Raw hex must be captured for forensic log", result.rawPayloadHex.isNotEmpty())
    }

    @Test
    fun testNoDataResponse_noNumericValue_noTelemetryContamination() {
        val payload = emptyList<Int>()
        val result = PidDecoder.decode(rpmPid, payload)
        assertEquals("NO DATA", result.displayValue)
        assertNull(result.numericValue)
        assertFalse(result.isKnown)
    }
}
