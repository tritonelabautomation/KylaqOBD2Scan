package com.example

import com.example.service.KeepAlivePolicy
import com.example.service.ObdKeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep-alive notification copy (added 2026-09-09, updated 2026-10-04 for always-on service). */
class ObdKeepAliveServiceTest {

    @Test
    fun `titles differ between recording and idle-connected`() {
        assertNotEquals(
            ObdKeepAliveService.notificationTitle(true),
            ObdKeepAliveService.notificationTitle(false)
        )
    }

    @Test
    fun `recording copy mentions recording and background`() {
        assertTrue(ObdKeepAliveService.notificationTitle(true).contains("Recording"))
        assertTrue(ObdKeepAliveService.notificationText(true).contains("background"))
    }

    @Test
    fun `idle copy mentions connected socket`() {
        assertTrue(ObdKeepAliveService.notificationTitle(false).contains("OBD connected"))
        assertFalse(ObdKeepAliveService.notificationText(false).isBlank())
    }

    @Test
    fun `standby mode reflects ready for next drive`() {
        val title = ObdKeepAliveService.notificationTitle(recording = false, connected = false)
        val text = ObdKeepAliveService.notificationText(recording = false, connected = false)
        assertTrue(title.contains("Standby"))
        assertTrue(text.contains("Waiting for vehicle") || text.contains("Auto-connect"))
    }

    @Test
    fun `connected mode shows device name if provided`() {
        val titleWithDev = ObdKeepAliveService.notificationTitle(recording = false, connected = true, deviceName = "vLinker MC+")
        assertTrue(titleWithDev.contains("vLinker MC+"))
    }

    @Test
    fun `service mode enum helpers map correctly`() {
        assertEquals("Recording trip - OBD live", KeepAlivePolicy.notificationTitle(KeepAlivePolicy.ServiceMode.RECORDING))
        assertEquals("OBD connected - logging ready", KeepAlivePolicy.notificationTitle(KeepAlivePolicy.ServiceMode.CONNECTED))
        assertEquals("OBD Standby - Ready to connect", KeepAlivePolicy.notificationTitle(KeepAlivePolicy.ServiceMode.STANDBY))
    }
}
