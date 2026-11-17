package com.example.engine

import java.util.Locale

/**
 * Deceleration Fuel Cut-Off (DFCO) coach — live rupee savings from zero-fuel glides.
 *
 * When the EA211 shuts the injectors on a closed-throttle overrun (fuel rate ≈ 0 L/h while
 * still moving), the car is gliding for free versus the 1.05 L/h idle it would burn in
 * neutral. This engine detects that window, integrates litres saved against idle, and
 * converts them to rupees at the owner's last petrol price.
 *
 * Honest: this is idle-vs-DFCO, not "versus holding speed with the pedal". Coast-vs-cruise
 * lives in [CoastNeutralDetector]; this coach is the live ₹ ticker for injector shutoff.
 */
class DfcoCoach(
    private val idleFuelLh: Double = PowertrainModel.IDLE_FUEL_LH,
    private val fuelCutThresholdLh: Double = 0.15,
    private val minSpeedKmh: Double = 18.0,
    private val minRpm: Double = 1200.0,
    private val releasedPedalPct: Double = 2.0,
    defaultPricePerLiterInr: Double = 102.0
) {

    data class Snapshot(
        val isDfcoActive: Boolean,
        val sessionSavedLiters: Double,
        val sessionSavedRupees: Double,
        val liveRupeesPerMinute: Double,
        val dfcoSeconds: Double,
        val dfcoDistanceKm: Double,
        val eventCount: Int,
        val pricePerLiterInr: Double,
        val message: String
    )

    private var lastTs: Long = 0L
    private var pricePerLiterInr: Double = defaultPricePerLiterInr
    private var savedLiters: Double = 0.0
    private var savedRupees: Double = 0.0
    private var dfcoSeconds: Double = 0.0
    private var dfcoDistanceKm: Double = 0.0
    private var eventCount: Int = 0
    private var inEvent: Boolean = false

    fun reset() {
        lastTs = 0L
        savedLiters = 0.0
        savedRupees = 0.0
        dfcoSeconds = 0.0
        dfcoDistanceKm = 0.0
        eventCount = 0
        inEvent = false
    }

    fun setPricePerLiterInr(price: Double) {
        if (price > 0.0) pricePerLiterInr = price
    }

    fun onSample(
        timestampMonotonicMs: Long,
        speedKmh: Double?,
        rpm: Double?,
        fuelRateLh: Double?,
        pedalPct: Double? = null,
        throttlePct: Double? = null,
        brakeActive: Boolean? = null
    ): Snapshot {
        val dtSec = if (lastTs > 0L) {
            ((timestampMonotonicMs - lastTs).coerceIn(0L, 5000L)) / 1000.0
        } else 0.0
        lastTs = timestampMonotonicMs

        val speed = speedKmh ?: 0.0
        val engineRpm = rpm ?: 0.0
        val fuel = fuelRateLh ?: Double.POSITIVE_INFINITY
        val pedal = pedalPct ?: throttlePct ?: 0.0
        val braking = brakeActive == true
        val pedalReleased = pedal <= releasedPedalPct

        val active = speed >= minSpeedKmh &&
            engineRpm >= minRpm &&
            fuel <= fuelCutThresholdLh &&
            pedalReleased &&
            !braking

        if (active && dtSec > 0.0) {
            if (!inEvent) {
                inEvent = true
                eventCount++
            }
            val dtHours = dtSec / 3600.0
            val deltaL = idleFuelLh * dtHours
            savedLiters += deltaL
            savedRupees += deltaL * pricePerLiterInr
            dfcoSeconds += dtSec
            dfcoDistanceKm += speed * dtHours
        } else {
            inEvent = false
        }

        val liveRpm = if (active) (idleFuelLh / 60.0) * pricePerLiterInr else 0.0
        val message = if (active) {
            String.format(
                Locale.US,
                "DFCO glide — ₹%.2f/min, session ₹%.1f (%.0f s, %.2f km).",
                liveRpm, savedRupees, dfcoSeconds, dfcoDistanceKm
            )
        } else if (savedRupees > 0.0) {
            String.format(
                Locale.US,
                "Zero-fuel glides saved ₹%.1f this session (%.0f s DFCO).",
                savedRupees, dfcoSeconds
            )
        } else {
            "Lift early, injectors off — DFCO savings tick in rupees."
        }

        return Snapshot(
            isDfcoActive = active,
            sessionSavedLiters = savedLiters,
            sessionSavedRupees = savedRupees,
            liveRupeesPerMinute = liveRpm,
            dfcoSeconds = dfcoSeconds,
            dfcoDistanceKm = dfcoDistanceKm,
            eventCount = eventCount,
            pricePerLiterInr = pricePerLiterInr,
            message = message
        )
    }
}
