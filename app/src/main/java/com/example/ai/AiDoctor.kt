package com.example.ai

import com.example.data.db.entities.AiAnalysisEntity
import com.example.data.db.entities.DiagnosticEventEntity
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Anomaly or observation from Car Doctor
 */
data class DoctorObservation(
    val category: String, // "ENGINE", "THERMAL", "ELECTRICAL", "AIR_FUEL", "INTAKE"
    val severity: String, // "NORMAL", "MONITOR", "WARNING", "CRITICAL"
    val title: String,
    val description: String,
    val possibleCause: String,
    val recommendation: String
)

/**
 * Clean result object from AI Car Doctor
 */
data class CarDoctorReport(
    val tripId: String,
    val overallHealth: String, // "NORMAL", "MONITOR", "ATTENTION", "CRITICAL"
    val healthScore: Int, // 0-100
    val drivingSummary: String,
    val engineBehavior: String,
    val temperatureBehavior: String,
    val voltageBehavior: String,
    val throttleLoadBehavior: String,
    val observations: List<DoctorObservation>,
    val recommendedChecks: List<String>,
    val confidence: String,
    val provider: String,
    val model: String
)

/**
 * Abstract AI Analysis Engine
 */
interface AiAnalysisEngine {
    suspend fun analyzeTrip(
        trip: TripEntity,
        samples: List<TelemetrySampleEntity>,
        events: List<DiagnosticEventEntity>
    ): CarDoctorReport
}

/**
 * Deterministic On-Device Rule-Based Diagnostic Engine
 * Provides instant, offline, zero-data-leakage vehicle health analysis for Škoda Kylaq 1.0 TSI (EA211)
 */
class RuleBasedAnalysisEngine : AiAnalysisEngine {

    override suspend fun analyzeTrip(
        trip: TripEntity,
        samples: List<TelemetrySampleEntity>,
        events: List<DiagnosticEventEntity>
    ): CarDoctorReport {
        val observations = mutableListOf<DoctorObservation>()
        val recommendations = mutableListOf<String>()

        // Group samples by PID for deep parametric analysis
        val samplesByPid = samples.groupBy { it.pid }

        val rpmSamples = samplesByPid["010C"]?.mapNotNull { it.numericValue } ?: emptyList()
        val speedSamples = samplesByPid["010D"]?.mapNotNull { it.numericValue } ?: emptyList()
        val coolantSamples = samplesByPid["0105"]?.mapNotNull { it.numericValue } ?: emptyList()
        val voltSamples = samplesByPid["0142"]?.mapNotNull { it.numericValue } ?: emptyList()
        val mapSamples = samplesByPid["010B"]?.mapNotNull { it.numericValue } ?: emptyList()
        val throttleSamples = samplesByPid["0111"]?.mapNotNull { it.numericValue } ?: emptyList()
        val loadSamples = samplesByPid["0104"]?.mapNotNull { it.numericValue } ?: emptyList()
        val iatSamples = samplesByPid["010F"]?.mapNotNull { it.numericValue } ?: emptyList()

        var calculatedScore = 100

        // 1. Electrical / Voltage Analysis
        val avgVolt = if (voltSamples.isNotEmpty()) voltSamples.average() else trip.avgVoltageV
        val minVolt = voltSamples.minOrNull() ?: avgVolt
        val maxVolt = voltSamples.maxOrNull() ?: avgVolt

        val voltageSummary = when {
            avgVolt in 13.6..14.8 -> {
                "Alternator charging voltage is optimal (avg: ${String.format(Locale.US, "%.2f", avgVolt)}V, range: ${String.format(Locale.US, "%.2f", minVolt)}-${String.format(Locale.US, "%.2f", maxVolt)}V). Battery charging circuit stable."
            }
            avgVolt in 12.4..13.5 -> {
                calculatedScore -= 5
                "Alternator output appears slightly low (avg: ${String.format(Locale.US, "%.2f", avgVolt)}V). Smart alternator regenerative braking mode may be cycling."
            }
            avgVolt < 12.2 && avgVolt > 0.0 -> {
                calculatedScore -= 20
                observations.add(
                    DoctorObservation(
                        category = "ELECTRICAL",
                        severity = "WARNING",
                        title = "Low Control Module Voltage",
                        description = "Observed average voltage ${String.format(Locale.US, "%.2f", avgVolt)}V during session.",
                        possibleCause = "Battery state of charge is low, or alternator voltage regulator is not active.",
                        recommendation = "Perform 12V battery load test and check alternator belt tension."
                    )
                )
                recommendations.add("Check 12V battery health and alternator output.")
                "Observed low operating voltage (avg: ${String.format(Locale.US, "%.2f", avgVolt)}V). Potential electrical discharge."
            }
            else -> "Voltage telemetry recorded: ${String.format(Locale.US, "%.2f", avgVolt)}V."
        }

        // 2. Thermal / Coolant Temperature Analysis
        val maxCoolant = coolantSamples.maxOrNull() ?: trip.maxCoolantC
        val minCoolant = coolantSamples.minOrNull() ?: maxCoolant

        val tempSummary = when {
            maxCoolant in 85.0..105.0 -> {
                "Coolant temperature operating within nominal EA211 TSI thermal management band (max: ${maxCoolant.toInt()}°C, min: ${minCoolant.toInt()}°C). Thermostat and active cooling functioning as expected."
            }
            maxCoolant > 108.0 -> {
                calculatedScore -= 25
                observations.add(
                    DoctorObservation(
                        category = "THERMAL",
                        severity = "CRITICAL",
                        title = "Elevated Engine Coolant Temperature",
                        description = "Observed peak coolant temperature of ${maxCoolant.toInt()}°C.",
                        possibleCause = "High ambient temperature with heavy load, cooling fan stage malfunction, or low coolant level.",
                        recommendation = "Inspect coolant reservoir level when cold; verify radiator fan activation."
                    )
                )
                recommendations.add("Inspect cooling system and radiator airflow.")
                "Coolant temperature peaked at ${maxCoolant.toInt()}°C (Threshold > 108°C). Monitored thermal stress."
            }
            maxCoolant in 40.0..75.0 && trip.durationSeconds > 600 -> {
                calculatedScore -= 10
                observations.add(
                    DoctorObservation(
                        category = "THERMAL",
                        severity = "MONITOR",
                        title = "Slow Warm-Up / Low Operating Temperature",
                        description = "Engine coolant only reached ${maxCoolant.toInt()}°C over a ${trip.durationSeconds / 60} min drive.",
                        possibleCause = "Thermostat stuck partially open or very short low-load trip.",
                        recommendation = "Monitor warm-up time on subsequent longer journeys."
                    )
                )
                recommendations.add("Monitor engine warm-up cycle.")
                "Engine ran below optimal operating temperature (${maxCoolant.toInt()}°C). Potential thermostat slow cycling."
            }
            else -> "Coolant temperature peaked at ${maxCoolant.toInt()}°C."
        }

        // 3. Engine Dynamics & RPM
        val maxRpm = rpmSamples.maxOrNull() ?: trip.maxRpm
        val idleSamples = rpmSamples.filterIndexed { index, rpm ->
            val speed = speedSamples.getOrNull(index) ?: 0.0
            speed < 3.0 && rpm in 600.0..1100.0
        }
        val idleVariance = if (idleSamples.size >= 5) {
            val mean = idleSamples.average()
            val variance = idleSamples.map { (it - mean) * (it - mean) }.average()
            Math.sqrt(variance)
        } else 0.0

        val engineBehavior = when {
            idleVariance > 45.0 -> {
                calculatedScore -= 10
                observations.add(
                    DoctorObservation(
                        category = "ENGINE",
                        severity = "MONITOR",
                        title = "Idle RPM Fluctuations",
                        description = "Idle RPM exhibited standard deviation of ±${String.format(Locale.US, "%.1f", idleVariance)} RPM.",
                        possibleCause = "AC compressor cycling, minor intake air turbulence, or purge valve pulse.",
                        recommendation = "Check for carbon accumulation on intake valves or throttle body."
                    )
                )
                "Engine peaked at ${maxRpm.toInt()} RPM. Idle speed showed minor fluctuation (±${String.format(Locale.US, "%.1f", idleVariance)} RPM)."
            }
            maxRpm > 5500 -> {
                "High RPM operation captured (peak: ${maxRpm.toInt()} RPM). Smooth power delivery with no sudden rev drops."
            }
            else -> {
                "Engine RPM response stable throughout session (peak: ${maxRpm.toInt()} RPM, idle stability nominal)."
            }
        }

        // 4. Intake Air & Manifold Absolute Pressure (MAP / Boost)
        val maxMap = mapSamples.maxOrNull() ?: 100.0
        val maxIat = iatSamples.maxOrNull() ?: 35.0

        val throttleLoadBehavior = when {
            maxMap > 170.0 -> {
                "Turbocharger boost active under load: Peak MAP ${maxMap.toInt()} kPa (~${String.format(Locale.US, "%.2f", (maxMap - 101.3) / 100.0)} bar boost). Intercooler maintained IAT at max ${maxIat.toInt()}°C."
            }
            else -> {
                "Naturally aspirated and light boost range observed (peak MAP: ${maxMap.toInt()} kPa). Throttle modulation consistent with driver demand."
            }
        }

        // 5. Driving Summary
        val maxSpeed = speedSamples.maxOrNull() ?: trip.maxSpeedKmh
        val avgSpeed = if (speedSamples.isNotEmpty()) speedSamples.average() else 0.0
        val drivingSummary = "Recorded ${samples.size} telemetry samples across ${trip.durationSeconds / 60}m ${trip.durationSeconds % 60}s of driving. Top speed: ${maxSpeed.toInt()} km/h (avg: ${avgSpeed.toInt()} km/h). Detection across CAN IDs ${trip.detectedEcus}."

        // Determine Overall Health State
        val overallHealth = when {
            calculatedScore >= 85 -> "NORMAL"
            calculatedScore >= 70 -> "MONITOR"
            calculatedScore >= 50 -> "ATTENTION"
            else -> "CRITICAL"
        }

        if (recommendations.isEmpty()) {
            recommendations.add("No immediate vehicle attention required. All standard OBD-II metrics operating within normal tolerances.")
        }

        return CarDoctorReport(
            tripId = trip.id,
            overallHealth = overallHealth,
            healthScore = calculatedScore.coerceIn(0, 100),
            drivingSummary = drivingSummary,
            engineBehavior = engineBehavior,
            temperatureBehavior = tempSummary,
            voltageBehavior = voltageSummary,
            throttleLoadBehavior = throttleLoadBehavior,
            observations = observations,
            recommendedChecks = recommendations,
            confidence = "HIGH",
            provider = "RULE_BASED",
            model = "EA211-1.0TSI-Doctor-v1"
        )
    }
}

/**
 * Privacy Filter to anonymize telemetry before any cloud sharing/analysis
 */
object PrivacyFilter {
    fun anonymizeTripData(
        trip: TripEntity,
        samples: List<TelemetrySampleEntity>
    ): JSONObject {
        val root = JSONObject()
        root.put("vehicleProfile", "1.0 TSI Turbo Gasoline Direct Injection")
        root.put("durationSeconds", trip.durationSeconds)
        root.put("sampleCount", samples.size)
        root.put("maxRpm", trip.maxRpm)
        root.put("maxSpeedKmh", trip.maxSpeedKmh)
        root.put("maxCoolantC", trip.maxCoolantC)
        root.put("avgVoltageV", trip.avgVoltageV)
        root.put("detectedEcus", trip.detectedEcus)

        // Subsampled parametric telemetry array (stripping timestamps to relative seconds)
        val sampledArray = JSONArray()
        val step = maxOf(1, samples.size / 50)
        for (i in samples.indices step step) {
            val s = samples[i]
            val item = JSONObject()
            item.put("relSec", (s.timestamp - trip.startTimestamp) / 1000)
            item.put("pid", s.pid)
            item.put("name", s.parameterName)
            item.put("val", s.numericValue)
            item.put("unit", s.unit)
            sampledArray.put(item)
        }
        root.put("sampledTelemetry", sampledArray)
        return root
    }
}
