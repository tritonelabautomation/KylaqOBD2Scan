package com.example

import com.example.discovery.CapabilitySnapshotStore
import com.example.discovery.PidCapabilityManager
import com.example.model.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner regression (2026-09-16 screenshots): the trip card read "reference 178 Nm" on a
 * car that answers 175 Nm on PID 0163, because (a) the learned capability matrix lived
 * only in RAM - every restart forgot discovery/live validation - and (b) 0163 sits in
 * the SLOW poll tier behind isLiveEligible(), so it could never be polled before being
 * validated: chicken and egg. Seed + persistence close both halves.
 */
class CapabilitySnapshotPersistenceTest {

    private class MemoryStore : CapabilitySnapshotStore {
        var text: String? = null
        override fun load(): String? = text
        override fun save(snapshot: String) {
            text = snapshot
        }
    }

    @Test
    fun serializeAndParseRoundTripKeepsEveryLayer() {
        val global = mapOf(
            "63" to CapabilityStatus.DIRECT_VALIDATED,
            "0163" to CapabilityStatus.DIRECT_VALIDATED,
            "0C" to CapabilityStatus.BITMAP_SUPPORTED
        )
        val ecu = mapOf(
            "7E8" to mapOf(
                "63" to CapabilityStatus.DIRECT_VALIDATED,
                "0163" to CapabilityStatus.DIRECT_VALIDATED
            ),
            "7E9" to mapOf("0C" to CapabilityStatus.BITMAP_SUPPORTED)
        )
        val validating = mapOf("63" to listOf("7E8"), "0C" to listOf("7E9", "7E8"))

        val text = PidCapabilityManager.serializeSnapshot(global, ecu, validating)
        val snap = PidCapabilityManager.parseSnapshot(text)

        assertEquals("canonical 2-hex keys only", setOf("63", "0C"), snap.global.keys)
        assertEquals(CapabilityStatus.DIRECT_VALIDATED, snap.global["63"])
        assertEquals(setOf("7E8", "7E9"), snap.ecu.keys)
        assertEquals(CapabilityStatus.BITMAP_SUPPORTED, snap.ecu["7E9"]?.get("0C"))
        assertEquals(listOf("7E8", "7E9"), snap.validating["0C"])

        // Corrupt/unknown status names are skipped, never crash a restore.
        val dirty = PidCapabilityManager.parseSnapshot("G|63|NOT_A_STATUS\nG|0C|DIRECT_VALIDATED\n")
        assertEquals(setOf("0C"), dirty.global.keys)
    }

    @Test
    fun freshManagerSeedsTheTorqueReferencePidsLiveEligible() {
        val m = PidCapabilityManager()
        assertTrue("0163 must be pollable from the first connect", m.isLiveEligible("0163"))
        assertTrue("0164 must be pollable from the first connect", m.isLiveEligible("0164"))
        assertEquals("7E8", m.getPreferredEcuForPid("0163"))
    }

    @Test
    fun validatedCapabilitySurvivesAProcessRestart() {
        val store = MemoryStore()
        val first = PidCapabilityManager(store)
        first.markPidStatus("7E8", "0163", CapabilityStatus.DIRECT_VALIDATED)
        assertNotNull("marking must persist the matrix", store.text)

        val second = PidCapabilityManager(store)
        assertTrue(second.isLiveEligible("0163"))
        assertEquals(CapabilityStatus.DIRECT_VALIDATED, second.getStatus("0163"))
        assertEquals(listOf("7E8"), second.getValidatingEcusForPid("0163"))
    }

    @Test
    fun storedNotSupportedBeatsTheBootstrapSeed() {
        val store = MemoryStore()
        store.text = "G|63|NOT_SUPPORTED\n"
        val m = PidCapabilityManager(store)
        assertFalse("learned NOT_SUPPORTED must win over the seed", m.isLiveEligible("0163"))
        assertEquals(CapabilityStatus.NOT_SUPPORTED, m.getStatus("0163"))
        // 0164 has no stored entry -> still seeded.
        assertTrue(m.isLiveEligible("0164"))
    }

    @Test
    fun resetReSeedsButNeverDegradesTheStore() {
        val store = MemoryStore()
        val m = PidCapabilityManager(store)
        m.markPidStatus("7E8", "0163", CapabilityStatus.DIRECT_VALIDATED)
        val before = store.text
        m.reset()
        assertEquals("reset must not rewrite the learned snapshot", before, store.text)
        assertTrue("seed re-applies where no entry exists", m.isLiveEligible("0164"))
    }
}
