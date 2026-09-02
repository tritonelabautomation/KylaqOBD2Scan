package com.example.engine

import com.example.model.RealtimeEconomySnapshot
import com.example.model.TripEconomyStats
import com.example.model.ValueSource
import java.util.Locale

/**
 * High-precision fuel economy calculation and numerical integration engine.
 * Supports instantaneous mileage (km/L and L/100km), exponential smoothing,
 * idle fuel consumption, and timestamp-aware Riemann/trapezoidal trip accumulation.
 */
class EconomyEngine {

    // Smoothing factor for instantaneous readout (0.0 to 1.0, lower is smoother)
    var smoothingAlpha: Double = 0.25

    private var currentSmoothedKmL: Double? = null
    private var currentSmoothedL100km: Double? = null

    // Trip integration state
    private var lastIntegrationTimeMonotonic: Long = 0L
    private var lastFuelRateLh: Double? = null
    private var lastSpeedKmh: Double? = null

    private var accumulatedDistanceKm: Double = 0.0
    private var accumulatedFuelLiters: Double = 0.0
    private var accumulatedIdleFuelLiters: Double = 0.0
    private var accumulatedCoastingFuelLiters: Double = 0.0

    private var tripStartTimeMonotonic: Long = 0L
    private var movingTimeMs: Long = 0L
    private var idleTimeMs: Long = 0L
    private var fuelCutTimeMs: Long = 0L
    private var sampleCount: Long = 0L

    @Synchronized
    fun resetTrip() {
        lastIntegrationTimeMonotonic = 0L
        lastFuelRateLh = null
        lastSpeedKmh = null
        accumulatedDistanceKm = 0.0
        accumulatedFuelLiters = 0.0
        accumulatedIdleFuelLiters = 0.0
        accumulatedCoastingFuelLiters = 0.0
        tripStartTimeMonotonic = 0L
        movingTimeMs = 0L
        idleTimeMs = 0L
        fuelCutTimeMs = 0L
        sampleCount = 0L
        currentSmoothedKmL = null
        currentSmoothedL100km = null
    }

    /**
     * Computes the real-time instantaneous economy snapshot.
     */
    @Synchronized
    fun computeInstantEconomy(
        speedKmh: Double?,
        fuelRateLh: Double?,
        engineRpm: Double?
    ): RealtimeEconomySnapshot {
        if (speedKmh == null || fuelRateLh == null || fuelRateLh < 0.0) {
            return RealtimeEconomySnapshot(
                instantKmL = null,
                instantKmLDisplay = "—",
                instantL100km = null,
                instantL100kmDisplay = "—",
                smoothedKmL = null,
                smoothedKmLDisplay = "—",
                smoothedL100km = null,
                smoothedL100kmDisplay = "—",
                idleConsumptionLh = null,
                isIdle = false,
                source = ValueSource.UNKNOWN
            )
        }

        val isEngineRunning = (engineRpm ?: 0.0) > 400.0
        val isStationary = speedKmh < 3.0

        if (isStationary && isEngineRunning) {
            // Vehicle stopped, engine idling -> Show L/h
            return RealtimeEconomySnapshot(
                instantKmL = null,
                instantKmLDisplay = "—",
                instantL100km = null,
                instantL100kmDisplay = "—",
                smoothedKmL = null,
                smoothedKmLDisplay = "—",
                smoothedL100km = null,
                smoothedL100kmDisplay = "—",
                idleConsumptionLh = fuelRateLh,
                isIdle = true,
                source = ValueSource.CALCULATED
            )
        }

        if (speedKmh >= 3.0) {
            // Fuel-cut coasting condition (fuel rate zero or near zero)
            val isFuelCut = fuelRateLh <= 0.05
            val instantKmL = if (isFuelCut) 99.9 else (speedKmh / fuelRateLh).coerceIn(0.0, 99.9)
            val instantL100km = if (isFuelCut) 0.0 else ((fuelRateLh / speedKmh) * 100.0).coerceIn(0.0, 99.9)

            // Exponential moving average
            val smoothedKmL = currentSmoothedKmL?.let { prev ->
                (smoothingAlpha * instantKmL) + ((1.0 - smoothingAlpha) * prev)
            } ?: instantKmL
            currentSmoothedKmL = smoothedKmL

            val smoothedL100km = currentSmoothedL100km?.let { prev ->
                (smoothingAlpha * instantL100km) + ((1.0 - smoothingAlpha) * prev)
            } ?: instantL100km
            currentSmoothedL100km = smoothedL100km

            return RealtimeEconomySnapshot(
                instantKmL = instantKmL,
                instantKmLDisplay = if (isFuelCut) "99.9+ km/L" else String.format(Locale.US, "%.1f km/L", instantKmL),
                instantL100km = instantL100km,
                instantL100kmDisplay = if (isFuelCut) "0.0 L/100km" else String.format(Locale.US, "%.1f L/100km", instantL100km),
                smoothedKmL = smoothedKmL,
                smoothedKmLDisplay = String.format(Locale.US, "%.1f km/L", smoothedKmL),
                smoothedL100km = smoothedL100km,
                smoothedL100kmDisplay = String.format(Locale.US, "%.1f L/100km", smoothedL100km),
                idleConsumptionLh = null,
                isIdle = false,
                source = ValueSource.CALCULATED
            )
        }

        return RealtimeEconomySnapshot(
            instantKmL = null,
            instantKmLDisplay = "—",
            instantL100km = null,
            instantL100kmDisplay = "—",
            smoothedKmL = null,
            smoothedKmLDisplay = "—",
            smoothedL100km = null,
            smoothedL100kmDisplay = "—",
            idleConsumptionLh = null,
            isIdle = false,
            source = ValueSource.CALCULATED
        )
    }

    /**
     * Updates trip accumulation metrics based on the latest telemetry sample and monotonic timestamp.
     */
    @Synchronized
    fun processTripSample(
        timestampMonotonic: Long,
        speedKmh: Double?,
        fuelRateLh: Double?,
        engineRpm: Double?,
        isFuelCut: Boolean = false,
        isCoasting: Boolean = false
    ): TripEconomyStats {
        sampleCount++

        if (tripStartTimeMonotonic == 0L) {
            tripStartTimeMonotonic = timestampMonotonic
            lastIntegrationTimeMonotonic = timestampMonotonic
            lastFuelRateLh = fuelRateLh
            lastSpeedKmh = speedKmh
            return getTripStats(timestampMonotonic)
        }

        val dtMs = timestampMonotonic - lastIntegrationTimeMonotonic
        // Ignore unreasonable time steps (> 5.0 seconds implies pause or reconnect)
        if (dtMs in 10..5000) {
            val dtHours = dtMs / 3_600_000.0

            val currentSpeed = speedKmh ?: lastSpeedKmh ?: 0.0
            val previousSpeed = lastSpeedKmh ?: currentSpeed
            val avgSpeed = ((previousSpeed + currentSpeed) / 2.0).coerceAtLeast(0.0)

            val currentFuelRate = fuelRateLh ?: lastFuelRateLh ?: 0.0
            val previousFuelRate = lastFuelRateLh ?: currentFuelRate
            val avgFuelRate = ((previousFuelRate + currentFuelRate) / 2.0).coerceAtLeast(0.0)

            // Trapezoidal integration
            val deltaDistance = avgSpeed * dtHours
            val deltaFuel = avgFuelRate * dtHours

            accumulatedDistanceKm += deltaDistance
            accumulatedFuelLiters += deltaFuel

            if (currentSpeed < 2.0) {
                idleTimeMs += dtMs
                accumulatedIdleFuelLiters += deltaFuel
            } else {
                movingTimeMs += dtMs
                if (isCoasting) {
                    accumulatedCoastingFuelLiters += deltaFuel
                }
            }

            if (isFuelCut) {
                fuelCutTimeMs += dtMs
            }
        }

        lastIntegrationTimeMonotonic = timestampMonotonic
        if (fuelRateLh != null) lastFuelRateLh = fuelRateLh
        if (speedKmh != null) lastSpeedKmh = speedKmh

        return getTripStats(timestampMonotonic)
    }

    @Synchronized
    fun getTripStats(currentMonotonic: Long = System.currentTimeMillis()): TripEconomyStats {
        val totalDurationSec = if (tripStartTimeMonotonic > 0L) {
            ((currentMonotonic - tripStartTimeMonotonic) / 1000L).coerceAtLeast(0L)
        } else 0L

        val movingSec = movingTimeMs / 1000L
        val idleSec = idleTimeMs / 1000L

        val avgSpeed = if (totalDurationSec > 0) {
            (accumulatedDistanceKm / (totalDurationSec / 3600.0))
        } else 0.0

        val avgMovingSpeed = if (movingSec > 0) {
            (accumulatedDistanceKm / (movingSec / 3600.0))
        } else 0.0

        val avgKmL = if (accumulatedFuelLiters > 0.001) {
            (accumulatedDistanceKm / accumulatedFuelLiters).coerceIn(0.0, 99.9)
        } else 0.0

        val avgL100km = if (accumulatedDistanceKm > 0.01) {
            ((accumulatedFuelLiters / accumulatedDistanceKm) * 100.0).coerceIn(0.0, 99.9)
        } else 0.0

        return TripEconomyStats(
            tripDurationSec = totalDurationSec,
            movingDurationSec = movingSec,
            idleDurationSec = idleSec,
            distanceKm = accumulatedDistanceKm,
            averageSpeedKmh = avgSpeed,
            averageMovingSpeedKmh = avgMovingSpeed,
            totalFuelLiters = accumulatedFuelLiters,
            averageKmL = avgKmL,
            averageL100km = avgL100km,
            idleFuelLiters = accumulatedIdleFuelLiters,
            coastingFuelLiters = accumulatedCoastingFuelLiters,
            fuelCutDurationSec = fuelCutTimeMs / 1000L,
            sampleCount = sampleCount,
            isFuelIntegrated = (accumulatedFuelLiters > 0.0)
        )
    }
}
