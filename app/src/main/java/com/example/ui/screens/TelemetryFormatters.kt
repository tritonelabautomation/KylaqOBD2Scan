package com.example.ui.screens

fun formatLiveValue(map: Map<String, String>, pid: String, defaultUnit: String = ""): String {
    val value = map[pid]
    if (value == null) return "Unavailable"
    
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE")
    if (errorStates.contains(value.uppercase())) {
        return "Unavailable"
    }
    
    // Some values in map already include unit because of ObdScheduler line 237: "${decoded.displayValue} ${decoded.unit}".trim()
    return value
}

fun isLiveError(map: Map<String, String>, pid: String): Boolean {
    val value = map[pid]
    if (value == null) return false
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE")
    return errorStates.contains(value.uppercase())
}
