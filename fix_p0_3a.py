#!/usr/bin/env python3

filepath = 'c:/KylaqOBD2Scan/app/src/main/java/com/example/model/TelemetryValue.kt'

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_text = '''/**
 * High-fidelity live telemetry value with trust source and staleness tracking
 */
data class LiveTelemetryValue(
    val parameterName: String,
    val numericValue: Double? = null,
    val displayValue: String = "—",
    val unit: String = "",
    val source: ValueSource = ValueSource.UNKNOWN,
    val timestampMonotonic: Long = 0L,
    val isValid: Boolean = false,
    val isStale: Boolean = false,
    val sourcePid: String? = null,
    val rawBytes: List<Int>? = null
)'''

new_text = '''/**
 * High-fidelity live telemetry value with trust source and staleness tracking
 *
 * FIX P0-3: Added sourceEcuId to enable per-ECU telemetry isolation.
 * When multiple ECUs respond to the same PID (e.g., 7E8 and 7E9 both responding to 010C),
 * each ECU's value is tracked independently, preventing data collision.
 */
data class LiveTelemetryValue(
    val parameterName: String,
    val numericValue: Double? = null,
    val displayValue: String = "—",
    val unit: String = "",
    val source: ValueSource = ValueSource.UNKNOWN,
    val timestampMonotonic: Long = 0L,
    val isValid: Boolean = false,
    val isStale: Boolean = false,
    val sourcePid: String? = null,
    /** FIX P0-3: Source ECU CAN ID for multi-ECU telemetry isolation */
    val sourceEcuId: String? = null,
    val rawBytes: List<Int>? = null
) {
    /**
     * FIX P0-3: Composite key for ECU-aware telemetry storage.
     * Format: "ECU_PID" (e.g., "7E8_010C")
     */
    fun ecuAwareKey(): String = "${sourceEcuId ?: "UNKNOWN"}_${sourcePid ?: "UNKNOWN"}"
}'''

if old_text in content:
    content = content.replace(old_text, new_text)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
    print('SUCCESS: TelemetryValue.kt updated')
else:
    print('ERROR: Old text not found')
    print('Looking for:', repr(old_text[:100]))