package com.example

import com.example.data.GpsManager
import com.example.ui.screens.gpsNoticeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-09-16: "Still Altitude logs are missing very very bad". GPS failures were
 * silent - the recording bar must now say exactly why altitude is (not) logging.
 */
class GpsAltitudeNoticeTest {

    private fun notice(
        recording: Boolean = true,
        fix: Boolean = false,
        hasAlt: Boolean = false,
        status: String = GpsManager.STATUS_NO_FIX,
        alt: Double = 0.0
    ) = gpsNoticeFor(recording, fix, hasAlt, status, alt)

    @Test
    fun silentWhenNotRecording() {
        assertNull(notice(recording = false))
    }

    @Test
    fun greenWithLiveAltitude() {
        val (text, tone) = notice(fix = true, hasAlt = true, status = GpsManager.STATUS_OK, alt = 542.3)!!
        assertEquals(0, tone)
        assertTrue(text.contains("altitude logging"))
        assertTrue("shows the actual elevation: $text", text.contains("542"))
    }

    @Test
    fun permissionDeniedIsRedAndActionable() {
        val (text, tone) = notice(status = GpsManager.STATUS_PERMISSION_DENIED)!!
        assertEquals(2, tone)
        assertTrue(text.contains("PRECISE location"))
    }

    @Test
    fun providerOffIsRedAndActionable() {
        val (text, tone) = notice(status = GpsManager.STATUS_PROVIDER_OFF)!!
        assertEquals(2, tone)
        assertTrue(text.contains("enable phone Location"))
    }

    @Test
    fun waitingForFirstFixIsAmberNotSilent() {
        val (_, tone) = notice(status = GpsManager.STATUS_NO_FIX)!!
        assertEquals(1, tone)
    }

    @Test
    fun fixWithoutAltitudeIsAmber() {
        val (_, tone) = notice(fix = true, hasAlt = false, status = GpsManager.STATUS_OK)!!
        assertEquals(1, tone)
    }
}
