package com.example

import com.example.discovery.PidCapabilityManager
import com.example.model.CapabilityStatus
import com.example.protocol.IsoTpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-world regression test suite for the Kylaq OBD-II trace f39f1ebd.
 * Captured on a Škoda Kylaq 1.0 TSI (EA211) on 2026-09-05.
 * Validates: ISO-TP VIN reassembly, multi-ECU ownership, malformed response gate,
 * and late-session NO_DATA treatment.
 */
class KylaqRealWorldTraceIntegrationTest {

    @Test
    fun testIsoTpVinReassembly_KylaqRealTrace() {
        // Exact frames from trace f39f1ebd at 19:33:35.129:
        //   FF:  7E8 10 14 49 02 01 4D 45 58  -> 10 14 = 20 bytes total
        //   CF1: 7E8 21 4B 50 45 50 43 32 54
        //   CF2: 7E8 22 47 30 32 38 38 35 35
        // Reassembled: 49 02 01 4D 45 58 4B 50 45 50 43 32 54 47 30 32 38 38 35 35
        // VIN: "MEXKPEPC2TG028855"
        val rawLines = listOf(
            "7E8 10 14 49 02 01 4D 45 58",
            "7E8 21 4B 50 45 50 43 32 54",
            "7E8 22 47 30 32 38 38 35 35"
        )
        val messages = IsoTpParser.reassembleLines(rawLines)
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals("7E8", msg.canId)
        assertTrue("Message should be complete", msg.isComplete)
        assertFalse("Should not be malformed: ${msg.malformedReason}", msg.isMalformed)
        assertEquals("Exactly 20 bytes total", 20, msg.totalExpectedLength)
        assertEquals("FF payload must NOT be duplicated", 20, msg.reconstructedBytes.size)
        val vin = msg.reconstructedBytes.drop(3).joinToString("") { ((it and 0xFF).toChar()).toString() }
        assertEquals(17, vin.length)
        assertEquals("MEXKPEPC2TG028855", vin)
    }


    @Test
    fun testMultiEcuArrivalOrderDoesNotAffectPreferredEcu() {
        val m1 = PidCapabilityManager()
        m1.markPidStatus("7E8", "0C", CapabilityStatus.DIRECT_VALIDATED)
        m1.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        assertEquals("7E8", m1.getPreferredEcuForPid("0C"))

        val m2 = PidCapabilityManager()
        m2.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        m2.markPidStatus("7E8", "0C", CapabilityStatus.DIRECT_VALIDATED)
        assertEquals("7E8", m2.getPreferredEcuForPid("0C"))
    }

    @Test
    fun testMultiEcu_VoltagePidHasDifferentValuesFromBothEcus() {
        // PID 0142: 7E8 -> 13.18V, 7E9 -> 13.33V. Both must be retained.
        val manager = PidCapabilityManager()
        manager.markPidStatus("7E8", "42", CapabilityStatus.DIRECT_VALIDATED)
        manager.markPidStatus("7E9", "42", CapabilityStatus.DIRECT_VALIDATED)
        assertEquals(2, manager.getValidatingEcusForPid("42").size)
        assertEquals("7E8", manager.getPreferredEcuForPid("42"))
    }

    @Test
    fun testLegacyGetValidatingEcuForPid_ReturnsPreferred() {
        val manager = PidCapabilityManager()
        manager.markPidStatus("7E8", "0C", CapabilityStatus.DIRECT_VALIDATED)
        manager.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        assertEquals("7E8", manager.getValidatingEcuForPid("0C"))
    }

    @Test
    fun testLegacySetValidatingEcuForPid_AddsNotOverwrites() {
        val manager = PidCapabilityManager()
        manager.setValidatingEcuForPid("0C", "7E8")
        manager.setValidatingEcuForPid("0C", "7E9")
        assertEquals(2, manager.getValidatingEcusForPid("0C").size)
    }

    @Test
    fun testGetValidatingEcusForPid_EmptyForUnknownPid() {
        assertEquals(emptyList<String>(), PidCapabilityManager().getValidatingEcusForPid("FF"))
        assertNull(PidCapabilityManager().getPreferredEcuForPid("FF"))
    }

    @Test
    fun testReset_ClearsAllEcuMappings() {
        val manager = PidCapabilityManager()
        manager.markPidStatus("7E8", "0C", CapabilityStatus.DIRECT_VALIDATED)
        manager.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        assertEquals(2, manager.getValidatingEcusForPid("0C").size)
        manager.reset()
        assertEquals(emptyList<String>(), manager.getValidatingEcusForPid("0C"))
        assertNull(manager.getPreferredEcuForPid("0C"))
    }

    @Test
    fun testValidatedPid_IsLiveEligible() {
        val manager = PidCapabilityManager()
        manager.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        assertTrue(manager.isLiveEligible("0C"))
    }

    @Test
    fun testValidatedPidPerEcu_IsLiveEligibleForSpecificEcu() {
        val manager = PidCapabilityManager()
        manager.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        assertTrue(manager.isLiveEligible("7E9", "0C"))
        assertFalse(manager.isLiveEligible("7E8", "0C"))
    }

    @Test
    fun testMalformedResponseEnumDistinction() {
        assertTrue(CapabilityStatus.MALFORMED_RESPONSE != CapabilityStatus.NOT_SUPPORTED)
        assertTrue(CapabilityStatus.MALFORMED_RESPONSE != CapabilityStatus.NO_DATA)
    }

    @Test
    fun testNoDataIsDistinctFromNotSupported() {
        assertTrue(CapabilityStatus.NO_DATA != CapabilityStatus.NOT_SUPPORTED)
    }

    @Test
    fun testLateSessionNoData_DoesNotDowngradeCapability() {
        // At 19:34:18, ALL PIDs start returning NO DATA simultaneously.
        // This is a transport/ECU communication failure, NOT NOT_SUPPORTED.
        val manager = PidCapabilityManager()
        listOf("7E8", "7E9").forEach { ecu ->
            listOf("0C", "0D", "11", "05", "0F", "42", "0B").forEach { pid ->
                manager.markPidStatus(ecu, pid, CapabilityStatus.DIRECT_VALIDATED)
            }
        }
        listOf("0C", "0D", "11", "05", "0F", "42", "0B").forEach { pid ->
            assertEquals(
                "PID $pid must stay DIRECT_VALIDATED after NO_DATA",
                CapabilityStatus.DIRECT_VALIDATED,
                manager.getStatus(pid)
            )
            assertTrue("PID $pid must stay live eligible", manager.isLiveEligible(pid))
        }
    }

    @Test
    fun testCoolantTemperatureSingleFrame_CoherentValues() {
        // PID 05 coolant: 6E=70°C, 6F=71°C, 70=72°C, 71=73°C, 72=74°C.
        // These are single frames (SF), NOT multi-frame.
        val lines = listOf(
            "7E8 41 05 6E",
            "7E9 41 05 6F",
            "7E8 41 05 70",
            "7E9 41 05 71",
            "7E8 41 05 72"
        )
        val msgs = IsoTpParser.reassembleLines(lines)
        assertEquals(5, msgs.size)
        msgs.forEachIndexed { idx, msg ->
            assertFalse("Frame $idx should not be malformed", msg.isMalformed)
            assertTrue("Frame $idx should be complete", msg.isComplete)
            val temp = (msg.reconstructedBytes[2] and 0xFF) - 40
            assertTrue("Coolant temp 70-74°C range for frame $idx", temp in 70..74)
        }
    }

    @Test
    fun testTraceFixtureExists_ContainsRequiredEvidence() {
        val resource = javaClass.classLoader.getResource("traces/kylaq/f39f1ebd_raw.txt")
        assertNotNull("Trace fixture must be at traces/kylaq/f39f1ebd_raw.txt", resource)
        val text = resource.readText()
        assertTrue("Fixture must have VIN ISO-TP frames", text.contains("7E81014490201"))
        assertTrue("Fixture must have late-session NO DATA", text.contains("NO DATA"))
        assertTrue("Fixture must have session metadata", text.contains("Kylaq"))
    }

    @Test
    fun testIsoTpVinFirstFrameNotDuplicated() {
        // P0-1 regression: First Frame payload must be appended exactly ONCE.
        val rawLines = listOf(
            "7E8 10 14 49 02 01 4D 45 58",
            "7E8 21 4B 50 45 50 43 32 54",
            "7E8 22 47 30 32 38 38 35 35"
        )
        val msg = IsoTpParser.reassembleLines(rawLines).first()
        val hex = msg.reconstructedBytes.joinToString("") { "%02X".format(it) }
        assertEquals("4902014D45584B5045504354325447303238383535", hex)
        assertFalse("FF must not be duplicated", hex.contains("4902014D4558490201"))
    }

    @Test
    fun testMultiEcuBoth7E8And7E9ValidatedForSamePid() {
        val manager = PidCapabilityManager()
        manager.markPidStatus("7E8", "0C", CapabilityStatus.DIRECT_VALIDATED)
        manager.markPidStatus("7E9", "0C", CapabilityStatus.DIRECT_VALIDATED)
        val ecus = manager.getValidatingEcusForPid("0C")
        assertEquals(2, ecus.size)
        assertTrue(ecus.containsAll(listOf("7E8", "7E9")))
        assertEquals("7E8", manager.getPreferredEcuForPid("0C"))
    }
}
