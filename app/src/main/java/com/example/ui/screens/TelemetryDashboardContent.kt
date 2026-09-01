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

@Composable
fun TelemetryDashboardContent(
    liveMap: Map<String, String>,
    gpsData: GpsData,
    onPidClick: (String) -> Unit
) {
    var expandedEngine by remember { mutableStateOf(true) }
    var expandedFuel by remember { mutableStateOf(true) }
    var expandedCombustion by remember { mutableStateOf(false) }
    var expandedTemp by remember { mutableStateOf(false) }
    var expandedAir by remember { mutableStateOf(false) }
    var expandedGps by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        
        TelemetrySectionCard(
            title = "ENGINE DYNAMICS",
            icon = Icons.Default.Speed,
            color = CyberCyan,
            isExpanded = expandedEngine,
            onToggle = { expandedEngine = !expandedEngine }
        ) {
            MetricRow("Engine RPM", formatLiveValue(liveMap, "010C"), isLiveError(liveMap, "010C"))
            MetricRow("Vehicle Speed", formatLiveValue(liveMap, "010D"), isLiveError(liveMap, "010D"))
            MetricRow("Engine Load", formatLiveValue(liveMap, "0104"), isLiveError(liveMap, "0104"))
            MetricRow("Engine Torque", formatLiveValue(liveMap, "0162"), isLiveError(liveMap, "0162"))
        }

        TelemetrySectionCard(
            title = "FUEL & ETHANOL",
            icon = Icons.Default.LocalGasStation,
            color = NeonEmerald,
            isExpanded = expandedFuel,
            onToggle = { expandedFuel = !expandedFuel }
        ) {
            MetricRow("Fuel Type", formatLiveValue(liveMap, "0151"), isLiveError(liveMap, "0151"))
            MetricRow("Ethanol %", formatLiveValue(liveMap, "0152"), isLiveError(liveMap, "0152"))
            MetricRow("Fuel Level", formatLiveValue(liveMap, "012F"), isLiveError(liveMap, "012F"))
            MetricRow("Fuel Rate (L/h)", formatLiveValue(liveMap, "019D"), isLiveError(liveMap, "019D"))
            MetricRow("Rail Pressure", formatLiveValue(liveMap, "0123"), isLiveError(liveMap, "0123"))
            MetricRow("System Status", formatLiveValue(liveMap, "0103"), isLiveError(liveMap, "0103"))
        }

        TelemetrySectionCard(
            title = "COMBUSTION & TRIM",
            icon = Icons.Default.Tune,
            color = ElectricAmber,
            isExpanded = expandedCombustion,
            onToggle = { expandedCombustion = !expandedCombustion }
        ) {
            MetricRow("STFT Bank 1", formatLiveValue(liveMap, "0106"), isLiveError(liveMap, "0106"))
            MetricRow("LTFT Bank 1", formatLiveValue(liveMap, "0107"), isLiveError(liveMap, "0107"))
            MetricRow("Equivalence Ratio", formatLiveValue(liveMap, "0144"), isLiveError(liveMap, "0144"))
            MetricRow("Timing Advance", formatLiveValue(liveMap, "010E"), isLiveError(liveMap, "010E"))
        }

        TelemetrySectionCard(
            title = "TEMPERATURES",
            icon = Icons.Default.DeviceThermostat,
            color = WarningRed,
            isExpanded = expandedTemp,
            onToggle = { expandedTemp = !expandedTemp }
        ) {
            MetricRow("Coolant Temp", formatLiveValue(liveMap, "0105"), isLiveError(liveMap, "0105"))
            MetricRow("Intake Air Temp", formatLiveValue(liveMap, "010F"), isLiveError(liveMap, "010F"))
            MetricRow("Ambient Air Temp", formatLiveValue(liveMap, "0146"), isLiveError(liveMap, "0146"))
            MetricRow("Catalyst Temp B1S1", formatLiveValue(liveMap, "013C"), isLiveError(liveMap, "013C"))
        }

        TelemetrySectionCard(
            title = "AIR & BOOST",
            icon = Icons.Default.Compress,
            color = Color(0xFF81D4FA),
            isExpanded = expandedAir,
            onToggle = { expandedAir = !expandedAir }
        ) {
            MetricRow("Intake MAP (Boost)", formatLiveValue(liveMap, "010B"), isLiveError(liveMap, "010B"))
            MetricRow("Barometric Pressure", formatLiveValue(liveMap, "0133"), isLiveError(liveMap, "0133"))
            MetricRow("Throttle Position", formatLiveValue(liveMap, "0111"), isLiveError(liveMap, "0111"))
            MetricRow("Cmd Throttle Actuator", formatLiveValue(liveMap, "014C"), isLiveError(liveMap, "014C"))
        }

        TelemetrySectionCard(
            title = "GPS & ROUTE",
            icon = Icons.Default.GpsFixed,
            color = Color(0xFFB39DDB),
            isExpanded = expandedGps,
            onToggle = { expandedGps = !expandedGps }
        ) {
            if (gpsData.isAvailable) {
                MetricRow("GPS Speed", String.format("%.1f km/h", gpsData.speedKmh))
                MetricRow("Altitude", String.format("%.0f m", gpsData.altitudeMeters))
                MetricRow("Distance Traveled", String.format("%.2f km", gpsData.distanceTraveledMeters / 1000f))
                MetricRow("Accuracy", String.format("%.0f m", gpsData.accuracyMeters))
            } else {
                MetricRow("GPS Signal", "Unavailable", isError = true)
            }
        }
    }
}
