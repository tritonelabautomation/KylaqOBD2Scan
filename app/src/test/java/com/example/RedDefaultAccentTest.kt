package com.example

import androidx.compose.ui.graphics.Color
import com.example.data.SettingsRepository
import com.example.ui.theme.CyberCyan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RED-BY-DEFAULT GUARD (owner request 2026-09-12: "yes I want red theme on MID").
 *
 * The Kylaq instrument cluster exposes no colour-theme adaptation channel (see
 * docs/reference/vag-coding-research.md), so the phone/HUD face of the app - the
 * owner's real MID substitute - must open RED SPORT out of the box:
 *
 *  1. the stored preference default is "RED" (fresh installs and installs where the
 *     accent was never touched both start red; an explicit CYBER/AMBER choice wins),
 *  2. the very first composed frame is already red - no cyan flash before
 *     MainActivity's LaunchedEffect applies the preference.
 */
class RedDefaultAccentTest {

    @Test
    fun `default accent preference is RED SPORT`() {
        assertEquals("RED", SettingsRepository.DEFAULT_ACCENT)
    }

    @Test
    fun `first composed frame accent is red - no cyan flash`() {
        assertEquals(Color(0xFFFF2D3F), CyberCyan)
    }
}
