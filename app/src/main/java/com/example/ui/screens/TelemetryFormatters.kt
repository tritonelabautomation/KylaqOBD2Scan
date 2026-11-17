package com.example.ui.screens

fun formatLiveValue(map: Map<String, String>, pid: String, defaultUnit: String = "", fallbackPid: String? = null): String {
    val value = map[pid]
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    val isPrimaryInvalid = value == null || errorStates.contains(value.uppercase()) || value.startsWith("Not available")

    if (!isPrimaryInvalid) {
        return value!!
    }

    if (fallbackPid != null) {
        val fbVal = map[fallbackPid]
        val isFbInvalid = fbVal == null || errorStates.contains(fbVal.uppercase()) || fbVal.startsWith("Not available")
        if (!isFbInvalid) {
            return fbVal!!
        }
    }

    return value ?: (fallbackPid?.let { map[it] } ?: "Not available")
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
    if (value != null) {
        val isError = errorStates.contains(value.uppercase()) || value.startsWith("Not available")
        if (!isError) return false
        if (fallbackPid != null) {
            val fbVal = map[fallbackPid] ?: return isError
            return errorStates.contains(fbVal.uppercase()) || fbVal.startsWith("Not available")
        }
        return isError
    }
    if (fallbackPid != null) {
        val fbVal = map[fallbackPid] ?: return false
        return errorStates.contains(fbVal.uppercase()) || fbVal.startsWith("Not available")
    }
    return false
}
