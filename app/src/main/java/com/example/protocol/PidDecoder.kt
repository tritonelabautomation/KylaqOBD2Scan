package com.example.protocol

import com.example.model.BytePositionStats
import com.example.model.DecoderType
import com.example.model.PidDefinition
import java.util.Locale

/**
 * Result of decoding an OBD response payload
 */
data class DecodedResult(
    val parameterName: String,
    val numericValue: Double?,
    val displayValue: String,
    val unit: String,
    val rawPayloadHex: String,
    val dataBytes: List<Int>,
    val isKnown: Boolean
)

/**
 * High precision OBD-II parameter decoder and reverse-engineering analytical engine
 */
object PidDecoder {

    /**
     * Decodes an assembled OBD response payload according to PID definition.
     * Raw payload typically starts with positive response service (e.g. 0x41 for service 01) and PID byte (e.g. 0x0C).
     */
    fun decode(pidDef: PidDefinition, payloadBytes: List<Int>): DecodedResult {
        val rawHex = payloadBytes.joinToString("") { "%02X".format(it) }

        if (payloadBytes.isEmpty()) {
            return DecodedResult(
                parameterName = pidDef.name,
                numericValue = null,
                displayValue = "NO DATA",
                unit = pidDef.unit,
                rawPayloadHex = rawHex,
                dataBytes = emptyList(),
                isKnown = false
            )
        }

        // Standard OBD response check: First byte is (service + 0x40), second byte is PID
        val expectedServiceAck = (pidDef.service.toIntOrNull(16) ?: 1) + 0x40
        val expectedPid = pidDef.pid.toIntOrNull(16) ?: 0

        val dataBytes: List<Int>
        if (payloadBytes.size >= 2 && payloadBytes[0] == expectedServiceAck && payloadBytes[1] == expectedPid) {
            // Standard format: [41, PID, A, B, C, D, ...]
            dataBytes = payloadBytes.drop(2)
        } else if (payloadBytes.size >= 1 && payloadBytes[0] == expectedServiceAck) {
            dataBytes = payloadBytes.drop(1)
        } else {
            // Direct data bytes
            dataBytes = payloadBytes
        }

        val a = dataBytes.getOrNull(0) ?: 0
        val b = dataBytes.getOrNull(1) ?: 0
        val c = dataBytes.getOrNull(2) ?: 0
        val d = dataBytes.getOrNull(3) ?: 0

        // Handle Research PIDs without inventing physical formulas
        if (pidDef.isResearch || pidDef.decoderType == DecoderType.RESEARCH_RAW) {
            val formattedBytes = dataBytes.joinToString(" ") { "%02X".format(it) }
            return DecodedResult(
                parameterName = pidDef.name,
                numericValue = null,
                displayValue = if (formattedBytes.isNotEmpty()) formattedBytes else rawHex,
                unit = "RAW",
                rawPayloadHex = rawHex,
                dataBytes = dataBytes,
                isKnown = false
            )
        }

        if (dataBytes.isEmpty()) {
            return DecodedResult(
                parameterName = pidDef.name,
                numericValue = null,
                displayValue = "UNKNOWN",
                unit = pidDef.unit,
                rawPayloadHex = rawHex,
                dataBytes = emptyList(),
                isKnown = false
            )
        }

        return when (pidDef.decoderType) {
            DecoderType.PERCENT_255 -> {
                val value = a * 100.0 / 255.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.1f", value),
                    unit = "%",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.TEMP_MINUS_40 -> {
                val value = (a - 40).toDouble()
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.0f", value),
                    unit = "°C",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.FUEL_TRIM -> {
                val value = (a - 128) * 100.0 / 128.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%+.1f", value),
                    unit = "%",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.RAW_A_KPA -> {
                val value = a.toDouble()
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.0f", value),
                    unit = "kPa",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.RPM_FORMULA -> {
                val value = ((a * 256.0) + b) / 4.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.0f", value),
                    unit = "RPM",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.RAW_A_KMH -> {
                val value = a.toDouble()
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.0f", value),
                    unit = "km/h",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.TIMING_ADVANCE -> {
                val value = (a / 2.0) - 64.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%+.1f", value),
                    unit = "°",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.PERCENT_EVAP -> {
                val value = a * 100.0 / 255.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.1f", value),
                    unit = "%",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.VOLTAGE_1000 -> {
                val value = ((a * 256.0) + b) / 1000.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.2f", value),
                    unit = "V",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.PERCENT_LOAD_255 -> {
                val value = ((a * 256.0) + b) / 2.55
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.1f", value),
                    unit = "%",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.EQUIVALENCE_RATIO -> {
                val value = ((a * 256.0) + b) / 32768.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.3f", value),
                    unit = "λ",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.CATALYST_TEMP -> {
                val value = (((a * 256.0) + b) / 10.0) - 40.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.1f", value),
                    unit = "°C",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.FUEL_TYPE_ENUM -> {
                val fuelTypeStr = when (a) {
                    1 -> "Gasoline"
                    2 -> "Methanol"
                    3 -> "Ethanol"
                    4 -> "Diesel"
                    5 -> "LPG"
                    6 -> "CNG"
                    7 -> "Propane"
                    8 -> "Electric"
                    9 -> "Bifuel (Gasoline)"
                    10 -> "Bifuel (Methanol)"
                    11 -> "Bifuel (Ethanol)"
                    12 -> "Bifuel (LPG)"
                    13 -> "Bifuel (CNG)"
                    14 -> "Bifuel (Propane)"
                    15 -> "Bifuel (Battery)"
                    16 -> "Bifuel (Gasoline/Battery)"
                    17 -> "Hybrid Gasoline"
                    18 -> "Hybrid Ethanol"
                    19 -> "Hybrid Diesel"
                    20 -> "Hybrid Electric"
                    else -> "Type 0x%02X".format(a)
                }
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = a.toDouble(),
                    displayValue = fuelTypeStr,
                    unit = "",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.FUEL_SYSTEM_STATUS -> {
                val statusStr = when (a) {
                    0 -> "Motor off"
                    1 -> "Open loop (insufficient temp)"
                    2 -> "Closed loop (using O2 sensor)"
                    4 -> "Open loop (load or decel)"
                    8 -> "Open loop (system failure)"
                    16 -> "Closed loop (feedback fault)"
                    else -> "Status 0x%02X".format(a)
                }
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = a.toDouble(),
                    displayValue = statusStr,
                    unit = "",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.FUEL_RAIL_PRESSURE -> {
                val value = ((a * 256.0) + b) * 10.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.0f", value),
                    unit = "kPa",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.TORQUE_PCT -> {
                val value = (a - 125).toDouble()
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%+.0f", value),
                    unit = "%",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.TORQUE_NM -> {
                val value = (a * 256.0) + b
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.0f", value),
                    unit = "Nm",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.FUEL_RATE_20 -> {
                val value = ((a * 256.0) + b) / 20.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.2f", value),
                    unit = "L/h",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.CUSTOM_EXPRESSION, DecoderType.RESEARCH_RAW -> {
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = null,
                    displayValue = dataBytes.joinToString(" ") { "%02X".format(it) },
                    unit = pidDef.unit,
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = false
                )
            }
        }
    }

    /**
     * Performs reverse-engineering byte-level variance and frequency analysis on historical raw payloads
     */
    fun analyzeBytePositions(payloadHistory: List<List<Int>>): List<BytePositionStats> {
        if (payloadHistory.isEmpty()) return emptyList()

        val maxLen = payloadHistory.maxOfOrNull { it.size } ?: 0
        val result = mutableListOf<BytePositionStats>()

        for (byteIdx in 0 until maxLen) {
            val byteValues = payloadHistory.mapNotNull { it.getOrNull(byteIdx) }
            if (byteValues.isEmpty()) continue

            val minVal = byteValues.minOrNull() ?: 0
            val maxVal = byteValues.maxOrNull() ?: 0
            val uniqueCount = byteValues.distinct().size
            var changeCount = 0
            for (i in 1 until byteValues.size) {
                if (byteValues[i] != byteValues[i - 1]) {
                    changeCount++
                }
            }

            val frequencies = byteValues.groupingBy { it }.eachCount()
            val topCommonHex = frequencies.entries
                .sortedByDescending { it.value }
                .take(4)
                .map { "0x%02X (%d)".format(it.key, it.value) }

            result.add(
                BytePositionStats(
                    byteIndex = byteIdx,
                    minVal = minVal,
                    maxVal = maxVal,
                    uniqueCount = uniqueCount,
                    changeCount = changeCount,
                    sampleCount = byteValues.size,
                    lastValue = byteValues.last(),
                    commonHexValues = topCommonHex
                )
            )
        }

        return result
    }

    /**
     * Computes adjacent 16-bit word statistics for reverse engineering (e.g. Byte0+Byte1, Byte2+Byte3)
     */
    fun analyze16BitWords(payloadHistory: List<List<Int>>): List<String> {
        if (payloadHistory.isEmpty()) return emptyList()
        val maxLen = payloadHistory.maxOfOrNull { it.size } ?: 0
        val results = mutableListOf<String>()

        for (i in 0 until maxLen - 1 step 2) {
            val wordValues = payloadHistory.mapNotNull {
                if (it.size > i + 1) ((it[i] and 0xFF) shl 8) or (it[i + 1] and 0xFF) else null
            }
            if (wordValues.isNotEmpty()) {
                val minW = wordValues.minOrNull() ?: 0
                val maxW = wordValues.maxOrNull() ?: 0
                val lastW = wordValues.last()
                val avgW = wordValues.average()
                results.add("Word B$i-B${i + 1}: Last=$lastW (0x%04X) | Min=$minW | Max=$maxW | Avg=%.1f".format(lastW, avgW))
            }
        }
        return results
    }
}
