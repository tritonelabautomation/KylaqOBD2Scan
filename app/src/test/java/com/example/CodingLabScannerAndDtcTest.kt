package com.example

import com.example.protocol.CodingLabCodec
import com.example.protocol.DtcDecoder
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end verification of UDS Multi-ECU DTC Scanner (Service 0x19),
 * Range Scanner (Service 0x22 Discovery Engine), and MQB Adaptation Channels.
 */
class CodingLabScannerAndDtcTest {

    @Test
    fun `extracts 3-byte VAG UDS DTCs from Service 0x19 multi-frame response`() {
        // Multi-frame 19 02 09 response containing P030000 (Random Misfire) and U112100 (Databus missing message)
        // 59 02 09 [03 00 00 A8] [D1 21 00 2F]
        val rawResponse = "7E8 10 0E 59 02 09 03 00 00 A8 7E8 21 D1 21 00 2F 00 00 00 00"
        val dtcs = DtcDecoder.extractUdsDtcs(rawResponse)

        assertEquals(2, dtcs.size)

        // First DTC: P030000 (0x03, 0x00, 0x00), Status: 0xA8 (Confirmed + Warning Lamp)
        val p0300 = dtcs[0]
        assertEquals("P030000", p0300.formattedCode)
        assertEquals(0xA8, p0300.statusByte)
        assertTrue(p0300.isConfirmed)
        assertTrue(p0300.isWarningRequested)

        // Second DTC: U112100 (0xD1, 0x21, 0x00), Status: 0x2F (Pending + Confirmed + Test Failed)
        val u1121 = dtcs[1]
        assertEquals("U112100", u1121.formattedCode)
        assertEquals(0x2F, u1121.statusByte)
        assertTrue(u1121.isPending)
        assertTrue(u1121.isConfirmed)
    }

    @Test
    fun `extracts Chassis and Body UDS DTCs with correct letter prefix`() {
        // Chassis DTC: C10AC07 (Steering angle sensor mechanical malfunction) -> 0x50, 0xAC, 0x07, Status 0x08
        // Body DTC: B10A315 (Airbag igniter open circuit) -> 0x90, 0xA3, 0x15, Status 0x09
        val chassisRaw = "77E 07 59 02 09 50 AC 07 08"
        val bodyRaw = "77F 07 59 02 09 90 A3 15 09"

        val chassisDtcs = DtcDecoder.extractUdsDtcs(chassisRaw)
        assertEquals(1, chassisDtcs.size)
        assertEquals("C10AC07", chassisDtcs[0].formattedCode)
        assertTrue(chassisDtcs[0].formattedCode.startsWith("C"))

        val bodyDtcs = DtcDecoder.extractUdsDtcs(bodyRaw)
        assertEquals(1, bodyDtcs.size)
        assertEquals("B10A315", bodyDtcs[0].formattedCode)
        assertTrue(bodyDtcs[0].formattedCode.startsWith("B"))
    }

    @Test
    fun `handles clean healthy ECU response with 0 DTCs`() {
        val cleanRaw = "7E8 03 59 02 09"
        val dtcs = DtcDecoder.extractUdsDtcs(cleanRaw)
        assertTrue("Expected 0 DTCs from clean response", dtcs.isEmpty())
    }

    @Test
    fun `classifies positive UDS 0x22 responses and decodes ASCII payloads`() {
        // Positive response for DID F190 (VIN): 62 F1 90 followed by ASCII "MEXKPEPC2TG028855"
        val vinHex = "4D45584B50455043325447303238383535"
        val lines = listOf("7E8 10 14 62 F1 90 4D 45 58 4B", "7E8 21 50 45 50 43 32 54 47 30", "7E8 22 32 38 38 35 35 00 00 00")
        val classification = CodingLabCodec.classifyResponse(lines.joinToString(" "), "F190")
        assertEquals("POSITIVE", classification)

        val payload = CodingLabCodec.decodePositive(lines, "F190")
        assertTrue(payload.startsWith(vinHex))
        val ascii = CodingLabCodec.hexToAscii(payload)
        assertTrue(ascii.contains("MEXKPEPC2TG028855"))
    }

    @Test
    fun `classifies UDS negative response codes (NRC) accurately`() {
        // NRC 0x31: requestOutOfRange (DID not present in ECU)
        val nrc31 = "7E8 03 7F 22 31"
        assertEquals("NRC:31", CodingLabCodec.classifyResponse(nrc31, "0999"))
        assertEquals("31", CodingLabCodec.negativeNrc(nrc31))
        assertTrue(CodingLabCodec.nrcName("31").contains("requestOutOfRange"))

        // NRC 0x33: securityAccessDenied
        val nrc33 = "710 03 7F 22 33"
        assertEquals("NRC:33", CodingLabCodec.classifyResponse(nrc33, "0225"))
        assertEquals("33", CodingLabCodec.negativeNrc(nrc33))
        assertTrue(CodingLabCodec.nrcName("33").contains("securityAccessDenied"))

        // NRC 0x7E: subFunctionNotSupportedInActiveSession
        val nrc7E = "714 03 7F 22 7E"
        assertEquals("NRC:7E", CodingLabCodec.classifyResponse(nrc7E, "040F"))
        assertEquals("7E", CodingLabCodec.negativeNrc(nrc7E))
        assertTrue(CodingLabCodec.nrcName("7E").contains("subFunctionNotSupportedInActiveSession"))
    }

    @Test
    fun `read-only safety verification for all MQB Adaptation presets`() {
        // Verify every preset adaptation DID:
        // 1. Is read-only Service 0x22 (ReadDataByIdentifier)
        // 2. Passes SafetyValidator
        // 3. Any attempt to write via Service 0x2E is REJECTED by SafetyValidator
        for (preset in CodingLabCodec.MQB_ADAPTATIONS) {
            val readCmd = CodingLabCodec.readRequest(preset.did)
            assertTrue("Read command $readCmd must start with 22", readCmd.startsWith("22"))
            assertEquals(ValidationResult.Allowed, SafetyValidator.validateCommand(readCmd))

            // Attempting write (Service 0x2E) must be strictly REJECTED
            val writeCmd = "2E${preset.did}01"
            val writeValidation = SafetyValidator.validateCommand(writeCmd)
            assertTrue("Write command $writeCmd must be REJECTED", writeValidation is ValidationResult.Rejected)
        }
    }
}
