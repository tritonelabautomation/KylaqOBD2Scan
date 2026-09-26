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

        // FIX: a negative response (7F <service> <NRC>) is never telemetry — not even for
        // research PIDs. The permissive research path below used to accept it and render
        // "7F 01 11" as if it were a live raw value, which the dashboard then displayed
        // as data. Reject it before any decoding strategy runs.
        if ((payloadBytes[0] and 0xFF) == 0x7F) {
            return DecodedResult(
                parameterName = pidDef.name,
                numericValue = null,
                displayValue = "INVALID_RESPONSE",
                unit = pidDef.unit,
                rawPayloadHex = rawHex,
                dataBytes = emptyList(),
                isKnown = false
            )
        }

        // Standard OBD response check: First byte is (service + 0x40), second byte is PID
        val expectedServiceAck = (pidDef.service.toIntOrNull(16) ?: 1) + 0x40
        val expectedPid = pidDef.pid.toIntOrNull(16) ?: 0

        // FIX P0-2: For non-research PIDs we REQUIRE the payload to start with the exact
        // positive service ack and the exact requested PID. A negative response (0x7F) is
        // never a valid payload. A permissive "Direct data bytes" fallback was previously
        // allowing malformed/unexpected responses to be silently decoded as telemetry.
        val isResearch = pidDef.isResearch || pidDef.decoderType == DecoderType.RESEARCH_RAW

        val dataBytes: List<Int> = when {
            payloadBytes.size >= 2 &&
                payloadBytes[0] == expectedServiceAck &&
                payloadBytes[1] == expectedPid -> {
                // Standard format: [41, PID, A, B, C, D, ...]
                payloadBytes.drop(2)
            }
            isResearch && payloadBytes.size >= 1 && payloadBytes[0] == expectedServiceAck -> {
                // Research PIDs may legitimately have a different PID byte; only require
                // the positive service ack when the caller has marked the PID as research.
                payloadBytes.drop(1)
            }
            isResearch -> {
                // Research fallback: treat the raw payload as data bytes.
                payloadBytes
            }
            else -> {
                // Strict path: refuse to decode malformed/unexpected responses.
                return DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = null,
                    displayValue = "INVALID_RESPONSE",
                    unit = pidDef.unit,
                    rawPayloadHex = rawHex,
                    dataBytes = emptyList(),
                    isKnown = false
                )
            }
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
                // Plausibility gate (2026-09-13, owner screenshot showed -37 C coolant-2):
                // an uninitialised ECU raw must surface as NO DATA, never as a fake number.
                val computed = (a - 40).toDouble()
                val plausible = computed in -30.0..210.0
                val value = if (plausible) computed else null
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = if (plausible) String.format(Locale.US, "%.0f", computed) else "implausible raw - no data",
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
                // J1979 error indicator: 0xFFFF on ratio PIDs means NOT AVAILABLE.
                // Unguarded it decodes to a plausible-looking lambda 2.000 - a fake
                // reading (2026-09-14 sweep: "issues you forgot to test").
                val raw = (a * 256) + b
                val available = raw != 0xFFFF
                val value = if (available) raw / 32768.0 else null
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = if (available) String.format(Locale.US, "%.3f", raw / 32768.0) else "no data (J1979 0xFFFF)",
                    unit = "λ",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.CATALYST_TEMP -> {
                val computed = (((a * 256.0) + b) / 10.0) - 40.0
                val plausible = computed in -30.0..1200.0
                val value = if (plausible) computed else null
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = if (plausible) String.format(Locale.US, "%.1f", computed) else "implausible raw - no data",
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

            DecoderType.FUEL_RATE_MASS_50 -> {
                // REAL-CAR CALIBRATION 2026-09-13 (owner live telemetry, Kylaq EA211): secondary
                // spec mirrors said /10 g/s, but that yields 3.9-6.8 L/h at warm idle - physically
                // impossible. Stoichiometric speed-density cross-check (MAP 37 kPa, 978 rpm,
                // IAT 30 C, lambda 1.000 => ~0.15-0.19 g/s) matches raw counts 8-14 ONLY at
                // /50 (0.02 g/s per count, == 0.1 L/h). See docs/qa-qc-fuel-pids-dashboard F-6.
                val value = ((a * 256.0) + b) / 50.0
                // 2026-09-15 owner: "why g/s when all other units are litres?" - J1979 019D IS
                // a mass flow (g/s) and the volume PID 015E is refused by this ECU, so every
                // litre figure in the app is derived from this mass rate. Show the conversion
                // inline (745 g/L petrol density) so the row reads in the same units as the
                // rest of the dashboard instead of looking like a foreign/gallon unit.
                val lh = value * 3600.0 / com.example.engine.PowertrainModel.FUEL_DENSITY_G_PER_L
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.2f g/s ≈ %.2f L/h", value, lh),
                    // Unit travels inside displayValue now (mass + litre-equivalent); the
                    // store joins displayValue+unit, so an extra "g/s" here would read
                    // "0.20 g/s ≈ 0.97 L/h g/s". The PID-definition sublabel still shows
                    // the J1979 unit (g/s) under the row name.
                    unit = "",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.FUEL_PRESSURE_3_KPA -> {
                val value = a * 3.0
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

            DecoderType.MAF_100 -> {
                val value = ((a * 256.0) + b) / 100.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%.2f", value),
                    unit = "g/s",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.INJECTION_TIMING_128 -> {
                val value = (((a * 256.0) + b) - 26880.0) / 128.0
                DecodedResult(
                    parameterName = pidDef.name,
                    numericValue = value,
                    displayValue = String.format(Locale.US, "%+.2f", value),
                    unit = "°",
                    rawPayloadHex = rawHex,
                    dataBytes = dataBytes,
                    isKnown = true
                )
            }

            DecoderType.TRANSMISSION_GEAR_A4 -> {
                // SAE J1979 PID 01A4:
                // Byte A: Command/Status, Bytes B&C: Gear Ratio = ((B*256)+C)/1000.0
                if (dataBytes.size >= 3) {
                    val ratio = ((b * 256.0) + c) / 1000.0
                    if (ratio in 0.2..15.0) {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = ratio,
                            displayValue = String.format(Locale.US, "Ratio %.3f", ratio),
                            unit = "ratio",
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = true
                        )
                    } else {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = null,
                            displayValue = "Not available",
                            unit = "",
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = true
                        )
                    }
                } else {
                    DecodedResult(
                        parameterName = pidDef.name,
                        numericValue = null,
                        displayValue = "Not available",
                        unit = "",
                        rawPayloadHex = rawHex,
                        dataBytes = dataBytes,
                        isKnown = false
                    )
                }
            }

            // Two-byte equivalence ratio, ((A*256)+B)/32768 - the layout J1979 uses for the
            // lambda half of PID 34-3B (O2 sensor n: lambda + current) and 24-2B (lambda +
            // voltage). The owner Kylaq run 2026-09-16 answered `41 56 7F 00`; read as lambda
            // that is 0.992 at closed-loop stoichiometry, which independently agrees with PID
            // 44 (commanded equivalence ratio) = 1.000 in the same run. Two earlier revisions
            // of this code printed the bare byte 7F, then invented "0.633 V" out of byte A -
            // the frame contains no voltage field, so none is displayed any more.
            DecoderType.LAMBDA_2B -> {
                if (dataBytes.size < 2) {
                    DecodedResult(
                        parameterName = pidDef.name,
                        numericValue = null,
                        displayValue = "NO DATA",
                        unit = pidDef.unit,
                        rawPayloadHex = rawHex,
                        dataBytes = dataBytes,
                        isKnown = false
                    )
                } else {
                    val lambdaValue = ((a * 256.0) + b) / 32768.0
                    DecodedResult(
                        parameterName = pidDef.name,
                        numericValue = lambdaValue,
                        displayValue = String.format(Locale.US, "\u03BB %.3f", lambdaValue),
                        unit = pidDef.unit,
                        rawPayloadHex = rawHex,
                        dataBytes = dataBytes,
                        isKnown = true
                    )
                }
            }

            // SAE J1979 PID 55-58: secondary oxygen-sensor fuel trims, two bytes, each
            // (X - 128) * 100 / 128 percent. 55/56 are the short/long term trim of bank 1 +
            // bank 3, 57/58 of bank 2 + bank 4. The owner run answered `41 55 80 80` =
            // +0.0 % / +0.0 % (closed loop, post-cat trims at zero) and `41 56 7F 00` =
            // -0.8 % / -100.0 %, the second byte being the no-sensor sentinel of the bank
            // this 3-cylinder engine does not have.
            DecoderType.O2_TRIM_PAIR_2B -> {
                if (dataBytes.size < 2) {
                    DecodedResult(
                        parameterName = pidDef.name,
                        numericValue = null,
                        displayValue = "NO DATA",
                        unit = pidDef.unit,
                        rawPayloadHex = rawHex,
                        dataBytes = dataBytes,
                        isKnown = false
                    )
                } else {
                    val trimA = (a - 128) * 100.0 / 128.0
                    val trimB = (b - 128) * 100.0 / 128.0
                    // -100 % is the ECU saying "that sensor does not exist". Publishing it as
                    // a trim value would be a fake number on a single-bank engine.
                    if (trimA <= -99.99 || trimB <= -99.99) {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = null,
                            displayValue = "Not available",
                            unit = pidDef.unit,
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = false
                        )
                    } else {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = trimA,
                            displayValue = String.format(Locale.US, "%+.1f %% / %+.1f %%", trimA, trimB),
                            unit = pidDef.unit,
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = true
                        )
                    }
                }
            }

            // SAE J1979 PID A6: odometer, ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km. The owner run
            // recorded `41 A6 00 00 86 EB` and the app printed "Unknown Research PID 01A6".
            // 34539 / 10 = 3453.9 km - a real channel, already answered, thrown away as raw
            // hex. Sanity gate: 0 km and anything above 2 000 000 km are rejected as sentinel
            // or garbage instead of being displayed.
            DecoderType.ODOMETER_4B -> {
                if (dataBytes.size < 4) {
                    DecodedResult(
                        parameterName = pidDef.name,
                        numericValue = null,
                        displayValue = "NO DATA",
                        unit = pidDef.unit,
                        rawPayloadHex = rawHex,
                        dataBytes = dataBytes,
                        isKnown = false
                    )
                } else {
                    val raw = (a.toLong() shl 24) or (b.toLong() shl 16) or
                        (c.toLong() shl 8) or d.toLong()
                    val km = raw / 10.0
                    if (raw <= 0L || km > 2_000_000.0) {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = null,
                            displayValue = "Not available",
                            unit = pidDef.unit,
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = false
                        )
                    } else {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = km,
                            displayValue = String.format(Locale.US, "%.1f", km),
                            unit = pidDef.unit,
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = true
                        )
                    }
                }
            }

            // Steering Wheel Angle: Signed 16-bit / 10.0 (°).
            // J1979-2 Mode 01 PID B5 & UDS Service 22 DID 0200 / 02B2.
            // Two's complement: 0x0000 = 0.0°, 0x00A4 = +16.4° (Right), 0xFF5C = -16.4° (Left).
            // Valid steering wheel mechanical range: -780.0° to +780.0° (max lock-to-lock).
            // Sentinel values like 0x7FFF / 0x8000 indicate sensor uncalibrated or error.
            DecoderType.STEERING_ANGLE_SIGNED_10 -> {
                if (dataBytes.size < 2) {
                    DecodedResult(
                        parameterName = pidDef.name,
                        numericValue = null,
                        displayValue = "NO DATA",
                        unit = pidDef.unit,
                        rawPayloadHex = rawHex,
                        dataBytes = dataBytes,
                        isKnown = false
                    )
                } else {
                    val raw16 = (a shl 8) or b
                    val isSentinel = raw16 == 0x7FFF || raw16 == 0x8000 || raw16 == 0xFFFF
                    val signedVal = if (raw16 > 32767) raw16 - 65536 else raw16
                    val angleDeg = signedVal / 10.0
                    val isPlausible = !isSentinel && kotlin.math.abs(angleDeg) <= 780.0

                    if (!isPlausible) {
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = null,
                            displayValue = if (isSentinel) "Sensor uncalibrated / error" else "implausible raw - no data",
                            unit = pidDef.unit,
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = false
                        )
                    } else {
                        val dir = when {
                            angleDeg > 0.5 -> "Right"
                            angleDeg < -0.5 -> "Left"
                            else -> "Center"
                        }
                        DecodedResult(
                            parameterName = pidDef.name,
                            numericValue = angleDeg,
                            displayValue = String.format(Locale.US, "%+.1f° (%s)", angleDeg, dir),
                            unit = "°",
                            rawPayloadHex = rawHex,
                            dataBytes = dataBytes,
                            isKnown = true
                        )
                    }
                }
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
