package com.example

import com.example.model.KylaqProtocolProfile
import com.example.protocol.CodingLabCodec
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.ui.screens.formatLiveValue
import com.example.ui.screens.isLiveError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KylaqMultiEcuUdsIntegrationTest {

    @Test
    fun `KylaqProtocolProfile correctly resolves physical TX request headers for all MQB ECUs`() {
        assertEquals("7E0", KylaqProtocolProfile.getPhysicalRequestId("7E8")) // Engine ECM
        assertEquals("7E1", KylaqProtocolProfile.getPhysicalRequestId("7E9")) // TCU 6-AT
        assertEquals("713", KylaqProtocolProfile.getPhysicalRequestId("77D")) // ABS / Brakes
        assertEquals("714", KylaqProtocolProfile.getPhysicalRequestId("77E")) // EPS Steering Assist
        assertEquals("711", KylaqProtocolProfile.getPhysicalRequestId("77B")) // Climatronic HVAC
        assertEquals("710", KylaqProtocolProfile.getPhysicalRequestId("77A")) // CAN Gateway
        assertEquals("708", KylaqProtocolProfile.getPhysicalRequestId("772")) // BCM Central Electrics
        assertEquals("715", KylaqProtocolProfile.getPhysicalRequestId("77F")) // Instruments / Airbag
    }

    @Test
    fun `KylaqProtocolProfile getPhysicalRequestId is idempotent for physical TX headers`() {
        assertEquals("7E0", KylaqProtocolProfile.getPhysicalRequestId("7E0"))
        assertEquals("7E1", KylaqProtocolProfile.getPhysicalRequestId("7E1"))
        assertEquals("714", KylaqProtocolProfile.getPhysicalRequestId("714"))
        assertEquals("713", KylaqProtocolProfile.getPhysicalRequestId("713"))
        assertEquals("711", KylaqProtocolProfile.getPhysicalRequestId("711"))
        assertEquals("710", KylaqProtocolProfile.getPhysicalRequestId("710"))
        assertEquals("708", KylaqProtocolProfile.getPhysicalRequestId("708"))
        assertEquals("715", KylaqProtocolProfile.getPhysicalRequestId("715"))
    }

    @Test
    fun `KylaqProtocolProfile correctly resolves expected RX CAN IDs for all MQB TX headers`() {
        assertEquals("7E8", KylaqProtocolProfile.getExpectedRxId("7E0"))
        assertEquals("7E9", KylaqProtocolProfile.getExpectedRxId("7E1"))
        assertEquals("77D", KylaqProtocolProfile.getExpectedRxId("713"))
        assertEquals("77E", KylaqProtocolProfile.getExpectedRxId("714"))
        assertEquals("77B", KylaqProtocolProfile.getExpectedRxId("711"))
        assertEquals("77A", KylaqProtocolProfile.getExpectedRxId("710"))
        assertEquals("772", KylaqProtocolProfile.getExpectedRxId("708"))
        assertEquals("77F", KylaqProtocolProfile.getExpectedRxId("715"))
    }

    @Test
    fun `SafetyValidator allows bare ATCRA and CRA filter configuration`() {
        assertTrue(SafetyValidator.validateCommand("ATCRA") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATCRA77E") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATCRA 77E") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATCRA77D") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATCRA77B") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATCRA77A") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATCRA7E8") is ValidationResult.Allowed)
    }

    @Test
    fun `formatLiveValue seamlessly resolves fallback PID when primary is unavailable`() {
        val mapWithFallback = mapOf("015C" to "85 °C", "220202" to "no data from ECU")
        val result = formatLiveValue(mapWithFallback, "220202", fallbackPid = "015C")
        assertEquals("85 °C", result)
        assertFalse(isLiveError(mapWithFallback, "220202", fallbackPid = "015C"))
    }

    @Test
    fun `formatLiveValue prefers primary UDS value when valid live data exists`() {
        val mapWithBoth = mapOf("015C" to "85 °C", "220202" to "88 °C")
        val result = formatLiveValue(mapWithBoth, "220202", fallbackPid = "015C")
        assertEquals("88 °C", result)
        assertFalse(isLiveError(mapWithBoth, "220202", fallbackPid = "015C"))
    }

    @Test
    fun `CodingLabCodec ALL_MODULE_HEADERS contains all 9 critical MQB modules`() {
        assertEquals(9, CodingLabCodec.ALL_MODULE_HEADERS.size)
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("7E0"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("7E1"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("710"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("711"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("713"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("714"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("715"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("716"))
        assertTrue(CodingLabCodec.ALL_MODULE_HEADERS.contains("740"))
    }

    @Test
    fun `CodingLabCodec correctly identifies ECU module names for all headers`() {
        assertNotNull(CodingLabCodec.ecuNameForHeader("7E0"))
        assertNotNull(CodingLabCodec.ecuNameForHeader("714"))
        assertNotNull(CodingLabCodec.ecuNameForHeader("713"))
        assertNotNull(CodingLabCodec.ecuNameForHeader("711"))
        assertNotNull(CodingLabCodec.ecuNameForHeader("710"))
    }
}
