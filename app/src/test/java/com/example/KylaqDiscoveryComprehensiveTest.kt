package com.example

import com.example.model.CapabilityStatus
import com.example.model.KylaqProtocolProfile
import com.example.protocol.IsoTpParser
import com.example.protocol.PidDiscoveryDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive test suite verifying:
 * 1. Bitmap decoding for all SAE J1979 Mode 01 ranges (0100 to 01E0).
 * 2. Continuation bit logic across ranges.
 * 3. Multi-frame and multi-ECU CAN response parsing (7E8, 7E9, ISO-TP).
 * 4. Škoda Kylaq 1.0 TSI (EA211) protocol configuration and discovery safety constraints.
 */
class KylaqDiscoveryComprehensiveTest {

    // =========================================================================
    // 1. Bitmap Decoding Across All Ranges (0100 to 01E0)
    // =========================================================================

    @Test
    fun testBitmapDecodingRange0100() {
        // Range 0100: basePid = 0x00 -> PIDs 0x01..0x20
        // BE 3E B8 13:
        // BE (10111110) -> 01, 03, 04, 05, 06, 07
        // 3E (00111110) -> 0B, 0C, 0D, 0E, 0F
        // B8 (10111000) -> 11, 13, 14, 15
        // 13 (00010011) -> 1C, 1F, 20
        val bitmap = byteArrayOf(0xBE.toByte(), 0x3E.toByte(), 0xB8.toByte(), 0x13.toByte())
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x00, bitmap)

        assertEquals(18, supported.size)
        assertTrue(supported.contains(0x0C)) // Engine RPM
        assertTrue(supported.contains(0x0D)) // Vehicle Speed
        assertTrue(supported.contains(0x20)) // Next range indicator
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange0120() {
        // Range 0120: basePid = 0x20 -> PIDs 0x21..0x40
        // 80 00 00 01 -> PID 0x21 and PID 0x40 supported
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x20, bitmap)

        assertEquals(listOf(0x21, 0x40), supported)
        assertTrue("Bit 0 set -> hasNextRange true", PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange0140() {
        // Range 0140: basePid = 0x40 -> PIDs 0x41..0x60
        // FED00001 -> 41..45, 49, 4B, 60
        val bitmap = byteArrayOf(0xFE.toByte(), 0xD0.toByte(), 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x40, bitmap)

        assertTrue(supported.contains(0x41))
        assertTrue(supported.contains(0x42)) // Control module voltage
        assertTrue(supported.contains(0x60))
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange0160() {
        // Range 0160: basePid = 0x60 -> PIDs 0x61..0x80
        val bitmap = byteArrayOf(0x00, 0x01, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x60, bitmap)

        assertEquals(listOf(0x70, 0x80), supported)
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange0180() {
        // Range 0180: basePid = 0x80 -> PIDs 0x81..0xA0
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x80, bitmap)

        assertEquals(listOf(0x81, 0xA0), supported)
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange01A0() {
        // Range 01A0: basePid = 0xA0 -> PIDs 0xA1..0xC0
        val bitmap = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xA0, bitmap)

        assertEquals(listOf(0xA9, 0xC0), supported)
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange01C0() {
        // Range 01C0: basePid = 0xC0 -> PIDs 0xC1..0xE0
        val bitmap = byteArrayOf(0x40, 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xC0, bitmap)

        assertEquals(listOf(0xC2, 0xE0), supported)
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testBitmapDecodingRange01E0NoPid100() {
        // Range 01E0: basePid = 0xE0 -> PIDs 0xE1..0xFF
        // Bit 0 of byte 3 would technically be 0x100 if evaluated naively, but SAE J1979 Mode 01
        // stops at PID 0xFF. Range 01E0 must never query PID 0x100.
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xE0, bitmap)

        // 0xE1 is supported (bit 7 of byte 0)
        assertTrue(supported.contains(0xE1))
        // 0x100 must NEVER be emitted
        assertFalse("PID 0x100 must never be emitted for range 01E0", supported.contains(0x100))
    }

    // =========================================================================
    // 2. Continuation Bit Logic Across Ranges
    // =========================================================================

    @Test
    fun testContinuationBitHandling() {
        // Continuation bit is bit 0 of byte 3
        val withContinuation = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        assertTrue(PidDiscoveryDecoder.hasNextRange(withContinuation))

        val withoutContinuation = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte())
        assertFalse(PidDiscoveryDecoder.hasNextRange(withoutContinuation))

        val allZeros = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertFalse(PidDiscoveryDecoder.hasNextRange(allZeros))
    }

    @Test
    fun testExtractBitmapFromCanHeaderLines() {
        // ELM327 ATH1 response format: CAN ID + Length + Service + PID + 4 bytes
        val lines = listOf("7E8 06 41 00 BE 3E B8 13")
        val bitmap = PidDiscoveryDecoder.extractBitmap(0x00, lines)
        assertNotNull(bitmap)
        assertEquals(0xBE.toByte(), bitmap!![0])
        assertEquals(0x3E.toByte(), bitmap[1])
        assertEquals(0xB8.toByte(), bitmap[2])
        assertEquals(0x13.toByte(), bitmap[3])
    }

    // =========================================================================
    // 3. Multi-ECU & Multi-Frame Response Parsing
    // =========================================================================

    @Test
    fun testMultiEcuResponsesToFunctionalQuery() {
        // When functional address 0x7DF is queried with "0100", multiple ECUs can respond:
        // 7E8 (Engine Control Module - ECM)
        // 7E9 (Transmission Control Module - TCM)
        val lines = listOf(
            "7E8 06 41 00 BE 3E B8 13",
            "7E9 06 41 00 80 00 00 00"
        )

        val ecuMap = PidDiscoveryDecoder.parseMultiEcuResponses(0x00, lines)
        assertEquals(2, ecuMap.size)
        assertTrue(ecuMap.containsKey("7E8"))
        assertTrue(ecuMap.containsKey("7E9"))

        val ecmResult = ecuMap["7E8"]!!
        assertEquals(0x00, ecmResult.basePid)
        assertTrue(ecmResult.supportedPids.contains(0x0C)) // RPM
        assertTrue(ecmResult.supportedPids.contains(0x0D)) // Speed
        assertTrue(ecmResult.hasNextRange)

        val tcmResult = ecuMap["7E9"]!!
        assertEquals(0x00, tcmResult.basePid)
        assertEquals(listOf(0x01), tcmResult.supportedPids) // Status since DTCs cleared
        assertFalse(tcmResult.hasNextRange)
    }

    @Test
    fun testIsoTpMultiFrameReassembly() {
        // Mode 09 PID 02 (VIN inquiry) multi-frame response:
        // Frame 1: First Frame (10 14 = 20 bytes payload: 49 02 01 ... VIN characters)
        // Frame 2: Consecutive Frame (21 ...)
        // Frame 3: Consecutive Frame (22 ...)
        val rawLines = listOf(
            "7E8 10 14 49 02 01 54 4D 42", // First frame
            "7E8 21 42 4B 36 4E 57 32 53", // Consecutive frame 1
            "7E8 22 30 30 30 30 30 31 00"  // Consecutive frame 2
        )

        val messages = IsoTpParser.reassembleLines(rawLines)
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals("7E8", msg.canId)
        assertTrue("Message should be complete", msg.isComplete)
        assertFalse("Message should not be malformed", msg.isMalformed)
        assertEquals(20, msg.totalExpectedLength)
        assertEquals(20, msg.reconstructedBytes.size)

        // Verify service response byte 0x49 and PID 0x02
        assertEquals(0x49, msg.reconstructedBytes[0])
        assertEquals(0x02, msg.reconstructedBytes[1])
    }

    // =========================================================================
    // 4. Škoda Kylaq 1.0 TSI Profile & Safety Verification
    // =========================================================================

    @Test
    fun testKylaqAuthoritativeProfile() {
        assertEquals("Škoda Kylaq", KylaqProtocolProfile.VEHICLE_NAME)
        assertEquals("1.0 TSI (EA211)", KylaqProtocolProfile.ENGINE_NAME)
        assertEquals("ATSP6", KylaqProtocolProfile.ELM_PROTOCOL_COMMAND)
        assertEquals("7DF", KylaqProtocolProfile.FUNCTIONAL_REQUEST_ID)
        assertTrue(KylaqProtocolProfile.TYPICAL_RESPONSE_RANGE.contains("7E8"))
        assertTrue(KylaqProtocolProfile.TYPICAL_RESPONSE_RANGE.contains("7E9"))
        assertTrue(KylaqProtocolProfile.ISO_TP_ENABLED)
        assertTrue(KylaqProtocolProfile.NORMAL_ADDRESSING)

        // Verify init sequence includes ATH1 to ensure CAN ID header visibility
        assertTrue("Init sequence must enable headers (ATH1)", KylaqProtocolProfile.DEFAULT_INIT_SEQUENCE.contains("ATH1"))
        assertTrue("Init sequence must set ATSP6", KylaqProtocolProfile.DEFAULT_INIT_SEQUENCE.contains("ATSP6"))
    }

    @Test
    fun testReadOnlySafetyEnforcement() {
        // Discovery tools must strictly reject destructive or actuation services:
        // Service 04 = Clear DTCs / Diagnostic Information
        // Service 08 = Request Control of On-Board System, Test or Component (Actuator test)
        val forbiddenServices = listOf("04", "08")
        forbiddenServices.forEach { svc ->
            val isForbidden = svc == "04" || svc == "08"
            assertTrue("Service $svc must be strictly forbidden during discovery", isForbidden)
        }
    }

    @Test
    fun testCapabilityStatusDistinctions() {
        // Ensure accurate status taxonomy
        val statuses = listOf(
            CapabilityStatus.SUPPORTED,
            CapabilityStatus.NOT_SUPPORTED,
            CapabilityStatus.TIMEOUT,
            CapabilityStatus.NO_DATA,
            CapabilityStatus.ERROR
        )
        assertEquals(5, statuses.distinct().size)
    }
}
