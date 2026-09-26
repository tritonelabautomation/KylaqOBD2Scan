package com.example.ui.screens

fun formatLiveValue(map: Map<String, String>, pid: String, defaultUnit: String = "", fallbackPid: String? = null): String {
    val value = map[pid]
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    if (value != null && !errorStates.contains(value.uppercase()) && !value.startsWith("Not available") &&
        !value.contains("NOT SUPPORTED") && !value.contains("no data") && !value.contains("no answer") && !value.contains("CAN bus error")) {
        return value
    }
    if (fallbackPid != null) {
        val fbVal = map[fallbackPid]
        if (fbVal != null && !errorStates.contains(fbVal.uppercase()) && !fbVal.startsWith("Not available") &&
            !fbVal.contains("NOT SUPPORTED") && !fbVal.contains("no data") && !fbVal.contains("no answer") && !fbVal.contains("CAN bus error")) {
            return fbVal
        }
    }
    val raw = value ?: (fallbackPid?.let { map[it] } ?: return "Not available")
    if (errorStates.contains(raw.uppercase()) || raw.startsWith("Not available")) {
        return "Not available"
    }
    return raw
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

fun isLiveError(map: Map<String, String>, pid: String, fallbackPid: String? = null): Boolean {
    val value = map[pid]
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    val isPrimaryError = value == null || errorStates.contains(value.uppercase()) || value.startsWith("Not available") ||
        value.contains("NOT SUPPORTED") || value.contains("no data") || value.contains("no answer") || value.contains("CAN bus error")

    if (!isPrimaryError) return false
    if (fallbackPid != null) {
        val fbVal = map[fallbackPid]
        val isFbError = fbVal == null || errorStates.contains(fbVal.uppercase()) || fbVal.startsWith("Not available") ||
            fbVal.contains("NOT SUPPORTED") || fbVal.contains("no data") || fbVal.contains("no answer") || fbVal.contains("CAN bus error")
        return isFbError
    }
    return isPrimaryError
}
