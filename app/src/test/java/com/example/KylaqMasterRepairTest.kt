package com.example

import com.example.discovery.EcuDiscoveryManager
import com.example.discovery.PidCapabilityManager
import com.example.protocol.IsoTpParser
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KylaqMasterRepairTest {

    @Test
    fun testModeResponseStructuralValidation() {
        // A random response with "42" in it but not a real mode 02 positive response
        val fakeLines = listOf("7E8 03 41 00 42") // 42 is data
        val m2Expected = 0x42
        
        // We simulate the logic added to EcuDiscoveryManager.isPositiveResponse
        val reconstructed = IsoTpParser.reassembleLines(fakeLines)
        val isPositive = reconstructed.any { it.reconstructedBytes.isNotEmpty() && it.reconstructedBytes[0] == m2Expected }
        
        assertFalse("Should not validate based on string '42' being in the payload", isPositive)
        
        // Real Mode 02 response
        val realLines = listOf("7E8 04 42 02 00 00")
        val realReconstructed = IsoTpParser.reassembleLines(realLines)
        val isRealPositive = realReconstructed.any { it.reconstructedBytes.isNotEmpty() && it.reconstructedBytes[0] == m2Expected }
        
        assertTrue("Should validate real positive response", isRealPositive)
    }

    @Test
    fun testSafetyValidatorStrictAt() {
        val validator = SafetyValidator

        // Malformed suffixes
        assertTrue(validator.validateCommand("ATSPX") is ValidationResult.Rejected)
        assertTrue(validator.validateCommand("ATSH12Z") is ValidationResult.Rejected)
        assertTrue(validator.validateCommand("ATCRA01G") is ValidationResult.Rejected)

        // Valid
        assertTrue(validator.validateCommand("ATSP6") is ValidationResult.Allowed)
        assertTrue(validator.validateCommand("ATSH7E0") is ValidationResult.Allowed)
        assertTrue(validator.validateCommand("ATZ") is ValidationResult.Allowed)
        assertTrue(validator.validateCommand("ATMA") is ValidationResult.Allowed)
        
        // Blocked services
        assertTrue(validator.validateCommand("04") is ValidationResult.Rejected)
        assertTrue(validator.validateCommand("08") is ValidationResult.Rejected)
        assertTrue(validator.validateCommand("2701") is ValidationResult.Rejected)
        assertTrue(validator.validateCommand("3400") is ValidationResult.Rejected)
    }

    @Test
    fun testCapabilityManagerLiveEligibility() {
        val manager = PidCapabilityManager()
        val pid = "010C"
        val ecu = "7E8"
        
        manager.markPidStatus(pid, com.example.model.CapabilityStatus.BITMAP_SUPPORTED)
        assertFalse(manager.isLiveEligible(pid))
        
        manager.setValidatingEcuForPid(pid, ecu)
        assertFalse(manager.isLiveEligible(pid)) // Validating ECU isn't enough, status must be CONFIRMED
        
        manager.markPidValidated(ecu, pid, com.example.model.CapabilityStatus.DIRECT_VALIDATED)
        assertTrue("Should be live eligible after DIRECT_VALIDATED", manager.isLiveEligible(pid))
        assertEquals("Should return the validated ECU", ecu, manager.getValidatingEcuForPid(pid))

        // Direct validated without ECU must NOT be live eligible
        val pidNoEcu = "010D"
        manager.markPidStatus(pidNoEcu, com.example.model.CapabilityStatus.DIRECT_VALIDATED)
        assertFalse("DIRECT_VALIDATED without ECU must not be live eligible", manager.isLiveEligible(pidNoEcu))
    }

    @Test
    fun testStructuralNegativeResponseHandling() {
        // Frame with 7F in the data payload (e.g. byte 3 of bitmap is 0x7F)
        val data7fFrame = listOf("7E8 06 41 00 BF BF 7F 00")
        val decoded = com.example.protocol.PidDiscoveryDecoder.decodeFromRawResponse(0x00, data7fFrame)
        assertNotNull("Frame with 0x7F in data bytes must NOT be rejected as negative response", decoded)
        assertEquals(1, decoded!!.ecuResponses.size)
        assertEquals("7E8", decoded.ecuResponses[0].rxCanId)
        assertEquals(0x7F.toByte(), decoded.ecuResponses[0].bitmap[2])

        // Explicit 7F 01 negative response
        val negativeFrame = listOf("7E8 03 7F 01 11")
        val negDecoded = com.example.protocol.PidDiscoveryDecoder.decodeFromRawResponse(0x00, negativeFrame)
        assertNull("7F 01 negative response must return null", negDecoded)
    }

    @Test
    fun testTimeoutDistinctionFromUnsupported() {
        val manager = PidCapabilityManager()
        val pid = "010C"
        manager.markPidStatus(pid, com.example.model.CapabilityStatus.TIMEOUT)
        
        assertEquals(com.example.model.CapabilityStatus.TIMEOUT, manager.getStatus(pid))
        assertFalse("TIMEOUT must not be treated as supported", manager.isPidSupported(pid))
        assertFalse("TIMEOUT must not be treated as live eligible", manager.isLiveEligible(pid))
    }
}
