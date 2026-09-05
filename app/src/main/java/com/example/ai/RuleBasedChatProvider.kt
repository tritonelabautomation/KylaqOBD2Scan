package com.example.ai

import com.example.data.db.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Free, offline, zero-API-key AI Doctor chat provider.
 * Uses the on-device RuleBasedAnalysisEngine to answer vehicle questions
 * without requiring any Gemini API key. Works for all users out of the box.
 * Used as fallback when FirebaseAiDoctorProvider fails (no GEMINI_API_KEY).
 */
class RuleBasedChatProvider(
    private val tripRepository: TripRepository
) : AiDoctorProvider {
    override val providerName: String = "Rule-Based (Offline)"
    override val modelName: String = "EA211-1.0TSI-Doctor-v1"

    override suspend fun analyze(request: AiDoctorRequest): AiDoctorResponse = withContext(Dispatchers.IO) {
        try {
            val trips = tripRepository.allTripsFlow.first()
            val latestTrip = trips.lastOrNull()
            val report = if (latestTrip != null) {
                val samples = tripRepository.getSamplesForTrip(latestTrip.id)
                val events = tripRepository.getEventsForTrip(latestTrip.id)
                RuleBasedAnalysisEngine().analyzeTrip(latestTrip, samples, events)
            } else null
            val responseText = buildChatResponse(request.latestQuery, request.context, report)
            AiDoctorResponse(responseText = responseText, isEcuFact = false)
        } catch (e: Exception) {
            AiDoctorResponse(
                responseText = "Diagnostic engine error: ${e.localizedMessage ?: e.message}. " +
                    "Ensure a trip has been recorded before asking the AI Doctor.",
                isEcuFact = false
            )
        }
    }
    private fun buildChatResponse(query: String, context: VehicleDiagnosticContext, report: CarDoctorReport?): String {
        val q = query.lowercase()
        return when {
            report == null -> buildNoTripResponse()
            q.contains("coolant") || q.contains("temperature") -> buildCoolantResponse(report)
            q.contains("battery") || q.contains("voltage") || q.contains("charging") -> buildVoltageResponse(report)
            q.contains("rpm") || q.contains("revolutions") || q.contains("idle") -> buildRpmResponse(report)
            q.contains("throttle") || q.contains("load") || q.contains("accelerat") -> buildThrottleResponse(report)
            q.contains("boost") || q.contains("turbo") || q.contains("map") -> buildBoostResponse(report)
            q.contains("speed") || q.contains("velocity") -> buildSpeedResponse(report)
            q.contains("dtc") || q.contains("fault") || q.contains("error") || q.contains("check engine") -> buildDtcResponse(report, context)
            q.contains("fuel") || q.contains("air") || q.contains("maf") || q.contains("lambda") -> buildAirFuelResponse(report)
            q.contains("overall") || q.contains("health") || q.contains("summary") || q.contains("score") -> buildHealthSummary(report)
            q.contains("recommend") || q.contains("check") || q.contains("maintenance") -> buildRecommendations(report)
            else -> buildGeneralResponse(report, context)
        }
    }

    private fun buildNoTripResponse() = buildString {
        appendLine("## No Trip Data Available")
        appendLine()
        appendLine("I don't have any recorded trip data to analyze yet.")
        appendLine()
        appendLine("### To get vehicle diagnostics:")
        appendLine("1. Connect your OBD-II adapter")
        appendLine("2. Start a recording session")
        appendLine("3. Drive for a few minutes to collect telemetry")
        appendLine("4. Stop recording and return to AI Doctor")
        appendLine()
        appendLine("I'll then have detailed RPM, coolant, voltage, and subsystem data to analyze.")
    }

    private fun buildHealthSummary(report: CarDoctorReport) = buildString {
        val emoji = if (report.healthScore >= 85) "OK" else if (report.healthScore >= 70) "FAIR" else "POOR"
        appendLine("## Vehicle Health: " + emoji)
        appendLine()
        appendLine("Health Score: " + report.healthScore + "/100")
        appendLine()
        appendLine("Engine: " + report.engineBehavior)
        appendLine("Coolant: " + report.temperatureBehavior)
        appendLine("Electrical: " + report.voltageBehavior)
        appendLine("Boost: " + report.throttleLoadBehavior)
        appendLine()
        appendLine("Driving: " + report.drivingSummary)
        if (report.observations.isNotEmpty()) {
            appendLine()
            appendLine("Top Observations:")
            report.observations.take(4).forEach { appendLine("- [" + it.severity + "] " + it.title + ": " + it.description) }
        }
        appendLine()
        appendLine("Rule-Based Diagnostic Engine (offline, no API key required)")
    }

    private fun buildCoolantResponse(report: CarDoctorReport) = buildString {
        appendLine("## Coolant / Thermal Analysis")
        appendLine()
        appendLine(report.temperatureBehavior)
        appendLine()
        val obs = report.observations.filter { it.category == "THERMAL" || it.category == "ENGINE" }
        if (obs.isNotEmpty()) {
            appendLine("Thermal Observations")
            obs.forEach { o ->
                appendLine("- " + o.title + " (" + o.severity + "): " + o.description)
                appendLine("  -> " + o.recommendation)
            }
        }
        appendLine()
        appendLine("Normal ranges (EA211 1.0TSI): 85-105C normal, up to 110C sustained load. Above 115C -> check coolant, thermostat, radiator fans.")
    }

    private fun buildVoltageResponse(report: CarDoctorReport) = buildString {
        appendLine("## Battery / Charging Analysis")
        appendLine()
        appendLine(report.voltageBehavior)
        appendLine()
        val obs = report.observations.filter { it.category == "ELECTRICAL" }
        if (obs.isNotEmpty()) obs.forEach { appendLine("- " + it.title + " (" + it.severity + "): " + it.description + " -> " + it.recommendation) }
        appendLine()
        appendLine("Target Ranges: Engine OFF: 12.4-12.8V | Idle: 13.5-14.5V | Under load: 13.8-14.8V | Below 12.4V running -> alternator fault | Above 15V -> voltage regulator failure")
    }

    private fun buildRpmResponse(report: CarDoctorReport) = buildString {
        appendLine("## Engine RPM Analysis")
        appendLine()
        appendLine(report.engineBehavior)
        appendLine()
        val obs = report.observations.filter { it.category == "ENGINE" }
        if (obs.isNotEmpty()) obs.forEach { appendLine("- " + it.title + " (" + it.severity + "): " + it.description + " -> " + it.recommendation) }
        appendLine()
        appendLine("Normal Ranges: Idle: 600-800 RPM | City: 1500-3000 RPM | Highway: 2000-3500 RPM | WOT: up to 6000 RPM | Redline: ~6800 RPM")
    }

    private fun buildThrottleResponse(report: CarDoctorReport) = buildString {
        appendLine("## Throttle / Load Analysis")
        appendLine()
        appendLine(report.throttleLoadBehavior)
        appendLine()
        appendLine("Load Ranges: Normal: 15-40% | Moderate accel: 40-70% | Heavy load: 70-90% | WOT: 90-100%")
    }

    private fun buildBoostResponse(report: CarDoctorReport) = buildString {
        appendLine("## Turbo / Boost Analysis")
        appendLine()
        appendLine(report.throttleLoadBehavior)
        appendLine()
        appendLine("Boost Info: Peak absolute MAP ~1.5 bar (50 kPa relative boost). Low boost at high load -> check boost leak, wastegate sticking, or turbo wear.")
    }

    private fun buildSpeedResponse(report: CarDoctorReport) = buildString {
        appendLine("## Speed Analysis")
        appendLine()
        appendLine(report.drivingSummary)
    }

    private fun buildDtcResponse(report: CarDoctorReport, context: VehicleDiagnosticContext) = buildString {
        val dtcs = context.dtcs.takeIf { it.isNotBlank() && it != "N/A" && it != "None" }
        appendLine("## DTC / Fault Code Analysis")
        appendLine()
        if (dtcs.isNullOrBlank()) {
            appendLine("No active DTCs detected from the last OBD session.")
        } else {
            appendLine("Active DTCs: " + dtcs)
            appendLine("Common Skoda codes: P0171/P0172 (fuel), P0300-P0304 (misfire), P0420 (catalyst), P0401 (EGR), P0562/P0563 (voltage)")
        }
        if (report.observations.any { it.title.contains("DTC", ignoreCase = true) }) {
            appendLine()
            report.observations.filter { it.title.contains("DTC", ignoreCase = true) }
                .forEach { appendLine("- " + it.title + ": " + it.description + " -> " + it.recommendation) }
        }
        appendLine()
        appendLine("Use VCDS (VAG-COM) for Skoda-specific codes beyond standard P0-codes.")
    }

    private fun buildAirFuelResponse(report: CarDoctorReport) = buildString {
        appendLine("## Air/Fuel Mixture Analysis")
        appendLine()
        val obs = report.observations.filter { it.category == "AIR_FUEL" }
        if (obs.isNotEmpty()) obs.forEach { appendLine("- " + it.title + " (" + it.severity + "): " + it.description + " -> " + it.recommendation) }
        else appendLine("No significant air/fuel anomalies detected.")
        appendLine()
        appendLine("AFR Basics: Stoichiometric: 14.7:1 | Lambda=1.0 ideal | Lambda<1.0 rich (excess fuel) | Lambda>1.0 lean (excess air)")
    }

    private fun buildRecommendations(report: CarDoctorReport) = buildString {
        appendLine("## Recommended Checks")
        appendLine()
        report.recommendedChecks.take(8).forEachIndexed { i, c -> appendLine((i + 1).toString() + ". " + c) }
        val priority = report.observations.filter { it.severity == "WARNING" || it.severity == "CRITICAL" }
        if (priority.isNotEmpty()) {
            appendLine()
            appendLine("Priority Items")
            priority.forEach { appendLine("- " + it.title + ": " + it.possibleCause) }
        }
        appendLine()
        appendLine("Based on last trip analysis. Verify with physical inspection before servicing.")
    }

    private fun buildGeneralResponse(report: CarDoctorReport, context: VehicleDiagnosticContext) = buildString {
        appendLine("## " + context.vehicleName + " - Health: " + report.overallHealth + " (" + report.healthScore + "/100)")
        appendLine()
        appendLine(report.drivingSummary)
        appendLine()
        appendLine("Engine: " + report.engineBehavior)
        appendLine("Thermal: " + report.temperatureBehavior)
        appendLine("Electrical: " + report.voltageBehavior)
        appendLine("Boost: " + report.throttleLoadBehavior)
        if (report.observations.isNotEmpty()) {
            appendLine()
            appendLine(report.observations.size.toString() + " Notable Observations:")
            report.observations.take(5).forEach { o ->
                val icon = when (o.severity) { "CRITICAL" -> "!!"; "WARNING" -> "!"; "MONITOR" -> "?"; else -> "OK" }
                appendLine(icon + " " + o.title + ": " + o.description)
            }
        }
        appendLine()
        appendLine("Offline rule-based diagnostics. No internet required.")
        appendLine()
        appendLine("Ask me about: coolant, battery, RPM, throttle, boost, DTCs, fuel, or overall health.")
    }
}
