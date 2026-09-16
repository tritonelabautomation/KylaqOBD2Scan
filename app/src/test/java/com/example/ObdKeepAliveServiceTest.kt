package com.example

import com.example.service.ObdKeepAliveService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep-alive notification copy (added 2026-09-09). */
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
}
