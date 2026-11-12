package com.example.analysis

import com.example.data.RecordTime
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Directional & Commute Route Apples-to-Apples Trip Comparator.
 *
 * Compares current trip against previous trips traveling in the same direction / time slot
 * (e.g., Morning Commute vs previous Morning Commutes, Evening vs Evening) to isolate
 * the exact effects of passenger payload vs solo driving.
 *
 * Pure, robust and JVM-unit-testable.
 */
object CommuteComparator {

    enum class CommuteSlot(val title: String, val directionDescription: String) {
        MORNING("Morning Commute", "Outbound / To Office (06:00 - 11:30 IST)"),
        EVENING("Evening Commute", "Inbound / Return Home (15:30 - 21:30 IST)"),
        OFF_PEAK("Off-Peak / Daytime", "Mid-day / Weekend Drive")
    }

    data class TripCommuteProfile(
        val tripId: String,
        val title: String,
        val startTimestampMs: Long,
        val distanceKm: Double,
        val durationSeconds: Long,
        val avgSpeedKmh: Double,
        val fuelLiters: Double,
        val kmPerLiter: Double?,
        val occupantCount: Int,
        val payloadKg: Double,
        val meanTorqueNm: Double?,
        val peakTorqueNm: Double?,
        val boostActivePct: Double,
        val sportShiftsPct: Double,
        val avgUpshiftRpm: Double?
    )

    data class CommuteComparison(
        val slot: CommuteSlot,
        val currentTrip: TripCommuteProfile,
        val matchingHistoricalTripsCount: Int,
        val soloBaseline: TripCommuteProfile?,
        val historicalSlotAverage: TripCommuteProfile?,
        // Deltas (current - baseline)
        val payloadDeltaKg: Double?,
        val kmPerLiterDelta: Double?,
        val kmPerLiterDeltaPct: Double?,
        val meanTorqueDeltaNm: Double?,
        val boostActivePctDelta: Double?,
        val sportShiftsPctDelta: Double?,
        val durationMinutesDelta: Double?,
        val avgSpeedDeltaKmh: Double?,
        val summaryInsight: String
    )

    /**
     * Determines the commute time slot from an epoch timestamp in IST.
     */
    fun classifySlot(timestampMs: Long): CommuteSlot {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        cal.timeInMillis = timestampMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val totalMinutes = hour * 60 + minute

        return when (totalMinutes) {
            in (6 * 60)..(11 * 60 + 30) -> CommuteSlot.MORNING
            in (15 * 60 + 30)..(21 * 60 + 30) -> CommuteSlot.EVENING
            else -> CommuteSlot.OFF_PEAK
        }
    }

    /**
     * Compares the current trip profile against historical peer trips.
     */
    fun compare(
        currentTrip: TripCommuteProfile,
        historicalTrips: List<TripCommuteProfile>,
        distanceTolerancePct: Double = 0.25
    ): CommuteComparison {
        val currentSlot = classifySlot(currentTrip.startTimestampMs)

        // Filter historical trips that match the same slot and similar route distance (+/- 25%)
        val minDistance = currentTrip.distanceKm * (1.0 - distanceTolerancePct)
        val maxDistance = currentTrip.distanceKm * (1.0 + distanceTolerancePct)

        val matchingPeers = historicalTrips.filter { p ->
            p.tripId != currentTrip.tripId &&
                classifySlot(p.startTimestampMs) == currentSlot &&
                p.distanceKm in minDistance..maxDistance
        }

        // 1. Solo baseline (1 occupant)
        val soloTrips = matchingPeers.filter { it.occupantCount <= 1 }
        val soloBaseline = if (soloTrips.isNotEmpty()) aggregateProfiles(soloTrips, "Solo Baseline") else null

        // 2. Historical average of all matching runs
        val historicalSlotAverage = if (matchingPeers.isNotEmpty()) aggregateProfiles(matchingPeers, "${currentSlot.title} Avg") else null

        val baselineToCompare = soloBaseline ?: historicalSlotAverage

        val payloadDelta = baselineToCompare?.let { currentTrip.payloadKg - it.payloadKg }
        val kmLDelta = if (currentTrip.kmPerLiter != null && baselineToCompare?.kmPerLiter != null) {
            currentTrip.kmPerLiter - baselineToCompare.kmPerLiter
        } else null
        val kmLDeltaPct = if (kmLDelta != null && baselineToCompare?.kmPerLiter != null && baselineToCompare.kmPerLiter > 0) {
            (kmLDelta / baselineToCompare.kmPerLiter) * 100.0
        } else null

        val torqueDelta = if (currentTrip.meanTorqueNm != null && baselineToCompare?.meanTorqueNm != null) {
            currentTrip.meanTorqueNm - baselineToCompare.meanTorqueNm
        } else null

        val boostDelta = baselineToCompare?.let { currentTrip.boostActivePct - it.boostActivePct }
        val sportShiftDelta = baselineToCompare?.let { currentTrip.sportShiftsPct - it.sportShiftsPct }
        val durationMinDelta = baselineToCompare?.let { (currentTrip.durationSeconds - it.durationSeconds) / 60.0 }
        val avgSpeedDelta = baselineToCompare?.let { currentTrip.avgSpeedKmh - it.avgSpeedKmh }

        val summaryInsight = buildSummaryInsight(
            currentSlot = currentSlot,
            currentTrip = currentTrip,
            baseline = baselineToCompare,
            matchingCount = matchingPeers.size,
            payloadDelta = payloadDelta,
            kmLDelta = kmLDelta,
            kmLDeltaPct = kmLDeltaPct,
            torqueDelta = torqueDelta,
            sportShiftDelta = sportShiftDelta
        )

        return CommuteComparison(
            slot = currentSlot,
            currentTrip = currentTrip,
            matchingHistoricalTripsCount = matchingPeers.size,
            soloBaseline = soloBaseline,
            historicalSlotAverage = historicalSlotAverage,
            payloadDeltaKg = payloadDelta,
            kmPerLiterDelta = kmLDelta,
            kmPerLiterDeltaPct = kmLDeltaPct,
            meanTorqueDeltaNm = torqueDelta,
            boostActivePctDelta = boostDelta,
            sportShiftsPctDelta = sportShiftDelta,
            durationMinutesDelta = durationMinDelta,
            avgSpeedDeltaKmh = avgSpeedDelta,
            summaryInsight = summaryInsight
        )
    }

    private fun aggregateProfiles(trips: List<TripCommuteProfile>, label: String): TripCommuteProfile {
        val count = trips.size.toDouble()
        val avgDist = trips.map { it.distanceKm }.average()
        val avgDur = trips.map { it.durationSeconds }.average().toLong()
        val avgSpd = trips.map { it.avgSpeedKmh }.average()
        val avgFuel = trips.map { it.fuelLiters }.average()
        val kmLs = trips.mapNotNull { it.kmPerLiter }
        val avgKmL = if (kmLs.isNotEmpty()) kmLs.average() else null
        val avgOcc = trips.map { it.occupantCount }.average().toInt()
        val avgPayload = trips.map { it.payloadKg }.average()
        val torques = trips.mapNotNull { it.meanTorqueNm }
        val avgTorque = if (torques.isNotEmpty()) torques.average() else null
        val peakTorques = trips.mapNotNull { it.peakTorqueNm }
        val maxPeakTorque = if (peakTorques.isNotEmpty()) peakTorques.maxOrNull() else null
        val avgBoost = trips.map { it.boostActivePct }.average()
        val avgSportShift = trips.map { it.sportShiftsPct }.average()
        val upshifts = trips.mapNotNull { it.avgUpshiftRpm }
        val avgUpshift = if (upshifts.isNotEmpty()) upshifts.average() else null

        return TripCommuteProfile(
            tripId = "agg_${trips.hashCode()}",
            title = label,
            startTimestampMs = trips.first().startTimestampMs,
            distanceKm = avgDist,
            durationSeconds = avgDur,
            avgSpeedKmh = avgSpd,
            fuelLiters = avgFuel,
            kmPerLiter = avgKmL,
            occupantCount = avgOcc,
            payloadKg = avgPayload,
            meanTorqueNm = avgTorque,
            peakTorqueNm = maxPeakTorque,
            boostActivePct = avgBoost,
            sportShiftsPct = avgSportShift,
            avgUpshiftRpm = avgUpshift
        )
    }

    private fun buildSummaryInsight(
        currentSlot: CommuteSlot,
        currentTrip: TripCommuteProfile,
        baseline: TripCommuteProfile?,
        matchingCount: Int,
        payloadDelta: Double?,
        kmLDelta: Double?,
        kmLDeltaPct: Double?,
        torqueDelta: Double?,
        sportShiftDelta: Double?
    ): String {
        if (baseline == null || matchingCount == 0) {
            return "First logged ${currentSlot.title.lowercase(Locale.US)} on this ~${String.format(Locale.US, "%.1f", currentTrip.distanceKm)} km route. Future ${currentSlot.title.lowercase(Locale.US)} runs will compare automatically."
        }

        val occupantNote = if (currentTrip.occupantCount > 1) {
            "${currentTrip.occupantCount} occupants (+${currentTrip.payloadKg.toInt()} kg payload)"
        } else {
            "Solo driver (+${currentTrip.payloadKg.toInt()} kg)"
        }

        val fuelDiffNote = if (kmLDelta != null && kmLDeltaPct != null) {
            val sign = if (kmLDelta >= 0) "+" else ""
            "Fuel economy: $sign${String.format(Locale.US, "%.1f", kmLDelta)} km/L ($sign${String.format(Locale.US, "%.1f", kmLDeltaPct)}% vs ${baseline.title.lowercase(Locale.US)})"
        } else ""

        val torqueNote = if (torqueDelta != null && kotlin.math.abs(torqueDelta) >= 1.0) {
            val sign = if (torqueDelta >= 0) "+" else ""
            " • Mean torque: $sign${String.format(Locale.US, "%.0f", torqueDelta)} Nm demand"
        } else ""

        val shiftNote = if (sportShiftDelta != null && kotlin.math.abs(sportShiftDelta) >= 5.0) {
            val sign = if (sportShiftDelta >= 0) "+" else ""
            " • Sport/>2.8k RPM shifts: $sign${String.format(Locale.US, "%.0f", sportShiftDelta)}% pts"
        } else ""

        return "Compared to ${matchingCount} previous ${currentSlot.title.lowercase(Locale.US)} run(s) [${baseline.title}]: $occupantNote. $fuelDiffNote$torqueNote$shiftNote."
    }
}
