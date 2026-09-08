package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Automotive Telemetry Color Palette
/**
 * App-wide accent. mutableStateOf so the Settings "ACCENT" chips (CYBER / RED SPORT /
 * AMBER) restyle every screen live - owner request 2026-09-09 ("MID red theme" wish,
 * implemented in-app; car-side coding is research-only, see docs/reference/vag-coding-research.md).
 */
var CyberCyan by androidx.compose.runtime.mutableStateOf(Color(0xFF00E5FF))

fun setAccentColor(c: Color) {
    CyberCyan = c
}
val CyberCyanDark = Color(0xFF00B0FF)
val NeonEmerald = Color(0xFF00E676)
val ElectricAmber = Color(0xFFFFB300)
val WarningRed = Color(0xFFFF5252)
val ResearchPurple = Color(0xFFB388FF)

// Dark Automotive Backgrounds
val DarkCanvas = Color(0xFF0D141C)
val DarkSurface = Color(0xFF16222F)
val DarkSurfaceElevated = Color(0xFF1E2F40)
val DarkBorder = Color(0xFF26384C)

// Text tokens
val TextPrimaryDark = Color(0xFFF0F4F8)
val TextSecondaryDark = Color(0xFF90A4AE)
val TextMutedDark = Color(0xFF546E7A)

// Light Palette
val LightCanvas = Color(0xFFF4F7FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFE8EEF4)
val LightBorder = Color(0xFFCFD8DC)
val TextPrimaryLight = Color(0xFF102027)
val TextSecondaryLight = Color(0xFF455A64)
