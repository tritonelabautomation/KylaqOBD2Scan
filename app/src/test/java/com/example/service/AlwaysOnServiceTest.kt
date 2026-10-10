package com.example.service

import com.example.scheduler.ObdQuickConnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests verifying the Always-On background service configuration, Bluetooth auto-connect filters,
 * and zero-touch trip recording lifecycle.
 */
class AlwaysOnServiceTest {

    @Test
    fun obdAdapterNameMatchingIdentifiesCommonAdapters() {
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("OBDII"))
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("vLinker MC+"))
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("vgate iCar Pro"))
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("OBDLink MX+"))
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("ELM327 Bluetooth"))
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("Carista OBD"))
        assertTrue(ObdQuickConnect.looksLikeObdAdapter("Konnwei KW902"))

        // Must reject non-OBD Bluetooth accessories
        assertFalse(ObdQuickConnect.looksLikeObdAdapter("Galaxy Buds Pro"))
        assertFalse(ObdQuickConnect.looksLikeObdAdapter("Sony WH-1000XM4"))
        assertFalse(ObdQuickConnect.looksLikeObdAdapter("Apple Watch"))
        assertFalse(ObdQuickConnect.looksLikeObdAdapter(null))
    }

    @Test
    fun manifestDeclaresBluetoothReceiverWithRequiredActions() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .firstNotNullOfOrNull { path -> File(path).takeIf { it.exists() } }
            ?.readText()
            ?: error("AndroidManifest.xml not found")

        assertTrue(manifest.contains("BluetoothStateReceiver"))
        assertTrue(manifest.contains("android.bluetooth.device.action.ACL_CONNECTED"))
        assertTrue(manifest.contains("android.bluetooth.device.action.ACL_DISCONNECTED"))
    }

    @Test
    fun autoRecordPolicyStartsRecordingWhenEngineTurnsOn() {
        // Engine starts running at 900 rpm idle
        val decision = AutoRecordPolicy.decide(
            rpm = 900.0,
            isRecording = false,
            isPolling = true,
            autoRecordEnabled = true,
            engineOffSinceMs = 0L,
            nowMs = 10_000L
        )
        assertEquals(AutoRecordPolicy.Decision.START_RECORDING, decision)
    }

    @Test
    fun autoRecordPolicyStopsRecordingWhenEngineTurnsOffAfterGrace() {
        // Engine stopped (rpm == null) past 60s grace
        val decision = AutoRecordPolicy.decide(
            rpm = null,
            isRecording = true,
            isPolling = false,
            autoRecordEnabled = true,
            engineOffSinceMs = 10_000L,
            nowMs = 80_000L, // 70s later > 60s grace
            sessionAgeMs = 120_000L
        )
        assertEquals(AutoRecordPolicy.Decision.STOP_RECORDING, decision)
    }

    @Test
    fun autoRecordPolicyRespectsStartStopAtTrafficLights() {
        // Engine at 0 RPM during red light (fresh reading from ECU)
        val decision = AutoRecordPolicy.decide(
            rpm = 0.0,
            isRecording = true,
            isPolling = true,
            autoRecordEnabled = true,
            engineOffSinceMs = 10_000L,
            nowMs = 70_000L, // 60s later, within 300s start-stop grace
            sessionAgeMs = 120_000L
        )
        assertEquals(AutoRecordPolicy.Decision.NONE, decision)
    }
}
