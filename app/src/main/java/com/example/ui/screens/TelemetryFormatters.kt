package com.example.ui.screens

fun formatLiveValue(map: Map<String, String>, pid: String, defaultUnit: String = ""): String {
    val value = map[pid] ?: return "Not available"
    
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    if (errorStates.contains(value.uppercase()) || value.startsWith("Not available")) {
        return "Not available"
    }
    
    return value
}

fun isLiveError(map: Map<String, String>, pid: String): Boolean {
    val value = map[pid] ?: return false
    val errorStates = setOf("UNSUPPORTED", "TIMEOUT", "ERROR", "NO_DATA", "NO_RESPONSE", "NOT_AVAILABLE")
    return errorStates.contains(value.uppercase()) || value.startsWith("Not available")
}
