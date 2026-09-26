package com.example

import com.example.data.WelcomeSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner 2026-09-16: "Welcome voice is not good give me option to choose" -
 * voice catalogue rules: saved choice only wins when still installed.
 */
class WelcomeVoicePickerTest {

    @Test
    fun labelShowsLocaleAndEngineVoiceName() {
        assertEquals("en-US · en-us-x-ics-network", WelcomeSpeaker.voiceLabel("en-US", "en-us-x-ics-network"))
    }

    @Test
    fun savedVoiceWinsWhenInstalled() {
        assertEquals(
            "en-gb-x-gbs-local",
            WelcomeSpeaker.pickVoiceId(listOf("en-us-x-ics-network", "en-gb-x-gbs-local"), "en-gb-x-gbs-local")
        )
    }

    @Test
    fun uninstalledSavedVoiceFallsBackToDefault() {
        assertNull(WelcomeSpeaker.pickVoiceId(listOf("en-us-x-ics-network"), "hi-in-x-hie-local"))
        assertNull(WelcomeSpeaker.pickVoiceId(listOf("en-us-x-ics-network"), null))
    }
}
