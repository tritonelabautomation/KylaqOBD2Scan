package com.example.engine

import com.example.model.DrivingState
import kotlin.math.abs

/**
 * Real-time driving state classification engine.
 *
 * Infers powertrain operational state from multi-signal correlation:
 * Speed, RPM, Accelerator Position, Throttle Opening, Fuel Rate, and Brake Status.
 */
class DrivingStateEngine {

    private var previousSpeedKmh: Double? = null
    private var previousTimestampMs: Long = 0L

    data class DrivingStateResult(
        val state: DrivingState,
        val brakeStatusDisplay: String, // "ACTIVE", "RELEASED", or "Not available / Not detected"
        val isBrakeActive: Boolean?,
        val reason: String,
        val isFuelCut: Boolean,
        val isCoasting: Boolean
    )

    fun evaluate(
        timestampMonotonic: Long,
        speedKmh: Double?,
        engineRpm: Double?,
        acceleratorPct: Double?,
        throttlePct: Double?,
        fuelRateLh: Double?,
        brakeSignal: Boolean? = null // null if no explicit hardware signal available
    ): DrivingStateResult {
        val dtSec = if (previousTimestampMs > 0L) {
            ((timestampMonotonic - previousTimestampMs) / 1000.0).coerceIn(0.01, 2.0)
        } else 1.0

        val speed = speedKmh ?: 0.0
        val rpm = engineRpm ?: 0.0
        val accel = acceleratorPct ?: (throttlePct?.let { (it - 12.0).coerceAtLeast(0.0) * 1.2 }) ?: 0.0
        val fuelRate = fuelRateLh ?: 0.0

        val dSpeed = if (previousSpeedKmh != null) speed - previousSpeedKmh!! else 0.0
        val accelerationKmhPerSec = dSpeed / dtSec

        previousSpeedKmh = speed
        previousTimestampMs = timestampMonotonic

        val isEngineOn = rpm > 400.0
        val isPedalPressed = accel > 4.0
        val isPedalReleased = accel <= 1.5

        // Brake signal resolution
        val isBrakeActive = when {
            brakeSignal != null -> brakeSignal
            speed > 10.0 && accelerationKmhPerSec < -3.5 && isPedalReleased -> true // High deceleration without throttle
            else -> false
        }

        val brakeStatusDisplay = when {
            brakeSignal != null -> if (brakeSignal) "ACTIVE" else "RELEASED"
            isBrakeActive -> "ACTIVE (Inferred decel)"
            else -> "Not available / Not detected"
        }

        // Fuel-cut condition: engine spinning above idle, throttle closed, injector shutoff
        val isFuelCut = speed >= 18.0 && rpm >= 1200.0 && isPedalReleased && (fuelRate <= 0.15)

        // Simultaneous pedals check
        if (isBrakeActive && isPedalPressed) {
            return DrivingStateResult(
                state = DrivingState.BRAKE_AND_ACCELERATOR,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = true,
                reason = "Simultaneous throttle (%.0f%%) and braking detected".format(accel),
                isFuelCut = false,
                isCoasting = false
            )
        }

        // Engine Stopped / Off
        if (!isEngineOn && speed < 2.0) {
            return DrivingStateResult(
                state = DrivingState.STOPPED,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = isBrakeActive,
                reason = "Vehicle stationary, engine RPM 0",
                isFuelCut = false,
                isCoasting = false
            )
        }

        // Engine Idling
        if (isEngineOn && speed < 2.0) {
            return DrivingStateResult(
                state = DrivingState.IDLE,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = isBrakeActive,
                reason = "Vehicle stopped, engine idling (%.0f RPM)".format(rpm),
                isFuelCut = false,
                isCoasting = false
            )
        }

        // Active Braking
        if (isBrakeActive && speed >= 2.0 && accelerationKmhPerSec < -0.8) {
            return DrivingStateResult(
                state = DrivingState.BRAKING,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = true,
                reason = "Vehicle decelerating under braking (%.1f km/h/s)".format(accelerationKmhPerSec),
                isFuelCut = isFuelCut,
                isCoasting = false
            )
        }

        // Fuel-Cut Deceleration
        if (isFuelCut && accelerationKmhPerSec <= 0.0) {
            return DrivingStateResult(
                state = DrivingState.FUEL_CUT_DECELERATION,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = false,
                reason = "Deceleration with injector shutoff (Fuel: %.2f L/h, RPM: %.0f)".format(fuelRate, rpm),
                isFuelCut = true,
                isCoasting = true
            )
        }

        // Coasting (throttle closed, vehicle freewheeling/rolling, brake off)
        if (speed >= 8.0 && isPedalReleased && !isBrakeActive && accelerationKmhPerSec in -2.5..0.5) {
            return DrivingStateResult(
                state = DrivingState.COASTING,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = false,
                reason = "Throttle released (0%%), rolling at %.1f km/h".format(speed),
                isFuelCut = false,
                isCoasting = true
            )
        }

        // Accelerating
        if (isPedalPressed && (accelerationKmhPerSec > 0.5 || accel > 15.0)) {
            return DrivingStateResult(
                state = DrivingState.ACCELERATING,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = false,
                reason = "Throttle applied (%.0f%%), accel +%.1f km/h/s".format(accel, accelerationKmhPerSec),
                isFuelCut = false,
                isCoasting = false
            )
        }

        // Cruising (speed maintained under steady load)
        if (speed >= 15.0 && abs(accelerationKmhPerSec) <= 0.8 && isPedalPressed) {
            return DrivingStateResult(
                state = DrivingState.CRUISING,
                brakeStatusDisplay = brakeStatusDisplay,
                isBrakeActive = false,
                reason = "Steady cruising at %.1f km/h (Throttle: %.0f%%)".format(speed, accel),
                isFuelCut = false,
                isCoasting = false
            )
        }

        return DrivingStateResult(
            state = if (speed >= 2.0) DrivingState.CRUISING else DrivingState.IDLE,
            brakeStatusDisplay = brakeStatusDisplay,
            isBrakeActive = isBrakeActive,
            reason = "Speed: %.1f km/h, RPM: %.0f".format(speed, rpm),
            isFuelCut = false,
            isCoasting = false
        )
    }
}
