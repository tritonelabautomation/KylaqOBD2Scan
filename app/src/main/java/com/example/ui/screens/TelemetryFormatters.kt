package com.example.ui.screens

fun formatLiveValue(map: Map<String, String>, pid: String, defaultUnit: String = ""): String {
    val value = map[pid] ?: return "Not available"
    
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    if (errorStates.contains(value.uppercase()) || value.startsWith("Not available")) {
        return "Not available"
    }
    
    return value
}

/**
 * Numeric view first; fall back to the leading number of the display string so a
 * stale-but-valid sample ("25 °C (stale)") still feeds derived cards (AC card OUT,
 * delta) instead of flipping to "--" while the Temperatures row keeps showing the
 * value - the cross-card inconsistency the owner reported for AC-related PIDs.
 * Pure JVM - unit tested.
 */
fun numericWithStaleFallback(numeric: Double?, decoded: String?): Double? =
    numeric ?: decoded?.trim()?.substringBefore(' ')?.toDoubleOrNull()

fun isLiveError(map: Map<String, String>, pid: String): Boolean {
    val value = map[pid] ?: return false
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    return errorStates.contains(value.uppercase()) || value.startsWith("Not available")
}
