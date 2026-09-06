#!/usr/bin/env python3

filepath = 'c:/KylaqOBD2Scan/app/src/main/java/com/example/scheduler/ObdScheduler.kt'

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix staleness supervisor to be per-ECU-PID aware
old_text = '''        // Launch periodic staleness check supervisor
        stalenessJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isPolling.value) {
                delay(1000L)
                val nowMonotonic = SystemClock.elapsedRealtime()
                var updated = false
                telemetryValues.forEach { (id, item) ->
                    val staleThresholdMs = when (id) {
                        "010C", "010D", "0111", "0149", "0162" -> 2500L // Fast items
                        "015E", "019D", "0104", "010B", "0110", "0105" -> 5000L // Medium items
                        else -> 15000L // Slow items
                    }
                    if (!item.isStale && (nowMonotonic - item.timestampMonotonic > staleThresholdMs)) {
                        telemetryValues[id] = item.copy(isStale = true)
                        updated = true
                    }
                }
                if (updated) {
                    _liveTelemetryMap.value = telemetryValues.toMap()
                }
            }
        }'''

new_text = '''        // Launch periodic staleness check supervisor
        // FIX P0-3: Per-ECU-PID staleness tracking
        // Now handles composite keys like "7E8_010C" and "7E9_010C" independently
        stalenessJob = scope.launch(Dispatchers.Default) {
            while (isActive && _isPolling.value) {
                delay(1000L)
                val nowMonotonic = SystemClock.elapsedRealtime()
                var updated = false
                telemetryValues.forEach { (id, item) ->
                    // FIX P0-3: Extract PID from composite key (e.g., "7E8_010C" -> "010C")
                    val pidId = if (id.contains("_")) id.substringAfter("_") else id
                    val staleThresholdMs = when (pidId) {
                        "010C", "010D", "0111", "0149", "0162" -> 2500L // Fast items
                        "015E", "019D", "0104", "010B", "0110", "0105" -> 5000L // Medium items
                        else -> 15000L // Slow items
                    }
                    // FIX P0-3: Each ECU's telemetry is independently checked for staleness
                    if (!item.isStale && (nowMonotonic - item.timestampMonotonic > staleThresholdMs)) {
                        telemetryValues[id] = item.copy(isStale = true)
                        updated = true
                    }
                }
                if (updated) {
                    _liveTelemetryMap.value = telemetryValues.toMap()
                }
            }
        }'''

if old_text in content:
    content = content.replace(old_text, new_text)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
    print('SUCCESS: Staleness supervisor updated with per-ECU-PID tracking')
else:
    print('ERROR: Old text not found')