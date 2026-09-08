package com.example.analysis

import com.example.engine.PowertrainModel
import com.example.model.TripEconomyStats

/**
 * Turns the analytics snapshot into plain-language coaching — the "ultimate learning guide"
 * half of the app. Every tip is derived from measured data with the physical reason attached,
 * never from a hardcoded slogan, and an empty list is returned while there is not enough data
 * to say something honest.
 */
object DrivingCoach {

    data class Tip(val title: String, val detail: String, val severity: Severity) {
        enum class Severity { INFO, GOOD, WARN }
    }

    fun tips(snapshot: DriveAnalytics.DriveSnapshot, trip: TripEconomyStats): List<Tip> {
        val out = mutableListOf<Tip>()

        // 1. Speed vs the efficiency sweet spot of the measured top gear.
        val sweet = snapshot.sweetSpotKmh
        if (sweet != null && trip.averageMovingSpeedKmh > 5.0) {
            val average = trip.averageMovingSpeedKmh
            val delta = average - sweet
            when {
                delta > 15.0 -> out.add(
                    Tip(
                        "You cruise ${String.format("%.0f", delta)} km/h above your sweet spot",
                        "For your measured top-gear ratio the Kylaq needs the least fuel at about " +
                            "${String.format("%.0f", sweet)} km/h. Aerodynamic drag grows with the " +
                            "square of speed, so every 10 km/h above that costs roughly 8-12 % more " +
                            "fuel on your 32 km route.",
                        Tip.Severity.WARN
                    )
                )
                delta < -20.0 && average > 20.0 -> out.add(
                    Tip(
                        "Very low average speed: ${String.format("%.0f", average)} km/h",
                        "Below about ${String.format("%.0f", sweet)} km/h the engine runs at low load " +
                            "and high throttle-loss efficiency. If traffic allows, steady 55-75 km/h in " +
                            "the highest gear is the cheap zone for this engine.",
                        Tip.Severity.INFO
                    )
                )
                else -> out.add(
                    Tip(
                        "Average speed is inside the efficient band",
                        "Your moving average of ${String.format("%.0f", average)} km/h sits near the " +
                            "${String.format("%.0f", sweet)} km/h sweet spot of the 1.0 TSI. Keep the " +
                            "speed steady and the turbo stays off the boost side of the map.",
                        Tip.Severity.GOOD
                    )
                )
            }
        }

        // 2. Harsh inputs.
        if (snapshot.harshAccelCount >= 3) {
            out.add(
                Tip(
                    "${snapshot.harshAccelCount} harsh accelerations logged",
                    "Pulling above ~2.5 m/s² forces the ECU into enrichment and full boost, where the " +
                        "BSFC is worst. On a fixed route, smoothing launches is usually worth 5-10 %.",
                    Tip.Severity.WARN
                )
            )
        }
        if (snapshot.harshBrakeCount >= 3) {
            out.add(
                Tip(
                    "${snapshot.harshBrakeCount} harsh braking events",
                    "Every hard stop throws away kinetic energy the fuel already paid for. Lifting off " +
                        "earlier lets the fuel-cut coasting (injectors shut, 0 L/h) do the slowing.",
                    Tip.Severity.WARN
                )
            )
        }

        // 3. Coasting behaviour.
        val coast = snapshot.coast
        if (coast.fuelCutEvents > 0 || coast.neutralEvents > 0) {
            out.add(
                Tip(
                    "Coasting: ${String.format("%.1f", coast.totalKm)} km, " +
                        "${String.format("%.2f", coast.totalSavedVsCruise)} L saved",
                    if (coast.neutralEvents > coast.fuelCutEvents) {
                        "Most of your coasting happened at idle rpm (drivetrain disengaged). It is " +
                            "smooth but burns idle fuel and loses engine braking; staying in gear " +
                            "cuts injectors completely and saves more."
                    } else {
                        "Good technique: most coasts kept the gear engaged, so the ECU shut the " +
                            "injectors (0 L/h) instead of burning idle fuel."
                    },
                    if (coast.neutralEvents > coast.fuelCutEvents) Tip.Severity.INFO else Tip.Severity.GOOD
                )
            )
        }

        // 4. Idling.
        if (snapshot.idleSeconds > 180.0) {
            val liters = snapshot.idleSeconds / 3600.0 * PowertrainModel.IDLE_FUEL_LH
            out.add(
                Tip(
                    "${String.format("%.0f", snapshot.idleSeconds / 60.0)} min idling this session",
                    "A warm 1.0 TSI burns about ${PowertrainModel.IDLE_FUEL_LH} L/h standing still — " +
                        "that is ${String.format("%.2f", liters)} L, or " +
                        "${String.format("%.1f", liters / maxOf(0.5, trip.totalFuelLiters) * 100)} % of " +
                        "the trip fuel, for zero distance.",
                    Tip.Severity.WARN
                )
            )
        }

        // 5. Fuel quality evidence.
        snapshot.activeTank?.let { tank ->
            val timing = tank.avgCruiseTimingDeg
            if (tank.cruiseTimingSamples >= 30 && timing != null && timing < 20.0) {
                out.add(
                    Tip(
                        "Cruise ignition advance is low (${String.format("%.1f", timing)}°)",
                        "Healthy cruise advance on this engine is roughly 20-35°. Persistent low " +
                            "advance or positive long-term trim usually means lower-octane fuel — " +
                            "compare this tank against a 95 RON (X95) fill in the Tanks table.",
                        Tip.Severity.WARN
                    )
                )
            }
        }
        snapshot.fuelComparisonNote?.let { note ->
            out.add(Tip("Tank-to-tank fuel evidence", note, Tip.Severity.INFO))
        }

        // 6. Turbo behaviour.
        snapshot.turbo?.let { turbo ->
            val lag = turbo.averageLagMs
            if (lag != null && lag > 2500) {
                out.add(
                    Tip(
                        "Average tip-in lag ${String.format("%.1f", lag / 1000.0)} s",
                        "The wastegated turbo of the 1.0 TSI needs exhaust energy: below ~1800 rpm a " +
                            "pedal stab takes seconds to reach boost. For overtakes, plan ahead or let " +
                            "the gearbox drop a gear so you start above 2000 rpm.",
                        Tip.Severity.INFO
                    )
                )
            }
            if (turbo.overBoostEvents > 0) {
                out.add(
                    Tip(
                        "${turbo.overBoostEvents} over-boost reading(s) above 1.6 bar",
                        "Sustained manifold pressure far above the usual ~1.2-1.5 bar gauge can mean a " +
                            "sensor or wastegate anomaly. Worth a DTC scan if it repeats.",
                        Tip.Severity.WARN
                    )
                )
            }
        }

        // 7. Trip efficiency headline.
        if (trip.isFuelIntegrated && trip.distanceKm > 2.0 && trip.averageKmL > 0.0) {
            val arai = 19.0
            val ratio = trip.averageKmL / arai
            out.add(
                Tip(
                    "Trip: ${String.format("%.1f", trip.averageKmL)} km/L over " +
                        "${String.format("%.1f", trip.distanceKm)} km",
                    if (ratio >= 0.85) {
                        "That is ${String.format("%.0f", ratio * 100)} % of the ARAI figure (19.0 km/L) " +
                            "— genuinely efficient driving for a turbo petrol in Indian traffic."
                    } else {
                        "That is ${String.format("%.0f", ratio * 100)} % of the ARAI figure (19.0 km/L). " +
                            "The usual levers, in order: steady speed near the sweet spot, earlier " +
                            "lift-off, less idling, and gentler launches."
                    },
                    if (ratio >= 0.85) Tip.Severity.GOOD else Tip.Severity.INFO
                )
            )
        }

        return out
    }
}
