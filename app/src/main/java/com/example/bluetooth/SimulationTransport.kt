package com.example.bluetooth

import android.os.SystemClock
import com.example.model.ResponseStatus
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-fidelity EA211 1.0 TSI Škoda Kylaq simulation transport for bench testing & verification.
 * Emits real 11-bit CAN 500kbps ISO-TP frames (7E8) including dynamic RPM, boost, load, temperatures,
 * and realistic 016D/0170 research payloads with dynamic byte variance.
 */
class SimulationElmTransport : ElmTransport {

    @Volatile
    private var connected: Boolean = false
    private var rawLogListener: RawLogListener? = null
    private var simTimeStep: Double = 0.0

    // Simulation state for EA211 1.0 TSI engine
    private var engineRpm: Double = 850.0
    private var speedKmh: Double = 0.0
    private var coolantTempC: Double = 88.0
    private var boostKpa: Double = 101.0
    private var fuelRailMpa: Double = 15.0

    override val isConnected: Boolean
        get() = connected

    override fun setRawLogListener(listener: RawLogListener?) {
        this.rawLogListener = listener
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.Default) {
        delay(150)
        connected = true
        logRaw(isTx = false, canId = null, text = "Simulation Mode Active [Škoda Kylaq EA211 1.0 TSI]", status = "INFO")
        true
    }

    override suspend fun disconnect() = withContext(Dispatchers.Default) {
        connected = false
        logRaw(isTx = false, canId = null, text = "Simulation Disconnected", status = "INFO")
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse = withContext(Dispatchers.Default) {
        val validation = SafetyValidator.validateCommand(command)
        if (validation is ValidationResult.Rejected) {
            logRaw(isTx = true, canId = null, text = "$command [BLOCKED]", status = "BLOCKED")
            return@withContext ElmResponse(
                rawText = "",
                lines = emptyList(),
                status = ResponseStatus.MALFORMED,
                isPromptReceived = false,
                durationMs = 0L,
                errorMessage = "SAFETY BLOCK: ${validation.reason}"
            )
        }

        val cleanCmd = command.trim().uppercase().replace(" ", "")
        val startMonotonic = SystemClock.elapsedRealtime()
        logRaw(isTx = true, canId = "7DF", text = cleanCmd, status = "TX")

        // Realistic CAN bus latency (15-30ms)
        delay(Random.nextLong(15, 35))

        // Update physics simulation step
        simTimeStep += 0.05
        val throttlePct = (sin(simTimeStep * 0.4) * 0.5 + 0.5) * 60.0 + 10.0
        engineRpm = 850.0 + (throttlePct * 55.0) + (sin(simTimeStep * 2.0) * 40.0)
        speedKmh = (throttlePct * 1.4) + (sin(simTimeStep * 0.2) * 5.0).coerceAtLeast(0.0)
        boostKpa = 98.0 + (throttlePct * 1.8) // MAP from 98 to ~220 kPa on EA211 turbo
        val loadPct = (throttlePct * 1.1).coerceIn(15.0, 95.0)
        val iatC = 32.0 + (sin(simTimeStep * 0.1) * 3.0)
        val ambientC = 28.0
        coolantTempC = 89.0 + sin(simTimeStep * 0.05) * 2.0
        fuelRailMpa = 4.0 + (throttlePct * 0.35) // High pressure direct injection rail 40 to 250 bar

        val lines = mutableListOf<String>()
        var responseStatus = ResponseStatus.OK

        when {
            cleanCmd == "ATZ" -> {
                lines.add("ELM327 v1.5")
            }
            cleanCmd == "ATE0" || cleanCmd == "ATL0" || cleanCmd == "ATS0" || cleanCmd == "ATH1" || cleanCmd.startsWith("ATSP") -> {
                lines.add("OK")
            }
            cleanCmd == "ATRV" -> {
                lines.add("14.1V")
            }
            cleanCmd == "ATDP" -> {
                lines.add("ISO 15765-4 (CAN 11/500)")
            }
            cleanCmd.startsWith("ATSH") || cleanCmd.startsWith("ATCRA") -> {
                lines.add("OK")
            }

            // Supported PIDs Bitmasks for EA211 1.0 TSI
            cleanCmd == "0100" -> {
                // PIDs 01-20: 04, 05, 06, 07, 0B, 0C, 0D, 0E, 0F, 11, etc.
                lines.add("7E8 06 41 00 BE 3F B8 13")
            }
            cleanCmd == "0120" -> {
                // PIDs 21-40: 2E, 2F, 33, 3C, etc.
                lines.add("7E8 06 41 20 80 07 20 01")
            }
            cleanCmd == "0140" -> {
                // PIDs 41-60: 42, 43, 44, 45, 46, 47, 49, 4A, 4C, 51, etc.
                lines.add("7E8 06 41 40 FE D0 80 00")
            }
            cleanCmd == "0160" -> {
                // PIDs 61-80: 62, 63, 67, 6D, 70, etc.
                lines.add("7E8 06 41 60 70 00 00 00")
            }

            // Standard Mode 01 OBD PIDs for EA211 (Single Frame with 7E8 CAN header)
            cleanCmd == "010C" -> {
                // RPM formula: ((A * 256) + B) / 4
                val rawVal = (engineRpm * 4).toInt().coerceIn(0, 65535)
                val a = (rawVal ushr 8) and 0xFF
                val b = rawVal and 0xFF
                lines.add("7E8 04 41 0C %02X %02X".format(a, b))
            }
            cleanCmd == "010D" -> {
                // Speed: A (km/h)
                val a = speedKmh.toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 0D %02X".format(a))
            }
            cleanCmd == "0104" -> {
                // Engine Load: A * 100 / 255
                val a = ((loadPct / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 04 %02X".format(a))
            }
            cleanCmd == "010B" -> {
                // MAP: A (kPa)
                val a = boostKpa.toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 0B %02X".format(a))
            }
            cleanCmd == "0111" -> {
                // Throttle Position: A * 100 / 255
                val a = ((throttlePct / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 11 %02X".format(a))
            }
            cleanCmd == "0149" -> {
                // Accelerator D
                val a = (((throttlePct + 5.0).coerceIn(0.0, 100.0) / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 49 %02X".format(a))
            }
            cleanCmd == "014A" -> {
                // Accelerator E
                val a = (((throttlePct * 0.5).coerceIn(0.0, 100.0) / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 4A %02X".format(a))
            }
            cleanCmd == "0105" -> {
                // Coolant Temp: A - 40
                val a = (coolantTempC + 40).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 05 %02X".format(a))
            }
            cleanCmd == "010F" -> {
                // IAT: A - 40
                val a = (iatC + 40).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 0F %02X".format(a))
            }
            cleanCmd == "0146" -> {
                // Ambient: A - 40
                val a = (ambientC + 40).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 46 %02X".format(a))
            }
            cleanCmd == "015E" -> {
                // Engine Fuel Rate (Volume: L/h): ((A * 256) + B) / 20.0
                val rateLh = if (throttlePct < 1.0 && engineRpm > 1200.0 && speedKmh > 20.0) {
                    0.0 // Fuel-cut deceleration
                } else if (speedKmh < 2.0) {
                    0.65 // Idle fuel consumption (L/h)
                } else {
                    0.65 + (engineRpm / 1000.0) * (loadPct / 100.0) * 1.5
                }
                val rawVal = (rateLh * 20.0).toInt().coerceIn(0, 65535)
                val a = (rawVal ushr 8) and 0xFF
                val b = rawVal and 0xFF
                lines.add("7E8 04 41 5E %02X %02X".format(a, b))
            }
            cleanCmd == "019D" -> {
                // Engine Fuel Rate (Mass: g/s): ((A * 256) + B) / 10.0
                // Gasoline density ~ 745 g/L -> g/s = (L/h * 745) / 3600.0
                val rateLh = if (throttlePct < 1.0 && engineRpm > 1200.0 && speedKmh > 20.0) {
                    0.0
                } else if (speedKmh < 2.0) {
                    0.65
                } else {
                    0.65 + (engineRpm / 1000.0) * (loadPct / 100.0) * 1.5
                }
                val rateMassGs = (rateLh * 745.0) / 3600.0
                val rawVal = (rateMassGs * 10.0).toInt().coerceIn(0, 65535)
                val a = (rawVal ushr 8) and 0xFF
                val b = rawVal and 0xFF
                lines.add("7E8 04 41 9D %02X %02X".format(a, b))
            }
            cleanCmd == "0110" -> {
                // MAF: ((A * 256) + B) / 100.0 (g/s)
                val mafVal = (engineRpm / 1000.0) * (boostKpa / 101.3) * 12.0
                val rawVal = (mafVal * 100.0).toInt().coerceIn(0, 65535)
                val a = (rawVal ushr 8) and 0xFF
                val b = rawVal and 0xFF
                lines.add("7E8 04 41 10 %02X %02X".format(a, b))
            }
            cleanCmd == "010A" -> {
                // Fuel Pressure: A * 3 kPa gauge (~350 kPa low-pressure fuel line)
                val a = (350.0 / 3.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 0A %02X".format(a))
            }
            cleanCmd == "015D" -> {
                // Fuel Injection Timing: (((A * 256) + B) - 26880) / 128.0
                val timingDeg = -5.0 + (throttlePct * 0.2)
                val rawVal = ((timingDeg * 128.0) + 26880.0).toInt().coerceIn(0, 65535)
                val a = (rawVal ushr 8) and 0xFF
                val b = rawVal and 0xFF
                lines.add("7E8 04 41 5D %02X %02X".format(a, b))
            }
            cleanCmd == "0161" -> {
                // Driver Demand Torque %: A - 125
                val demandPct = throttlePct.toInt()
                val a = (demandPct + 125).coerceIn(0, 255)
                lines.add("7E8 03 41 61 %02X".format(a))
            }
            cleanCmd == "01A4" -> {
                // Transmission Actual Gear Ratio (PID 01A4)
                // A: Status, B & C: Ratio * 1000
                val gearRatio = when {
                    speedKmh < 15.0 -> 4.04
                    speedKmh < 30.0 -> 2.37
                    speedKmh < 50.0 -> 1.56
                    speedKmh < 75.0 -> 1.16
                    speedKmh < 105.0 -> 0.85
                    else -> 0.67
                }
                val rawRatio = (gearRatio * 1000.0).toInt()
                val b = (rawRatio ushr 8) and 0xFF
                val c = rawRatio and 0xFF
                lines.add("7E8 05 41 A4 01 %02X %02X".format(b, c))
            }
            cleanCmd == "0902" -> {
                // VIN: TMBE79N15S001234 (17 chars)
                lines.add("7E8 10 14 49 02 01 54 4D 42")
                lines.add("7E8 21 45 37 39 4E 31 35 53")
                lines.add("7E8 22 30 30 31 32 33 34 00")
            }
            cleanCmd == "0904" -> {
                // Calibration ID: 04C906027A
                lines.add("7E8 10 0E 49 04 01 30 34 43")
                lines.add("7E8 21 39 30 36 30 32 37 41")
            }
            cleanCmd == "090A" -> {
                // ECU Name: EA211_1.0TSI
                lines.add("7E8 08 49 0A 01 45 41 32 31 31")
            }
            cleanCmd == "22F189" -> {
                // SW Version
                lines.add("7E8 06 62 F1 89 39 38 34")
            }
            cleanCmd == "22F187" -> {
                // Spare Part Number: 04C906027A
                lines.add("7E8 10 0E 62 F1 87 30 34 43")
                lines.add("7E8 21 39 30 36 30 32 37 41")
            }
            cleanCmd == "0162" -> {
                // Actual Torque %: A - 125
                val torquePct = (loadPct * 0.85).toInt()
                val a = (torquePct + 125).coerceIn(0, 255)
                lines.add("7E8 03 41 62 %02X".format(a))
            }
            cleanCmd == "0142" -> {
                // Voltage: ((A * 256) + B) / 1000 = V
                val rawVal = (14120 + Random.nextInt(-40, 40)).coerceIn(0, 65535)
                val a = (rawVal ushr 8) and 0xFF
                val b = rawVal and 0xFF
                lines.add("7E8 04 41 42 %02X %02X".format(a, b))
            }

            // Research PID 016D - EA211 Fuel Pressure Control (Multi-byte raw telemetry)
            cleanCmd == "016D" -> {
                // Emulate raw response: 7E8 06 41 6D [Byte0: status] [Byte1, Byte2: target pressure] [Byte3, Byte4: actual pressure]
                val targetRailKpa = (fuelRailMpa * 1000.0).toInt()
                val actualRailKpa = (targetRailKpa + Random.nextInt(-50, 50)).coerceIn(0, 65535)
                val b0 = 0x01 // closed-loop status flag
                val b1 = (targetRailKpa ushr 8) and 0xFF
                val b2 = targetRailKpa and 0xFF
                val b3 = (actualRailKpa ushr 8) and 0xFF
                val b4 = actualRailKpa and 0xFF
                lines.add("7E8 07 41 6D %02X %02X %02X %02X %02X".format(b0, b1, b2, b3, b4))
            }

            // Research PID 0170 - EA211 Boost Pressure Control (Multi-byte raw telemetry)
            cleanCmd == "0170" -> {
                // Emulate raw response: 7E8 06 41 70 [Byte0: Wastegate Duty %] [Byte1, Byte2: Target Boost hPa] [Byte3, Byte4: Charge Pressure Sensor]
                val wastegateDuty = (throttlePct * 1.2).toInt().coerceIn(10, 240)
                val targetBoost = (boostKpa * 10.0).toInt()
                val actualBoost = (targetBoost + Random.nextInt(-10, 10)).coerceIn(0, 65535)
                val b0 = wastegateDuty
                val b1 = (targetBoost ushr 8) and 0xFF
                val b2 = targetBoost and 0xFF
                val b3 = (actualBoost ushr 8) and 0xFF
                val b4 = actualBoost and 0xFF
                lines.add("7E8 07 41 70 %02X %02X %02X %02X %02X".format(b0, b1, b2, b3, b4))
            }

            // Research PID 01A6 - Unknown EA211 Channel
            cleanCmd == "01A6" -> {
                val b0 = Random.nextInt(0, 255)
                val b1 = Random.nextInt(0, 255)
                val b2 = 0x1A
                lines.add("7E8 05 41 A6 %02X %02X %02X".format(b0, b1, b2))
            }

            // Additional standard PIDs
            cleanCmd == "0106" -> {
                // STFT Bank 1: (A - 128) * 100 / 128
                val stft = (sin(simTimeStep * 0.8) * 3.0)
                val a = ((stft * 128.0 / 100.0) + 128.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 06 %02X".format(a))
            }
            cleanCmd == "0107" -> {
                // LTFT Bank 1
                val ltft = 1.5
                val a = ((ltft * 128.0 / 100.0) + 128.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 07 %02X".format(a))
            }
            cleanCmd == "010E" -> {
                // Timing advance: A / 2 - 64
                val timingDeg = 12.0 + sin(simTimeStep * 0.5) * 6.0
                val a = ((timingDeg + 64.0) * 2.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 0E %02X".format(a))
            }
            cleanCmd == "012E" -> {
                val evap = 18.0
                val a = ((evap / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 2E %02X".format(a))
            }
            cleanCmd == "012F" -> {
                val a = ((68.0 / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 2F %02X".format(a))
            }
            cleanCmd == "0133" -> {
                lines.add("7E8 03 41 33 64") // 100 kPa
            }
            cleanCmd == "013C" -> {
                // Cat temp: ((A*256)+B)/10 - 40 = 550°C
                val rawVal = ((550.0 + 40.0) * 10.0).toInt()
                lines.add("7E8 04 41 3C %02X %02X".format((rawVal ushr 8) and 0xFF, rawVal and 0xFF))
            }
            cleanCmd == "0143" -> {
                // Abs Load
                val a = ((loadPct * 2.55)).toInt().coerceIn(0, 65535)
                lines.add("7E8 04 41 43 %02X %02X".format((a ushr 8) and 0xFF, a and 0xFF))
            }
            cleanCmd == "0144" -> {
                // Lambda 1.000
                val rawVal = 32768
                lines.add("7E8 04 41 44 %02X %02X".format((rawVal ushr 8) and 0xFF, rawVal and 0xFF))
            }
            cleanCmd == "0145" -> {
                val a = ((throttlePct / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 45 %02X".format(a))
            }
            cleanCmd == "0147" -> {
                val a = ((throttlePct / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 47 %02X".format(a))
            }
            cleanCmd == "014C" -> {
                val a = ((throttlePct / 100.0) * 255.0).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 4C %02X".format(a))
            }
            cleanCmd == "0151" -> {
                lines.add("7E8 03 41 51 01") // 01 = Gasoline
            }
            cleanCmd == "0163" -> {
                // Ref Torque 178 Nm (EA211 1.0 TSI peak torque = 178 Nm)
                val rawVal = 178
                lines.add("7E8 04 41 63 %02X %02X".format((rawVal ushr 8) and 0xFF, rawVal and 0xFF))
            }
            cleanCmd == "0167" -> {
                val a = (coolantTempC - 5.0 + 40).toInt().coerceIn(0, 255)
                lines.add("7E8 03 41 67 %02X".format(a))
            }
            else -> {
                lines.add("NO DATA")
                responseStatus = ResponseStatus.NO_DATA
            }
        }

        for (line in lines) {
            logRaw(isTx = false, canId = "7E8", text = line, status = responseStatus.name)
        }

        val rawText = lines.joinToString("\r\n") + "\r\n>"
        val duration = SystemClock.elapsedRealtime() - startMonotonic
        return@withContext ElmResponse(
            rawText = rawText,
            lines = lines,
            status = responseStatus,
            isPromptReceived = true,
            durationMs = duration,
            errorMessage = if (responseStatus != ResponseStatus.OK) responseStatus.name else null
        )
    }

    override suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>> {
        val results = mutableListOf<Pair<String, ElmResponse>>()
        for (cmd in initSequence) {
            val resp = sendCommand(cmd)
            results.add(Pair(cmd, resp))
            delay(50)
        }
        return results
    }

    private fun logRaw(isTx: Boolean, canId: String?, text: String, status: String) {
        val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        rawLogListener?.onRawLog(
            timestampUtc = nowUtc,
            timestampMonotonic = SystemClock.elapsedRealtime(),
            isTx = isTx,
            canId = canId,
            rawText = text,
            status = status
        )
    }
}
