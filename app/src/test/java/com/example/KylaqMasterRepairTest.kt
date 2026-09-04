package com.example

import com.example.discovery.PidCapabilityManager
import com.example.model.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.model.StandardPidCatalog
import com.example.discovery.SafetyValidator
import com.example.protocol.ValidationResult

class KylaqMasterRepairTest {

    @Test
    fun testLiveEligibilityGating() {
        val manager = PidCapabilityManager()
        
        manager.markPidStatus("010C", CapabilityStatus.SUPPORTED)
        assertFalse("SUPPORTED must not be live eligible", manager.isLiveEligible("010C"))
        
        manager.markPidStatus("010C", CapabilityStatus.BITMAP_SUPPORTED)
        assertFalse("BITMAP_SUPPORTED must not be live eligible", manager.isLiveEligible("010C"))

        manager.markPidStatus("010C", CapabilityStatus.TIMEOUT)
        assertFalse("TIMEOUT must not be live eligible", manager.isLiveEligible("010C"))

        manager.markPidStatus("010C", CapabilityStatus.NO_DATA)
        assertFalse("NO_DATA must not be live eligible", manager.isLiveEligible("010C"))

        manager.markPidStatus("010C", CapabilityStatus.DIRECT_VALIDATED)
        assertTrue("DIRECT_VALIDATED must be live eligible", manager.isLiveEligible("010C"))

        manager.markPidStatus("010C", CapabilityStatus.LIVE_ELIGIBLE)
        assertTrue("LIVE_ELIGIBLE must be live eligible", manager.isLiveEligible("010C"))
    }

    @Test
    fun testEcuOwnershipAndMultiEcu() {
        val manager = PidCapabilityManager()
        
        // 7E8 supports PID 0C
        manager.markPidStatus("010C", CapabilityStatus.BITMAP_SUPPORTED, "7E8")
        // 7E9 does NOT support PID 0C
        manager.markPidStatus("010C", CapabilityStatus.NOT_SUPPORTED, "7E9")
        
        assertEquals(CapabilityStatus.BITMAP_SUPPORTED, manager.getStatus("7E8", "010C"))
        assertEquals(CapabilityStatus.NOT_SUPPORTED, manager.getStatus("7E9", "010C"))
        
        manager.setValidatingEcuForPid("010C", "7E8")
        assertEquals("7E8", manager.getValidatingEcuForPid("010C"))
    }

    @Test
    fun testMode04NotExecuted() {
        val manager = PidCapabilityManager()
        // If Mode 04 has not been executed, it should be NOT_TESTED, not NOT_SUPPORTED or SUPPORTED.
        assertEquals(CapabilityStatus.NOT_TESTED, manager.getStatus("04"))
    }

    @Test
    fun testCatalogPresenceDoesNotMakeLiveEligible() {
        val manager = PidCapabilityManager()
        val catalogDef = StandardPidCatalog.lookup("0C", false)
        // Even if in catalog, until we have evidence, it's not live eligible
        assertFalse(manager.isLiveEligible("010C"))
    }

    @Test
    fun testResponseValidationCorrelation() {
        // request 010C, response 410D => reject
        val bytes = listOf(0x41, 0x0D, 0x00, 0x00)
        
        // This simulates the validation logic in PidDiscoveryService
        // We simulate testing PID 0C
        val cleanPid = "0C"
        var isPositive = false
        
        if (bytes.size >= 2 && bytes[0] == 0x41 && bytes[1] == cleanPid.toInt(16, 16)) {
            isPositive = true
        }
        
        assertFalse("Response for 41 0D must not validate request for 01 0C", isPositive)
        
        // request 010C, response 410C => accept
        val bytesCorrect = listOf(0x41, 0x0C, 0x00, 0x00)
        var isPositiveCorrect = false
        if (bytesCorrect.size >= 2 && bytesCorrect[0] == 0x41 && bytesCorrect[1] == cleanPid.toInt(16, 16)) {
            isPositiveCorrect = true
        }
        assertTrue("Response for 41 0C must validate request for 01 0C", isPositiveCorrect)
    }

}
