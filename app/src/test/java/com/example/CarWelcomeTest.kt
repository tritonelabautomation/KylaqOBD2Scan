package com.example

import com.example.data.WelcomeSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Car Welcome voice (owner 2026-09-16, MacroDroid-style recipe made native):
 * greeting rules - interval guard, {car} placeholder, volume mapping.
 */
class CarWelcomeTest {

    @Test
    fun firstConnectAlwaysGreets() {
        assertTrue(WelcomeSpeaker.shouldSpeak(lastSpokeMs = 0L, nowMs = 10_000L))
    }

    @Test
    fun reconnectWithinFiveMinutesStaysSilent() {
        assertFalse(WelcomeSpeaker.shouldSpeak(lastSpokeMs = 1_000_000L, nowMs = 1_000_000L + 4 * 60_000L))
    }

    @Test
    fun newSessionAfterFiveMinutesGreetsAgain() {
        assertTrue(WelcomeSpeaker.shouldSpeak(lastSpokeMs = 1_000_000L, nowMs = 1_000_000L + 6 * 60_000L))
    }

    @Test
    fun carPlaceholderSaysTheVehicleName() {
        assertEquals(
            "Welcome back to your Kylaq! Drive safe.",
            WelcomeSpeaker.resolveMessage("Welcome back to your {car}! Drive safe.", "Kylaq")
        )
    }

    @Test
    fun blankMessageFallsBackToDefaultGreeting() {
        assertEquals(WelcomeSpeaker.DEFAULT_MESSAGE, WelcomeSpeaker.resolveMessage("   ", "Kylaq"))
        assertTrue(WelcomeSpeaker.DEFAULT_MESSAGE.contains("seatbelt"))
    }

    @Test
    fun volumePercentMapsOntoStreamRange() {
        assertEquals(0, WelcomeSpeaker.volumeLevel(0, 15))
        assertEquals(15, WelcomeSpeaker.volumeLevel(100, 15))
        assertEquals(10, WelcomeSpeaker.volumeLevel(70, 15))   // 15*70/100 = 10.5 -> 10
        assertEquals(15, WelcomeSpeaker.volumeLevel(140, 15))  // clamped
    }
}
