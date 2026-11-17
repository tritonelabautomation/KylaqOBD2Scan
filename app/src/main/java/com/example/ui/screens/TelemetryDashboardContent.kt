package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.*
import com.example.data.GpsData
import com.example.engine.DrivingStateEngine
import com.example.model.RealtimeEconomySnapshot
import com.example.model.TransmissionState
import com.example.model.TripEconomyStats
import java.util.Locale

@Composable
fun TelemetryDashboardContent(
    liveMap: Map<String, String>,
    gpsData: GpsData,
    realtimeEconomy: RealtimeEconomySnapshot? = null,
    tripEconomy: TripEconomyStats? = null,
    drivingState: DrivingStateEngine.DrivingStateResult? = null,
    transmissionState: TransmissionState? = null,
    steeringAngleData: com.example.ui.components.SteeringAngleData? = null,
    capabilityStatuses: Map<String, com.example.model.CapabilityStatus> = emptyMap(),
    onPidClick: (String) -> Unit = {}
) {
    // LINEAGE FIX 2026-09-12: a missing live value must SAY why - "NOT SUPPORTED BY ECU"
    // when capability probing got a refusal, "probing..." while the scheduler's progressive
    // auto-probe has not reached the PID yet. Silent "Not available" is how this bug hid.
    val effectiveLive = remember(liveMap, capabilityStatuses) {
        val m = liveMap.toMutableMap()
        for ((rawPid, st) in capabilityStatuses) {
            val key = if (rawPid.startsWith("22", ignoreCase = true)) {
                rawPid.uppercase()
            } else {
                ("01" + rawPid.uppercase().removePrefix("01")).takeLast(4)
            }
            val cur = m[key]
            if (cur == null || cur == "Not available") {
                m[key] = when (st) {
                    com.example.model.CapabilityStatus.NOT_SUPPORTED -> "NOT SUPPORTED BY ECU"
                    com.example.model.CapabilityStatus.TIMEOUT -> "no answer (timeout)"
                    // QA/QC fuel audit 2026-09-13 (F-4): a VALIDATED pid whose live query hits a
                    // transient NO_DATA / CAN error used to fall into the else branch and keep the
                    // silent "Not available" - failure states must be loud (Batch-17 doctrine).
                    com.example.model.CapabilityStatus.NO_DATA -> "no data from ECU"
                    com.example.model.CapabilityStatus.CAN_ERROR -> "CAN bus error"
                    else -> cur ?: "probing..."
                }
            }
        }
        for (key in m.keys.toList()) {
            if (m[key] == null) m[key] = "probing..."
        }
        m
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // 2026-09-15 owner screenshot: the quick-add FAB floated over the last telemetry
        // rows (GPS value hidden behind it at end-of-scroll). Extra bottom padding lets
        // every row clear the FAB.
        modifier = Modifier.fillMaxWidth().padding(bottom = 96.dp)
    ) {

        // 0. Steering Wheel & Chassis Dynamics (Authoritative Vehicle Sensor)
        if (steeringAngleData != null) {
            com.example.ui.components.SteeringAngleGauge(
                data = steeringAngleData
            )
        }

        // 1. Driving State & Transmission (Škoda Kylaq 6-Speed AT - No DSG)
        TelemetrySectionCard(
            title = "DRIVING & TRANSMISSION (6-AT)",
            icon = Icons.Default.DirectionsCar,
            color = CyberCyan,
        ) {
            val stateName = drivingState?.state?.name ?: "UNKNOWN"
            val stateColor = when (drivingState?.state?.name) {
                "FUEL_CUT_DECELERATION" -> NeonEmerald
                "COASTING" -> NeonEmerald
                "CRUISING" -> CyberCyan
                "ACCELERATING" -> ElectricAmber
                "BRAKING" -> WarningRed
                "BRAKE_AND_ACCELERATOR" -> WarningRed
                "IDLE" -> Color.LightGray
                else -> Color.White
            }
            MetricRowWithSource(
                label = "Powertrain Driving State",
                value = stateName,
                source = "CALCULATED CORRELATION"
            )
            MetricRowWithSource(
                label = "Brake Signal Status",
                value = drivingState?.brakeStatusDisplay ?: "Not available / Not detected",
                isError = drivingState?.isBrakeActive == true,
                source = if (drivingState?.brakeStatusDisplay?.contains("Inferred") == true) "ESTIMATED" else "HARDWARE SENSOR"
            )
            MetricRowWithSource(
                label = "Transmission Range",
                value = transmissionState?.selectedRange ?: "—",
                source = "ECU / DERIVED"
            )
            MetricRowWithSource(
                label = "Actual Gear (Authoritative)",
                value = transmissionState?.actualGearDisplay ?: "Not available / Not detected",
                isError = transmissionState?.actualGear == null,
                source = "STANDARD OBD (TCU/ECU)"
            )
            MetricRowWithSource(
                label = "Estimated Gear",
                value = transmissionState?.estimatedGearDisplay ?: "—",
                source = "CALCULATED (RPM/Speed Ratio)"
            )
            MetricRowWithSource(
                label = "Torque Converter Lockup",
                value = transmissionState?.torqueConverterLockup ?: "Not available",
                source = "CALCULATED"
            )
        }

        // 2. Real-time & Integrated Trip Fuel Economy
        TelemetrySectionCard(
            title = "POWERTRAIN FUEL ECONOMY",
            icon = Icons.Default.LocalGasStation,
            color = NeonEmerald,
        ) {
            if (realtimeEconomy?.isIdle == true) {
                val idleVal = realtimeEconomy.idleConsumptionLh?.let { String.format(Locale.US, "%.2f L/h", it) } ?: "—"
                MetricRowWithSource("Idle Fuel Rate", idleVal, source = "CALCULATED (SPEED=0)")
            } else {
                MetricRowWithSource(
                    "Instant Mileage (km/L)",
                    realtimeEconomy?.instantKmLDisplay ?: "—",
                    source = "CALCULATED"
                )
                MetricRowWithSource(
                    "Instant Consumption (L/100km)",
                    realtimeEconomy?.instantL100kmDisplay ?: "—",
                    source = "CALCULATED"
                )
                MetricRowWithSource(
                    "Smoothed Mileage (km/L)",
                    realtimeEconomy?.smoothedKmLDisplay ?: "—",
                    source = "CALCULATED (EMA)"
                )
            }

            tripEconomy?.let { trip ->
                MetricRowWithSource(
                    "Trip Average Mileage",
                    if (trip.averageKmL > 0.0) String.format(Locale.US, "%.1f km/L (%.1f L/100km)", trip.averageKmL, trip.averageL100km) else "—",
                    source = "TRIP INTEGRATION"
                )
                MetricRowWithSource(
                    "Trip Distance",
                    String.format(Locale.US, "%.2f km", trip.distanceKm),
                    source = "INTEGRATED SPEED"
                )
                MetricRowWithSource(
                    "Trip Fuel Consumed",
                    String.format(Locale.US, "%.3f L", trip.totalFuelLiters),
                    source = "RIEMANN INTEGRATED FUEL"
                )
                MetricRowWithSource(
                    "Trip Idle Fuel",
                    String.format(Locale.US, "%.3f L (%.0f sec)", trip.idleFuelLiters, trip.idleDurationSec.toDouble()),
                    source = "TRIP INTEGRATION"
                )
                MetricRowWithSource(
                    "Coasting / Fuel-Cut Duration",
                    String.format(Locale.US, "%d sec", trip.fuelCutDurationSec),
                    source = "TRIP INTEGRATION"
                )
            }
        }

        // 3. Engine Dynamics
        TelemetrySectionCard(
            title = "ENGINE DYNAMICS",
            icon = Icons.Default.Speed,
            color = CyberCyan,
        ) {
            MetricRowWithSource("Engine RPM", formatLiveValue(effectiveLive, "010C"), isLiveError(effectiveLive, "010C"), source = "PID 010C (STANDARD)")
            MetricRowWithSource("Vehicle Speed", formatLiveValue(effectiveLive, "010D"), isLiveError(effectiveLive, "010D"), source = "PID 010D (STANDARD)")
            MetricRowWithSource("Engine Load", formatLiveValue(effectiveLive, "0104"), isLiveError(effectiveLive, "0104"), source = "PID 0104 (STANDARD)")
            MetricRowWithSource("Actual Engine Torque", formatLiveValue(effectiveLive, "0162"), isLiveError(effectiveLive, "0162"), source = "PID 0162 (STANDARD)")
            MetricRowWithSource("Driver Demand Torque", formatLiveValue(effectiveLive, "0161"), isLiveError(effectiveLive, "0161"), source = "PID 0161 (STANDARD)")
            MetricRowWithSource("Reference Torque", formatLiveValue(effectiveLive, "0163"), isLiveError(effectiveLive, "0163"), source = "PID 0163 (STANDARD)")
        }

        // 4. Fuel & Direct Injection (EA211 1.0 TSI)
        TelemetrySectionCard(
            title = "FUEL & INJECTION",
            icon = Icons.Default.EvStation,
            color = ElectricAmber,
        ) {
            MetricRowWithSource("Engine Fuel Rate (Volume)", formatLiveValue(effectiveLive, "015E"), isLiveError(effectiveLive, "015E"), source = "PID 015E (L/h)")
            MetricRowWithSource("Engine Fuel Rate (Mass)", formatLiveValue(effectiveLive, "019D"), isLiveError(effectiveLive, "019D"), source = "PID 019D (g/s)")
            MetricRowWithSource("Fuel Pressure (Low Gauge)", formatLiveValue(effectiveLive, "010A"), isLiveError(effectiveLive, "010A"), source = "PID 010A (kPa)")
            MetricRowWithSource("Fuel Rail Pressure (Direct Inj)", formatLiveValue(effectiveLive, "0123"), isLiveError(effectiveLive, "0123"), source = "PID 0123 (kPa)")
            MetricRowWithSource("Fuel Injection Timing", formatLiveValue(effectiveLive, "015D"), isLiveError(effectiveLive, "015D"), source = "PID 015D (°)")
            MetricRowWithSource("Fuel Tank Level", formatLiveValue(effectiveLive, "012F"), isLiveError(effectiveLive, "012F"), source = "PID 012F (%)")
            MetricRowWithSource("Fuel Type", formatLiveValue(effectiveLive, "0151"), isLiveError(effectiveLive, "0151"), source = "PID 0151")
            MetricRowWithSource("Ethanol Fuel %", formatLiveValue(effectiveLive, "0152"), isLiveError(effectiveLive, "0152"), source = "PID 0152 (%)")
            MetricRowWithSource("Fuel System Status", formatLiveValue(effectiveLive, "0103"), isLiveError(effectiveLive, "0103"), source = "PID 0103")
        }

        // 5. Combustion & Trim
        TelemetrySectionCard(
            title = "COMBUSTION & TRIM",
            icon = Icons.Default.Tune,
            color = ElectricAmber,
        ) {
            MetricRowWithSource("Short Term Fuel Trim B1", formatLiveValue(effectiveLive, "0106"), isLiveError(effectiveLive, "0106"), source = "PID 0106")
            MetricRowWithSource("Long Term Fuel Trim B1", formatLiveValue(effectiveLive, "0107"), isLiveError(effectiveLive, "0107"), source = "PID 0107")
            MetricRowWithSource("Equivalence Ratio (Lambda)", formatLiveValue(effectiveLive, "0144"), isLiveError(effectiveLive, "0144"), source = "PID 0144")
            MetricRowWithSource("Timing Advance Cyl 1", formatLiveValue(effectiveLive, "010E"), isLiveError(effectiveLive, "010E"), source = "PID 010E")
        }

        // 6. Thermal Management
        TelemetrySectionCard(
            title = "TEMPERATURES",
            icon = Icons.Default.DeviceThermostat,
            color = WarningRed,
        ) {
            MetricRowWithSource("Engine Coolant Temp", formatLiveValue(effectiveLive, "0105"), isLiveError(effectiveLive, "0105"), source = "PID 0105 (°C)")
            MetricRowWithSource("Intake Air Temp", formatLiveValue(effectiveLive, "010F"), isLiveError(effectiveLive, "010F"), source = "PID 010F (°C)")
            MetricRowWithSource("Ambient Air Temp", formatLiveValue(effectiveLive, "0146"), isLiveError(effectiveLive, "0146"), source = "PID 0146 (°C)")
            MetricRowWithSource("Catalyst Temp B1S1", formatLiveValue(effectiveLive, "013C"), isLiveError(effectiveLive, "013C"), source = "PID 013C (°C)")
            MetricRowWithSource("Coolant Temp 2 (Radiator)", formatLiveValue(effectiveLive, "0167"), isLiveError(effectiveLive, "0167"), source = "PID 0167 (°C)")
        }

        // 7. Air & Boost (Turbocharged EA211)
        TelemetrySectionCard(
            title = "AIR & TURBO BOOST",
            icon = Icons.Default.Compress,
            color = Color(0xFF81D4FA),
        ) {
            MetricRowWithSource("Intake MAP (Boost)", formatLiveValue(effectiveLive, "010B"), isLiveError(effectiveLive, "010B"), source = "PID 010B (kPa)")
            MetricRowWithSource("MAF Air Flow", formatLiveValue(effectiveLive, "0110"), isLiveError(effectiveLive, "0110"), source = "PID 0110 (g/s)")
            MetricRowWithSource("Barometric Pressure", formatLiveValue(effectiveLive, "0133"), isLiveError(effectiveLive, "0133"), source = "PID 0133 (kPa)")
            MetricRowWithSource("Absolute Load", formatLiveValue(effectiveLive, "0143"), isLiveError(effectiveLive, "0143"), source = "PID 0143 (%)")
            MetricRowWithSource("Throttle Position", formatLiveValue(effectiveLive, "0111"), isLiveError(effectiveLive, "0111"), source = "PID 0111 (%)")
            MetricRowWithSource("Accelerator Pedal D", formatLiveValue(effectiveLive, "0149"), isLiveError(effectiveLive, "0149"), source = "PID 0149 (%)")
            MetricRowWithSource("Accelerator Pedal E", formatLiveValue(effectiveLive, "014A"), isLiveError(effectiveLive, "014A"), source = "PID 014A (%)")
            MetricRowWithSource("Commanded Throttle Actuator", formatLiveValue(effectiveLive, "014C"), isLiveError(effectiveLive, "014C"), source = "PID 014C (%)")
        }

        // 8. GPS & Route
        TelemetrySectionCard(
            title = "GPS & TELEMETRY",
            icon = Icons.Default.GpsFixed,
            color = Color(0xFFB39DDB),
        ) {
            if (gpsData.isAvailable) {
                MetricRowWithSource("GPS Speed", String.format(Locale.US, "%.1f km/h", gpsData.speedKmh), source = "HARDWARE GPS")
                MetricRowWithSource(
                    "Altitude",
                    // A fix without altitude carries the meaningless 0.0 default: show the
                    // honest blank instead of claiming sea level (no-fake-values rule).
                    if (gpsData.hasAltitude) String.format(Locale.US, "%.0f m", gpsData.altitudeMeters) else "-- m",
                    source = "HARDWARE GPS"
                )
                MetricRowWithSource("Distance Traveled", String.format(Locale.US, "%.2f km", gpsData.distanceTraveledMeters / 1000f), source = "HARDWARE GPS")
                MetricRowWithSource("GPS Accuracy", String.format(Locale.US, "%.0f m", gpsData.accuracyMeters), source = "HARDWARE GPS")
            } else {
                MetricRowWithSource("GPS Signal", "Not available", isError = true, source = "HARDWARE GPS")
            }
        }

        // 9. Extended UDS: Engine Health & Direct Injection (MED17.1.27)
        TelemetrySectionCard(
            title = "ENGINE HEALTH & IGNITION (UDS)",
            icon = Icons.Default.HealthAndSafety,
            color = ElectricAmber,
        ) {
            val oilSource = if (effectiveLive["220202"] != null && !isLiveError(effectiveLive, "220202")) "UDS DID 0202 (G266)" else if (effectiveLive["015C"] != null && !isLiveError(effectiveLive, "015C")) "OBD PID 015C (Engine Oil)" else "UDS DID 0202 (G266)"
            val boostSource = if (effectiveLive["220204"] != null && !isLiveError(effectiveLive, "220204")) "UDS DID 0204 (hPa)" else if (effectiveLive["010B"] != null && !isLiveError(effectiveLive, "010B")) "OBD PID 010B (MAP)" else "UDS DID 0204 (hPa)"
            MetricRowWithSource("Engine Oil Temp (Sump)", formatLiveValue(effectiveLive, "220202", fallbackPid = "015C"), isLiveError(effectiveLive, "220202", fallbackPid = "015C"), source = oilSource)
            MetricRowWithSource("Direct Inj Rail Pressure", formatLiveValue(effectiveLive, "22020B"), isLiveError(effectiveLive, "22020B"), source = "UDS DID 020B (bar)")
            MetricRowWithSource("Pre-Turbine EGT", formatLiveValue(effectiveLive, "22020C"), isLiveError(effectiveLive, "22020C"), source = "UDS DID 020C (°C)")
            MetricRowWithSource("Target Boost Pressure", formatLiveValue(effectiveLive, "220203"), isLiveError(effectiveLive, "220203"), source = "UDS DID 0203 (hPa)")
            MetricRowWithSource("Actual Boost Pressure", formatLiveValue(effectiveLive, "220204", fallbackPid = "010B"), isLiveError(effectiveLive, "220204", fallbackPid = "010B"), source = boostSource)
            MetricRowWithSource("Cylinder 1 Knock Retard", formatLiveValue(effectiveLive, "220208"), isLiveError(effectiveLive, "220208"), source = "UDS DID 0208 (°CA)")
            MetricRowWithSource("Cylinder 2 Knock Retard", formatLiveValue(effectiveLive, "220209"), isLiveError(effectiveLive, "220209"), source = "UDS DID 0209 (°CA)")
            MetricRowWithSource("Cylinder 3 Knock Retard", formatLiveValue(effectiveLive, "22020A"), isLiveError(effectiveLive, "22020A"), source = "UDS DID 020A (°CA)")
            MetricRowWithSource("Cylinder 1 Misfires", formatLiveValue(effectiveLive, "220205"), isLiveError(effectiveLive, "220205"), source = "UDS DID 0205 (Count)")
            MetricRowWithSource("Cylinder 2 Misfires", formatLiveValue(effectiveLive, "220206"), isLiveError(effectiveLive, "220206"), source = "UDS DID 0206 (Count)")
            MetricRowWithSource("Cylinder 3 Misfires", formatLiveValue(effectiveLive, "220207"), isLiveError(effectiveLive, "220207"), source = "UDS DID 0207 (Count)")
        }

        // 10. Extended UDS: Chassis & 4-Wheel Dynamics (ABS J104)
        TelemetrySectionCard(
            title = "CHASSIS & 4-WHEEL DYNAMICS (UDS)",
            icon = Icons.Default.DirectionsCar,
            color = CyberCyan,
        ) {
            MetricRowWithSource("Brake Master Pressure", formatLiveValue(effectiveLive, "2202B3"), isLiveError(effectiveLive, "2202B3"), source = "UDS DID 02B3 (G201 bar)")
            MetricRowWithSource("Lateral Acceleration", formatLiveValue(effectiveLive, "2202B4"), isLiveError(effectiveLive, "2202B4"), source = "UDS DID 02B4 (G200)")
            MetricRowWithSource("Yaw Rate (Cornering)", formatLiveValue(effectiveLive, "2202B5"), isLiveError(effectiveLive, "2202B5"), source = "UDS DID 02B5 (G202)")
            MetricRowWithSource("Wheel Speed Front-Left", formatLiveValue(effectiveLive, "2202B0"), isLiveError(effectiveLive, "2202B0"), source = "UDS DID 02B0 (G47)")
            MetricRowWithSource("Wheel Speed Front-Right", formatLiveValue(effectiveLive, "2202B1"), isLiveError(effectiveLive, "2202B1"), source = "UDS DID 02B1 (G45)")
            MetricRowWithSource("Wheel Speed Rear-Left", formatLiveValue(effectiveLive, "2202B6"), isLiveError(effectiveLive, "2202B6"), source = "UDS DID 02B6 (G46)")
            MetricRowWithSource("Wheel Speed Rear-Right", formatLiveValue(effectiveLive, "2202B7"), isLiveError(effectiveLive, "2202B7"), source = "UDS DID 02B7 (G44)")
        }

        // 11. Extended UDS: Transmission & ATF (6-AT AQ250)
        TelemetrySectionCard(
            title = "TRANSMISSION & ATF (UDS 6-AT)",
            icon = Icons.Default.Settings,
            color = NeonEmerald,
        ) {
            MetricRowWithSource("ATF Fluid Temperature", formatLiveValue(effectiveLive, "220220"), isLiveError(effectiveLive, "220220"), source = "UDS DID 0220 (G93 °C)")
            MetricRowWithSource("Torque Converter Slip", formatLiveValue(effectiveLive, "220221"), isLiveError(effectiveLive, "220221"), source = "UDS DID 0221 (RPM)")
            MetricRowWithSource("AT Main Line Pressure", formatLiveValue(effectiveLive, "220222"), isLiveError(effectiveLive, "220222"), source = "UDS DID 0222 (bar)")
        }

        // 12. Extended UDS: Climatronic & 12V Battery Health (J255 & J533)
        TelemetrySectionCard(
            title = "CLIMATRONIC & 12V BATTERY (UDS)",
            icon = Icons.Default.BatteryChargingFull,
            color = Color(0xFF80CBC4),
        ) {
            MetricRowWithSource("A/C Refrigerant Pressure", formatLiveValue(effectiveLive, "220280"), isLiveError(effectiveLive, "220280"), source = "UDS DID 0280 (G395 bar)")
            MetricRowWithSource("A/C Compressor Load Torque", formatLiveValue(effectiveLive, "220281"), isLiveError(effectiveLive, "220281"), source = "UDS DID 0281 (Nm)")
            MetricRowWithSource("Evaporator Core Temp", formatLiveValue(effectiveLive, "220282"), isLiveError(effectiveLive, "220282"), source = "UDS DID 0282 (G308 °C)")
            MetricRowWithSource("12V Battery State of Charge", formatLiveValue(effectiveLive, "220260"), isLiveError(effectiveLive, "220260"), source = "UDS DID 0260 (SoC %)")
            MetricRowWithSource("12V Battery Resistance", formatLiveValue(effectiveLive, "220261"), isLiveError(effectiveLive, "220261"), source = "UDS DID 0261 (mΩ)")
        }
    }
}
