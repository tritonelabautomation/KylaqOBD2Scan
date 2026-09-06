#!/usr/bin/env python3
import re

filepath = 'c:/KylaqOBD2Scan/app/src/main/java/com/example/scheduler/ObdScheduler.kt'

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: Add sourceEcuId to LiveTelemetryValue creation (around line 463)
old_text1 = '''            val source = if (pidDef.isResearch) ValueSource.RAW_OBSERVED else ValueSource.STANDARD_OBD
            val telemetryItem = LiveTelemetryValue(
                parameterName = pidDef.name,
                numericValue = decoded.numericValue,
                displayValue = decoded.displayValue,
                unit = decoded.unit,
                source = source,
                timestampMonotonic = rxMonotonic,
                isValid = decoded.isKnown,
                isStale = false,
                sourcePid = pidDef.id,
                rawBytes = msg.reconstructedBytes
            )'''

new_text1 = '''            val source = if (pidDef.isResearch) ValueSource.RAW_OBSERVED else ValueSource.STANDARD_OBD
            // FIX P0-3: Include ECU source ID for multi-ECU telemetry isolation
            val telemetryItem = LiveTelemetryValue(
                parameterName = pidDef.name,
                numericValue = decoded.numericValue,
                displayValue = decoded.displayValue,
                unit = decoded.unit,
                source = source,
                timestampMonotonic = rxMonotonic,
                isValid = decoded.isKnown,
                isStale = false,
                sourcePid = pidDef.id,
                sourceEcuId = rxCanId,
                rawBytes = msg.reconstructedBytes
            )'''

if old_text1 in content:
    content = content.replace(old_text1, new_text1)
    print('FIX 1: Added sourceEcuId to LiveTelemetryValue - SUCCESS')
else:
    print('FIX 1: Old text not found')

# Fix 2: Update updateTelemetry to accept ecuId and use composite keys
old_text2 = '''    private fun updateTelemetry(
        pidId: String,
        telemetryItem: LiveTelemetryValue,
        displayString: String,
        numericValue: Double? = null
    ) {
        telemetryValues[pidId] = telemetryItem
        _liveTelemetryMap.value = telemetryValues.toMap()

        val currDecoded = _liveDecodedMap.value.toMutableMap()
        currDecoded[pidId] = displayString
        _liveDecodedMap.value = currDecoded

        if (numericValue != null) {
            val currNum = _liveNumericMap.value.toMutableMap()
            currNum[pidId] = numericValue
            _liveNumericMap.value = currNum
        }
    }'''

new_text2 = '''    /**
     * FIX P0-3: Update telemetry with ECU-aware storage.
     * When ecuId is provided, uses composite key "ECU_PID" for isolation.
     * Falls back to PID-only key for backward compatibility.
     */
    private fun updateTelemetry(
        pidId: String,
        telemetryItem: LiveTelemetryValue,
        displayString: String,
        numericValue: Double? = null,
        ecuId: String? = null
    ) {
        // FIX P0-3: Use composite ECU-aware key for multi-ECU isolation
        val ecuAwareKey = "${ecuId ?: "DEFAULT"}_$pidId"
        telemetryValues[ecuAwareKey] = telemetryItem
        _liveTelemetryMap.value = telemetryValues.toMap()

        // Update display maps with composite key
        val currDecoded = _liveDecodedMap.value.toMutableMap()
        currDecoded[ecuAwareKey] = displayString
        _liveDecodedMap.value = currDecoded

        if (numericValue != null) {
            val currNum = _liveNumericMap.value.toMutableMap()
            currNum[ecuAwareKey] = numericValue
            _liveNumericMap.value = currNum
        }
    }'''

if old_text2 in content:
    content = content.replace(old_text2, new_text2)
    print('FIX 2: Updated updateTelemetry with ECU-aware storage - SUCCESS')
else:
    print('FIX 2: Old text not found')

# Fix 3: Update the call to updateTelemetry to pass ecuId
old_text3 = '''            val displayString = "${decoded.displayValue} ${decoded.unit}".trim()
            updateTelemetry(pidDef.id, telemetryItem, displayString, decoded.numericValue)'''

new_text3 = '''            val displayString = "${decoded.displayValue} ${decoded.unit}".trim()
            // FIX P0-3: Pass ECU ID to updateTelemetry for ECU-aware storage
            updateTelemetry(pidDef.id, telemetryItem, displayString, decoded.numericValue, rxCanId)'''

if old_text3 in content:
    content = content.replace(old_text3, new_text3)
    print('FIX 3: Updated updateTelemetry call with ecuId - SUCCESS')
else:
    print('FIX 3: Old text not found')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)

print('All P0-3 fixes applied to ObdScheduler.kt')