package com.example

import com.example.model.DecoderType
import com.example.model.DefaultPidDefinitions
import com.example.model.PidDefinition
import com.example.model.PidDefinitionReconciler
import com.example.model.PollingPriority
import com.example.model.StandardPidCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A catalogue correction that never reaches an installed device is not a fix.
 *
 * `pid_definitions_json` is written on every settings change and used to be read back
 * verbatim, so the 2026-09-17 J1979 pass would have changed nothing on the owner's phone:
 * the saved list still said "Turbocharger Boost Pressure", still decoded `01A0` as a
 * transmission sump temperature and still called the odometer "Unknown Research PID 01A6".
 * [PidDefinitionReconciler] is the delivery mechanism, and these tests pin the ownership
 * split it implements: the catalogue owns what a PID *is*, the user owns how it is polled.
 */
class PidCatalogReconciliationTest {

    /** A definition as an OLD build would have persisted it. */
    private fun stale(
        pid: String,
        name: String,
        decoder: DecoderType,
        enabled: Boolean = true,
        dataBytes: Int = 1,
        unit: String = "",
        priority: PollingPriority = PollingPriority.MEDIUM,
        intervalMs: Long = 500L,
        canHeader: String = "7DF",
        rxId: String = "7E8",
        isResearch: Boolean = false
    ) = PidDefinition(
        id = "01$pid", service = "01", pid = pid,
        name = name, shortName = name, unit = unit,
        canHeader = canHeader, expectedRxId = rxId,
        defaultIntervalMs = intervalMs, enabled = enabled,
        decoderType = decoder, isResearch = isResearch,
        priority = priority, dataBytes = dataBytes
    )

    @Test
    fun aStaleSavedDefinitionIsRefreshedFromTheCatalogue() {
        // what an old build saved for the range marker PID A0
        val saved = stale("A0", "Transmission Sump Temperature", DecoderType.TEMP_MINUS_40,
            unit = "\u00B0C", priority = PollingPriority.SLOW, intervalMs = 3000L)
        val fresh = PidDefinitionReconciler.reconcile(saved)

        assertTrue("the fabricated gearbox temperature must be gone: ${fresh.name}",
            fresh.name.contains("range marker"))
        assertFalse("a range marker is never a live channel", fresh.enabled)
        assertEquals(DecoderType.RESEARCH_RAW, fresh.decoderType)
        assertEquals(4, fresh.dataBytes)
        assertEquals("", fresh.unit)
    }

    @Test
    fun theOdometerReplacesTheUnknownResearchChannelOnAnUpgradedDevice() {
        val saved = stale("A6", "Unknown Research PID 01A6", DecoderType.RESEARCH_RAW,
            unit = "RAW", priority = PollingPriority.SLOW, intervalMs = 3000L, isResearch = true)
        val fresh = PidDefinitionReconciler.reconcile(saved)

        assertEquals("Odometer", fresh.name)
        assertEquals("km", fresh.unit)
        assertEquals(DecoderType.ODOMETER_4B, fresh.decoderType)
        assertEquals(4, fresh.dataBytes)
        assertTrue("a proven channel is not research any more", fresh.enabled)
        assertFalse(fresh.isResearch)
    }

    @Test
    fun theWithdrawnBoostClaimIsDisabledEvenIfTheUserHadItSwitchedOn() {
        // J1979 PID 65 is an auxiliary input/output bitmap. The "16 kPa" reading rested on a
        // name the standard does not use, so the catalogue disabled it - and a user toggle
        // must not be able to put the guess back on the dashboard.
        val saved = stale("65", "Turbocharger Boost Pressure", DecoderType.RAW_A_KPA,
            enabled = true, unit = "kPa", priority = PollingPriority.FAST, intervalMs = 150L)
        val fresh = PidDefinitionReconciler.reconcile(saved)

        assertFalse(fresh.enabled)
        assertTrue(fresh.name.contains("Auxiliary Input"))
        assertEquals(DecoderType.RESEARCH_RAW, fresh.decoderType)
        assertEquals("an unproven bitmap must leave the FAST band",
            PollingPriority.SLOW, fresh.priority)
        assertTrue(fresh.defaultIntervalMs >= PollingPriority.SLOW.floorMs)
    }

    @Test
    fun userOwnedFieldsSurviveTheReconciliation() {
        val saved = stale("0C", "Engine RPM", DecoderType.RPM_FORMULA,
            canHeader = "7E0", rxId = "7E8", intervalMs = 250L)
        val fresh = PidDefinitionReconciler.reconcile(saved)

        assertEquals("the owner's direct-ECU addressing is theirs, not the catalogue's",
            "7E0", fresh.canHeader)
        assertEquals("7E8", fresh.expectedRxId)
        assertTrue("a user-set interval is never shortened by a catalogue refresh",
            fresh.defaultIntervalMs >= 250L)
        assertTrue(fresh.enabled)
    }

    @Test
    fun aUserDisabledChannelStaysDisabled() {
        val saved = stale("0C", "Engine RPM", DecoderType.RPM_FORMULA, enabled = false)
        assertFalse(PidDefinitionReconciler.reconcile(saved).enabled)
    }

    @Test
    fun anUnknownUserAddedPidIsLeftAlone() {
        val custom = PidDefinition(
            id = "01ZZ", service = "01", pid = "ZZ",
            name = "My custom channel", shortName = "Mine", unit = "x",
            defaultIntervalMs = 3000L, decoderType = DecoderType.CUSTOM_EXPRESSION,
            formulaDisplay = "A * 2"
        )
        val fresh = PidDefinitionReconciler.reconcile(custom)
        assertEquals("My custom channel", fresh.name)
        assertEquals(DecoderType.CUSTOM_EXPRESSION, fresh.decoderType)
        assertEquals("A * 2", fresh.formulaDisplay)
    }

    @Test
    fun theShippedDefaultsCarryTheOdometerAndNoDuplicateIds() {
        val defaults = DefaultPidDefinitions.getDefaults()
        val odo = defaults.firstOrNull { it.hexPid == "A6" }
        assertTrue("PID A6 is claimed and answered by the real car, so it must be polled",
            odo != null)
        assertEquals("Odometer", odo!!.name)
        assertEquals(DecoderType.ODOMETER_4B, odo.decoderType)

        assertEquals("PID A6 must be polled exactly once", 1, defaults.count { it.hexPid == "A6" })
        // NOTE: getDefaults() and the J1979 "additional" catalogue list legitimately define the
        // same physical PID twice (both use 4-char ids for 0107/010A/010E/0123/012F/0143/0144/
        // 0145/015D). StandardPidCatalog is keyed by hexPid so lookup() is unambiguous, and the
        // defaults list is what the scheduler polls - deleting either copy breaks a dashboard
        // tile. Only PID A6 is defined once and referenced from both, via ProvenChannels.
    }

    @Test
    fun reconcilingTheWholeCatalogueIsIdempotent() {
        val all = StandardPidCatalog.getAllKnownPids()
        for (def in all) {
            val once = PidDefinitionReconciler.reconcile(def)
            val twice = PidDefinitionReconciler.reconcile(once)
            assertEquals("PID ${def.hexPid} changes on the second pass", once, twice)
        }
    }
}
