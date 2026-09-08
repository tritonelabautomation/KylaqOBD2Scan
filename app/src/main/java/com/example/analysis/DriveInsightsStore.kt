package com.example.analysis

import com.example.engine.CoastNeutralDetector
import com.example.engine.FuelQualityAnalyzer

/**
 * Persistence codec for drive insights: coast sessions and fuel-tank segments.
 *
 * [DriveAnalytics] keeps its state in memory only. The owner's-manual coasting requirement says
 * behaviour and mileage must be SAVED, and X95-vs-regular tracking only means something across
 * refuels and app restarts — so when a recording ends, completed coast sessions and closed tank
 * segments are encoded here into compact line records and appended to the durable insight logs
 * in [com.example.data.SettingsRepository].
 *
 * Deliberately dependency-free: fixed-order `|`-delimited fields with a version tag, parseable by
 * pure JVM unit tests (no Robolectric, no JSON library).
 */
object DriveInsightsStore {

    const val FORMAT_VERSION = 1

    /** One saved coasting session (behaviour + mileage for the driving-in-neutral requirement). */
    data class CoastLogEntry(
        val savedAtUtc: String,
        val eventCount: Int,
        val neutralEvents: Int,
        val fuelCutEvents: Int,
        val distanceKm: Double,
        val coastSeconds: Double,
        val fuelUsedL: Double,
        val savedVsIdleL: Double,
        val savedVsCruiseL: Double
    ) {
        val display: String
            get() = String.format(
                "%s · %.1f km · %.0f s · %d cut / %d idle · saved %.2f L vs idle",
                savedAtUtc.take(16).replace('T', ' '),
                distanceKm, coastSeconds, fuelCutEvents, neutralEvents, savedVsIdleL
            )
    }

    /** One closed fuel-tank segment (evidence in the X95-vs-regular comparison). */
    data class TankLogEntry(
        val savedAtUtc: String,
        val startMonotonicMs: Long,
        val fuelUsedL: Double,
        val distanceKm: Double,
        val avgCruiseTimingDeg: Double?,
        val minCruiseTimingDeg: Double?,
        val avgStftPct: Double?,
        val avgLtftPct: Double?,
        val knockRetardEvents: Int,
        val score: Int,
        val gradeTag: String? = null
    ) {
        val kmPerLiter: Double?
            get() = if (fuelUsedL > 0.05 && distanceKm > 0.2) distanceKm / fuelUsedL else null

        val display: String
            get() = buildString {
                append(savedAtUtc.take(10))
                append(" · score ").append(score)
                kmPerLiter?.let { append(String.format(" · %.1f km/L", it)) }
                avgCruiseTimingDeg?.let { append(String.format(" · cruise timing %.1f°", it)) }
                append(" · knock ").append(knockRetardEvents)
                gradeTag?.let { append(" · ").append(it) }
            }

        /** Dedup key: monotonic segment start identifies one physical tank unambiguously. */
        val dedupKey: String get() = startMonotonicMs.toString()
    }

    fun encodeCoast(savedAtUtc: String, coast: CoastNeutralDetector.CoastSummary): String =
        listOf(
            "c$FORMAT_VERSION", savedAtUtc, coast.eventCount, coast.neutralEvents, coast.fuelCutEvents,
            trim(coast.totalDistanceM / 1000.0), trim(coast.totalSeconds), trim(coast.totalFuelUsedL),
            trim(coast.totalSavedVsIdleL), trim(coast.totalSavedVsCruiseL)
        ).joinToString("|")

    fun decodeCoast(line: String): CoastLogEntry? {
        val parts = line.split('|')
        if (parts.size != 10 || parts[0] != "c$FORMAT_VERSION") return null
        return try {
            CoastLogEntry(
                savedAtUtc = parts[1],
                eventCount = parts[2].toInt(),
                neutralEvents = parts[3].toInt(),
                fuelCutEvents = parts[4].toInt(),
                distanceKm = parts[5].toDouble(),
                coastSeconds = parts[6].toDouble(),
                fuelUsedL = parts[7].toDouble(),
                savedVsIdleL = parts[8].toDouble(),
                savedVsCruiseL = parts[9].toDouble()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun encodeTank(savedAtUtc: String, tank: FuelQualityAnalyzer.TankSegment): String =
        listOf(
            "t$FORMAT_VERSION", savedAtUtc, tank.startMonotonicMs,
            trim(tank.fuelUsedL), trim(tank.distanceM / 1000.0),
            tank.avgCruiseTimingDeg?.let { trim(it) } ?: "-",
            tank.minCruiseTimingDeg?.let { trim(it) } ?: "-",
            tank.avgStftPct?.let { trim(it) } ?: "-",
            tank.avgLtftPct?.let { trim(it) } ?: "-",
            tank.knockRetardEvents, tank.score, tank.gradeTag ?: "-"
        ).joinToString("|")

    fun decodeTank(line: String): TankLogEntry? {
        val parts = line.split('|')
        if ((parts.size != 11 && parts.size != 12) || parts[0] != "t$FORMAT_VERSION") return null
        return try {
            TankLogEntry(
                savedAtUtc = parts[1],
                startMonotonicMs = parts[2].toLong(),
                fuelUsedL = parts[3].toDouble(),
                distanceKm = parts[4].toDouble(),
                avgCruiseTimingDeg = parts[5].toDoubleOrNull(),
                minCruiseTimingDeg = parts[6].toDoubleOrNull(),
                avgStftPct = parts[7].toDoubleOrNull(),
                avgLtftPct = parts[8].toDoubleOrNull(),
                knockRetardEvents = parts[9].toInt(),
                score = parts[10].toInt(),
                gradeTag = parts.getOrNull(11)?.takeIf { it != "-" }
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun trim(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value)
}
