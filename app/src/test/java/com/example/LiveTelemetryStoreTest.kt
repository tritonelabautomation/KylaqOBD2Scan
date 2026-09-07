package com.example

import com.example.model.LiveTelemetryValue
import com.example.model.ValueSource
import com.example.scheduler.LiveTelemetryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LiveTelemetryStore].
 *
 * These lock in the fix for the "dead dashboard" regression: live values were published
 * only under composite `"ECU_PID"` keys (`"7E8_010C"`) while every consumer — the
 * telemetry dashboard, driving dashboard, AI doctor, PID detail screen, Android Auto
 * screen and `ObdScheduler.onTelemetrySignalUpdated()` — reads plain PID keys (`"010C"`).
 *
 * They also lock in the multi-ECU behaviour the Škoda Kylaq reference trace demands:
 * 7E8 *and* 7E9 answer the same request with slightly different numbers, so the store must
 * keep both samples yet always display one deterministic primary value.
 *
 * Pure JVM — no Robolectric, no Android runtime, no coroutine dispatcher.
 */
class LiveTelemetryStoreTest {

    private companion object {
        const val RPM = "010C"
        const val COOLANT = "0105"
        /** Engine ECU: answers every powertrain PID and is the preferred source. */
        const val ENGINE = "7E8"
        /** Secondary ECU (gateway/gearbox) that also answers most PIDs on this vehicle. */
        const val SECONDARY = "7E9"
    }

    private lateinit var store: LiveTelemetryStore

    @Before
    fun setUp() {
        store = LiveTelemetryStore()
    }

    // ------------------------------------------------------------------ helpers

    private fun sample(
        pidId: String,
        ecuId: String?,
        display: String,
        unit: String,
        numeric: Double?,
        valid: Boolean = true,
        stale: Boolean = false,
        timestamp: Long = 1_000L
    ) = LiveTelemetryValue(
        parameterName = pidId,
        numericValue = numeric,
        displayValue = display,
        unit = unit,
        source = ValueSource.STANDARD_OBD,
        timestampMonotonic = timestamp,
        isValid = valid,
        isStale = stale,
        sourcePid = pidId,
        sourceEcuId = ecuId,
        rawBytes = null
    )

    private fun publish(
        pidId: String,
        ecuId: String?,
        display: String,
        unit: String,
        numeric: Double?,
        preferredEcu: String? = ENGINE,
        timestamp: Long = 1_000L
    ) {
        store.publish(
            pidId = pidId,
            ecuId = ecuId,
            item = sample(pidId, ecuId, display, unit, numeric, timestamp = timestamp),
            preferredEcu = preferredEcu
        )
    }

    /**
     * Mirrors what `ObdScheduler` publishes on timeout / NO DATA: an invalid sample whose
     * `unit` is still the PID's unit (which is why the display must not be string-formatted
     * as "Not available RPM").
     */
    private fun publishTimeout(
        pidId: String,
        ecuId: String?,
        unit: String = "RPM",
        preferredEcu: String? = ENGINE,
        timestamp: Long = 2_000L
    ) {
        store.publishUnavailable(
            pidId = pidId,
            ecuId = ecuId,
            item = sample(
                pidId = pidId,
                ecuId = ecuId,
                display = "Not available",
                unit = unit,
                numeric = null,
                valid = false,
                timestamp = timestamp
            ),
            preferredEcu = preferredEcu
        )
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun testPlainPidKeyIsPublishedForEverySample() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)

        assertEquals("970 RPM", store.decodedMap.value[RPM])
        assertEquals(970.0, store.numericMap.value[RPM]!!, 0.0001)
        assertEquals(970.0, store.numericValue(RPM)!!, 0.0001)
        assertEquals("970 RPM", store.displayValue(RPM))
    }

    @Test
    fun testPreferredEcuWinsWhenItAnswersFirst() {
        // Reference trace 19:33:16.009 — 7E8: 970 rpm, 7E9: 973 rpm
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publish(RPM, SECONDARY, "973", "RPM", 973.0)

        assertEquals(970.0, store.numericValue(RPM)!!, 0.0001)
        assertEquals("970 RPM", store.decodedMap.value[RPM])
    }

    @Test
    fun testPreferredEcuWinsWhenItAnswersLast() {
        // Reference trace 19:33:30.463 — 7E9 arrives *before* 7E8 here.
        publish(RPM, SECONDARY, "973", "RPM", 973.0)
        publish(RPM, ENGINE, "970", "RPM", 970.0)

        assertEquals("arrival order must not change the displayed value",
            970.0, store.numericValue(RPM)!!, 0.0001)
        assertEquals("970 RPM", store.decodedMap.value[RPM])
    }

    @Test
    fun testSecondaryEcuNeverOverwritesPrimaryOnRepeatedPolls() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publish(RPM, SECONDARY, "973", "RPM", 973.0)
        publish(RPM, SECONDARY, "975", "RPM", 975.0)
        publish(RPM, SECONDARY, "971", "RPM", 971.0)

        assertEquals("dashboard must not jitter between the two ECUs",
            970.0, store.numericValue(RPM)!!, 0.0001)
        // ... but the secondary samples are still retained for diagnostics.
        assertEquals(971.0, store.telemetryMap.value["${SECONDARY}_$RPM"]!!.numericValue!!, 0.0001)
    }

    @Test
    fun testTimeoutOnPreferredEcuKeepsHealthySecondaryAlive() {
        publish(COOLANT, ENGINE, "70", "°C", 70.0)
        publish(COOLANT, SECONDARY, "70", "°C", 70.0)

        publishTimeout(COOLANT, ENGINE, unit = "°C")

        assertEquals("one timeout must not blank a PID the secondary ECU still answers",
            70.0, store.numericValue(COOLANT)!!, 0.0001)
        assertEquals("70 °C", store.decodedMap.value[COOLANT])
    }

    @Test
    fun testTimeoutOnSecondaryEcuKeepsPreferredValue() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publish(RPM, SECONDARY, "973", "RPM", 973.0)

        publishTimeout(RPM, SECONDARY)

        assertEquals(970.0, store.numericValue(RPM)!!, 0.0001)
        assertEquals("970 RPM", store.decodedMap.value[RPM])
    }

    @Test
    fun testTimeoutOnOnlyEcuReportsUnavailableWithoutUnitSuffix() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publishTimeout(RPM, ENGINE)

        assertEquals("Not available", store.decodedMap.value[RPM])
        assertFalse("placeholder must not be formatted as 'Not available RPM'",
            store.decodedMap.value[RPM]!!.contains("RPM"))
        assertNull("a failed sample must not leave a numeric value behind", store.numericMap.value[RPM])
    }

    @Test
    fun testStalePreferredEcuYieldsToFreshSecondary() {
        publish(RPM, ENGINE, "970", "RPM", 970.0, timestamp = 1_000L)
        publish(RPM, SECONDARY, "973", "RPM", 973.0, timestamp = 5_000L)

        val changed = store.markStale(
            nowMonotonic = 6_000L,
            thresholdFor = { 2_000L },
            preferredEcuFor = { ENGINE }
        )

        assertTrue("markStale should report that something changed", changed)
        assertTrue(store.telemetryMap.value["${ENGINE}_$RPM"]!!.isStale)
        assertFalse(store.telemetryMap.value["${SECONDARY}_$RPM"]!!.isStale)
        assertEquals("failover to the still-answering ECU", 973.0, store.numericValue(RPM)!!, 0.0001)
        assertEquals("973 RPM", store.decodedMap.value[RPM])
    }

    @Test
    fun testAllSamplesStaleShowsStalePlaceholderAndDropsNumeric() {
        publish(RPM, ENGINE, "970", "RPM", 970.0, timestamp = 1_000L)

        store.markStale(nowMonotonic = 60_000L, thresholdFor = { 2_000L }, preferredEcuFor = { ENGINE })

        assertEquals(LiveTelemetryStore.STALE_DISPLAY, store.decodedMap.value[RPM])
        assertNull(store.numericMap.value[RPM])
        assertNull(store.numericValue(RPM))
    }

    @Test
    fun testMarkStaleIsIdempotent() {
        publish(RPM, ENGINE, "970", "RPM", 970.0, timestamp = 1_000L)

        assertTrue(store.markStale(60_000L, { 2_000L }, { ENGINE }))
        assertFalse("a second pass must not report changes", store.markStale(60_000L, { 2_000L }, { ENGINE }))
    }

    @Test
    fun testMarkStaleKeepsFreshSamplesUntouched() {
        publish(RPM, ENGINE, "970", "RPM", 970.0, timestamp = 5_500L)

        assertFalse(store.markStale(6_000L, { 2_000L }, { ENGINE }))
        assertFalse(store.telemetryMap.value["${ENGINE}_$RPM"]!!.isStale)
        assertEquals(970.0, store.numericValue(RPM)!!, 0.0001)
    }

    @Test
    fun testEcuAwareAndPlainViewsStayInSync() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publish(RPM, SECONDARY, "973", "RPM", 973.0)
        publish(COOLANT, ENGINE, "70", "°C", 70.0)

        val ecuKeys = store.telemetryMap.value.keys
        assertTrue(ecuKeys.containsAll(listOf("${ENGINE}_$RPM", "${SECONDARY}_$RPM", "${ENGINE}_$COOLANT")))
        assertEquals("ECU-aware view keeps one entry per responding ECU", 3, ecuKeys.size)

        assertEquals("plain view keeps one entry per PID", 2, store.activePidCount)
        assertEquals(setOf(RPM, COOLANT), store.decodedMap.value.keys)
        assertEquals(listOf(ENGINE, SECONDARY), store.ecusFor(RPM))
        assertEquals(listOf(ENGINE), store.ecusFor(COOLANT))
    }

    @Test
    fun testFramesWithoutCanHeaderUseTheDefaultBucket() {
        // ELM327 with ATH0 delivers no CAN id at all.
        publish(RPM, null, "970", "RPM", 970.0)

        assertEquals(listOf(LiveTelemetryStore.NO_ECU), store.ecusFor(RPM))
        assertNotNull(store.telemetryMap.value["${LiveTelemetryStore.NO_ECU}_$RPM"])
        assertEquals(970.0, store.numericValue(RPM)!!, 0.0001)
    }

    @Test
    fun testEngineEcuOutranksOtherEcusWithoutDiscoveryEvidence() {
        publish(RPM, SECONDARY, "973", "RPM", 973.0, preferredEcu = null)
        publish(RPM, ENGINE, "970", "RPM", 970.0, preferredEcu = null)

        assertEquals(970.0, store.numericValue(RPM, preferredEcu = null)!!, 0.0001)
    }

    @Test
    fun testUnknownEcusAreResolvedAlphabeticallyForDeterminism() {
        publish(RPM, "7EB", "975", "RPM", 975.0, preferredEcu = null)
        publish(RPM, "7EA", "974", "RPM", 974.0, preferredEcu = null)
        // Re-publish in the opposite order: the primary must not change.
        publish(RPM, "7EA", "974", "RPM", 974.0, preferredEcu = null)
        publish(RPM, "7EB", "975", "RPM", 975.0, preferredEcu = null)

        assertEquals("7EA is alphabetically first and therefore the deterministic primary",
            974.0, store.numericValue(RPM, preferredEcu = null)!!, 0.0001)
    }

    @Test
    fun testEcuIdIsNormalisedToUpperCase() {
        publish(RPM, "7e8", "970", "RPM", 970.0)

        assertEquals(listOf(ENGINE), store.ecusFor(RPM))
        assertNotNull(store.telemetryMap.value["${ENGINE}_$RPM"])
    }

    @Test
    fun testPrimaryAccessorReturnsTheWinningSample() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publish(RPM, SECONDARY, "973", "RPM", 973.0)

        val primary = store.primary(RPM, ENGINE)
        assertNotNull(primary)
        assertEquals(ENGINE, primary!!.sourceEcuId)
        assertEquals("Engine RPM sample must be attributed to its PID", RPM, primary.sourcePid)
    }

    @Test
    fun testUnknownPidHasNoViews() {
        assertNull(store.numericValue("0199"))
        assertNull(store.displayValue("0199"))
        assertNull(store.primary("0199"))
        assertTrue(store.ecusFor("0199").isEmpty())
        assertEquals(0, store.activePidCount)
    }

    @Test
    fun testClearResetsEveryView() {
        publish(RPM, ENGINE, "970", "RPM", 970.0)
        publish(RPM, SECONDARY, "973", "RPM", 973.0)

        store.clear()

        assertTrue(store.telemetryMap.value.isEmpty())
        assertTrue(store.decodedMap.value.isEmpty())
        assertTrue(store.numericMap.value.isEmpty())
        assertEquals(0, store.activePidCount)
        assertNull(store.primary(RPM))
    }
}
