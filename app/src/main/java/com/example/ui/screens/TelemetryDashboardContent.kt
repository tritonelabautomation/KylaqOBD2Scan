package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    onPidClick: (String) -> Unit = {}
) {
    var expandedDriving by remember { mutableStateOf(true) }
    var expandedEconomy by remember { mutableStateOf(true) }
    var expandedEngine by remember { mutableStateOf(true) }
    var expandedFuel by remember { mutableStateOf(true) }
    var expandedCombustion by remember { mutableStateOf(false) }
    var expandedTemp by remember { mutableStateOf(false) }
    var expandedAir by remember { mutableStateOf(false) }
    var expandedGps by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {

        // 1. Driving State & Transmission (Škoda Kylaq 6-Speed AT - No DSG)
        TelemetrySectionCard(
            title = "DRIVING & TRANSMISSION (6-AT)",
            icon = Icons.Default.DirectionsCar,
            color = CyberCyan,
            isExpanded = expandedDriving,
            onToggle = { expandedDriving = !expandedDriving }
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
            isExpanded = expandedEconomy,
            onToggle = { expandedEconomy = !expandedEconomy }
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
            isExpanded = expandedEngine,
            onToggle = { expandedEngine = !expandedEngine }
        ) {
            MetricRowWithSource("Engine RPM", formatLiveValue(liveMap, "010C"), isLiveError(liveMap, "010C"), source = "PID 010C (STANDARD)")
            MetricRowWithSource("Vehicle Speed", formatLiveValue(liveMap, "010D"), isLiveError(liveMap, "010D"), source = "PID 010D (STANDARD)")
            MetricRowWithSource("Engine Load", formatLiveValue(liveMap, "0104"), isLiveError(liveMap, "0104"), source = "PID 0104 (STANDARD)")
            MetricRowWithSource("Actual Engine Torque", formatLiveValue(liveMap, "0162"), isLiveError(liveMap, "0162"), source = "PID 0162 (STANDARD)")
            MetricRowWithSource("Driver Demand Torque", formatLiveValue(liveMap, "0161"), isLiveError(liveMap, "0161"), source = "PID 0161 (STANDARD)")
            MetricRowWithSource("Reference Torque", formatLiveValue(liveMap, "0163"), isLiveError(liveMap, "0163"), source = "PID 0163 (STANDARD)")
        }

        // 4. Fuel & Direct Injection (EA211 1.0 TSI)
        TelemetrySectionCard(
            title = "FUEL & INJECTION",
            icon = Icons.Default.EvStation,
            color = ElectricAmber,
            isExpanded = expandedFuel,
            onToggle = { expandedFuel = !expandedFuel }
        ) {
            MetricRowWithSource("Engine Fuel Rate (Volume)", formatLiveValue(liveMap, "015E"), isLiveError(liveMap, "015E"), source = "PID 015E (L/h)")
            MetricRowWithSource("Engine Fuel Rate (Mass)", formatLiveValue(liveMap, "019D"), isLiveError(liveMap, "019D"), source = "PID 019D (g/s)")
            MetricRowWithSource("Fuel Pressure (Low Gauge)", formatLiveValue(liveMap, "010A"), isLiveError(liveMap, "010A"), source = "PID 010A (kPa)")
            MetricRowWithSource("Fuel Rail Pressure (Direct Inj)", formatLiveValue(liveMap, "0123"), isLiveError(liveMap, "0123"), source = "PID 0123 (kPa)")
            MetricRowWithSource("Fuel Injection Timing", formatLiveValue(liveMap, "015D"), isLiveError(liveMap, "015D"), source = "PID 015D (°)")
            MetricRowWithSource("Fuel Tank Level", formatLiveValue(liveMap, "012F"), isLiveError(liveMap, "012F"), source = "PID 012F (%)")
            MetricRowWithSource("Fuel Type", formatLiveValue(liveMap, "0151"), isLiveError(liveMap, "0151"), source = "PID 0151")
            MetricRowWithSource("Ethanol Fuel %", formatLiveValue(liveMap, "0152"), isLiveError(liveMap, "0152"), source = "PID 0152 (%)")
            MetricRowWithSource("Fuel System Status", formatLiveValue(liveMap, "0103"), isLiveError(liveMap, "0103"), source = "PID 0103")
        }

        // 5. Combustion & Trim
        TelemetrySectionCard(
            title = "COMBUSTION & TRIM",
            icon = Icons.Default.Tune,
            color = ElectricAmber,
            isExpanded = expandedCombustion,
            onToggle = { expandedCombustion = !expandedCombustion }
        ) {
            MetricRowWithSource("Short Term Fuel Trim B1", formatLiveValue(liveMap, "0106"), isLiveError(liveMap, "0106"), source = "PID 0106")
            MetricRowWithSource("Long Term Fuel Trim B1", formatLiveValue(liveMap, "0107"), isLiveError(liveMap, "0107"), source = "PID 0107")
            MetricRowWithSource("Equivalence Ratio (Lambda)", formatLiveValue(liveMap, "0144"), isLiveError(liveMap, "0144"), source = "PID 0144")
            MetricRowWithSource("Timing Advance Cyl 1", formatLiveValue(liveMap, "010E"), isLiveError(liveMap, "010E"), source = "PID 010E")
        }

        // 6. Thermal Management
        TelemetrySectionCard(
            title = "TEMPERATURES",
            icon = Icons.Default.DeviceThermostat,
            color = WarningRed,
            isExpanded = expandedTemp,
            onToggle = { expandedTemp = !expandedTemp }
        ) {
            MetricRowWithSource("Engine Coolant Temp", formatLiveValue(liveMap, "0105"), isLiveError(liveMap, "0105"), source = "PID 0105 (°C)")
            MetricRowWithSource("Intake Air Temp", formatLiveValue(liveMap, "010F"), isLiveError(liveMap, "010F"), source = "PID 010F (°C)")
            MetricRowWithSource("Ambient Air Temp", formatLiveValue(liveMap, "0146"), isLiveError(liveMap, "0146"), source = "PID 0146 (°C)")
            MetricRowWithSource("Catalyst Temp B1S1", formatLiveValue(liveMap, "013C"), isLiveError(liveMap, "013C"), source = "PID 013C (°C)")
            MetricRowWithSource("Coolant Temp 2 (Radiator)", formatLiveValue(liveMap, "0167"), isLiveError(liveMap, "0167"), source = "PID 0167 (°C)")
        }

        // 7. Air & Boost (Turbocharged EA211)
        TelemetrySectionCard(
            title = "AIR & TURBO BOOST",
            icon = Icons.Default.Compress,
            color = Color(0xFF81D4FA),
            isExpanded = expandedAir,
            onToggle = { expandedAir = !expandedAir }
        ) {
            MetricRowWithSource("Intake MAP (Boost)", formatLiveValue(liveMap, "010B"), isLiveError(liveMap, "010B"), source = "PID 010B (kPa)")
            MetricRowWithSource("MAF Air Flow", formatLiveValue(liveMap, "0110"), isLiveError(liveMap, "0110"), source = "PID 0110 (g/s)")
            MetricRowWithSource("Barometric Pressure", formatLiveValue(liveMap, "0133"), isLiveError(liveMap, "0133"), source = "PID 0133 (kPa)")
            MetricRowWithSource("Absolute Load", formatLiveValue(liveMap, "0143"), isLiveError(liveMap, "0143"), source = "PID 0143 (%)")
            MetricRowWithSource("Throttle Position", formatLiveValue(liveMap, "0111"), isLiveError(liveMap, "0111"), source = "PID 0111 (%)")
            MetricRowWithSource("Accelerator Pedal D", formatLiveValue(liveMap, "0149"), isLiveError(liveMap, "0149"), source = "PID 0149 (%)")
            MetricRowWithSource("Accelerator Pedal E", formatLiveValue(liveMap, "014A"), isLiveError(liveMap, "014A"), source = "PID 014A (%)")
            MetricRowWithSource("Commanded Throttle Actuator", formatLiveValue(liveMap, "014C"), isLiveError(liveMap, "014C"), source = "PID 014C (%)")
        }

        // 8. GPS & Route
        TelemetrySectionCard(
            title = "GPS & TELEMETRY",
            icon = Icons.Default.GpsFixed,
            color = Color(0xFFB39DDB),
            isExpanded = expandedGps,
            onToggle = { expandedGps = !expandedGps }
        ) {
            if (gpsData.isAvailable) {
                MetricRowWithSource("GPS Speed", String.format(Locale.US, "%.1f km/h", gpsData.speedKmh), source = "HARDWARE GPS")
                MetricRowWithSource("Altitude", String.format(Locale.US, "%.0f m", gpsData.altitudeMeters), source = "HARDWARE GPS")
                MetricRowWithSource("Distance Traveled", String.format(Locale.US, "%.2f km", gpsData.distanceTraveledMeters / 1000f), source = "HARDWARE GPS")
                MetricRowWithSource("GPS Accuracy", String.format(Locale.US, "%.0f m", gpsData.accuracyMeters), source = "HARDWARE GPS")
            } else {
                MetricRowWithSource("GPS Signal", "Not available", isError = true, source = "HARDWARE GPS")
            }
        }
    }
}
