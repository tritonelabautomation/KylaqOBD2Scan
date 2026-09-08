package com.example.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Physical and factory model of the Škoda Kylaq 1.0 TSI (EA211 evo2, 999 cc 3-cyl turbo).
 *
 * Factory anchors: 85 kW (115 PS) at 5000-5500 rpm, 178 Nm at 1750-4000 rpm, kerb weight
 * 1169-1219 kg. The model exists for three reasons:
 *
 *  1. It turns live OBD signals into *derived* quantities the ECU does not publish on this
 *     vehicle: wheel power demand, engine torque when PID 0162 is unavailable, and fuel burn
 *     when PID 015E is unavailable.
 *  2. It produces the reference curves (torque vs rpm, L/100 km vs speed) that measured data is
 *     compared against in the Insights screens, so the user can see where reality beats or
 *     misses the brochure.
 *  3. It answers "which speed is my sweet spot?" by sweeping cruise fuel consumption through the
 *     physically possible speed range for the *measured* gear ratio.
 *
 * Everything is pure and unit-tested; nothing here touches Android APIs.
 */
object PowertrainModel {

    /** Rated power, kW (115 PS). */
    const val PEAK_POWER_KW = 85.0

    /** Plateau torque, Nm. */
    const val PEAK_TORQUE_NM = 178.0

    /** Kerb weight mid-point of the published 1169-1219 kg range. */
    const val KERB_KG = 1200.0

    /** Assumed driver mass added to kerb weight by default. */
    const val OCCUPANT_KG = 75.0

    /** Drag coefficient × frontal area, m² (compact SUV: Cd ~0.34, A ~2.1 m²). */
    const val DRAG_AREA_M2 = 0.72

    const val ROLLING_COEFF = 0.012
    const val AIR_DENSITY_KG_M3 = 1.184

    /** Petrol energy per litre: 43.4 MJ/kg × 0.745 kg/L. */
    const val FUEL_ENERGY_MJ_PER_L = 32.3

    const val TANK_CAPACITY_L = 45.0

    /** Typical warm-idle consumption of a 1.0 TSI, used as a coasting baseline. */
    const val IDLE_FUEL_LH = 0.8

    /** Rotating-mass allowance (flywheel, wheels, driveshafts) as a multiplier on vehicle mass. */
    const val ROTATING_MASS_FACTOR = 1.05

    /** Driveline efficiency from engine flywheel to wheels. */
    const val DRIVETRAIN_EFFICIENCY = 0.90

    /** Alternator, fuel pump, ECU and a modest blower load, kW. */
    const val ACCESSORY_KW = 1.2

    private const val GRAVITY = 9.81
    private const val RPM_TO_KW = 9549.297

    /** Top gear cannot hold below this crank speed without lugging. */
    const val LUGGING_RPM = 1000.0

    /**
     * Full-load (WOT) torque curve, piecewise linear in rpm. Anchored at the factory plateau
     * (178 Nm, 1750-4000 rpm) and at the rated point (85 kW at 5500 rpm ⇒ 147.5 Nm); the
     * low-rpm ramp reproduces the well-documented lag of this engine below ~2000 rpm.
     */
    private val TORQUE_CURVE: List<Pair<Double, Double>> = listOf(
        800.0 to 95.0,
        1200.0 to 140.0,
        1500.0 to 165.0,
        1750.0 to PEAK_TORQUE_NM,
        4000.0 to PEAK_TORQUE_NM,
        4500.0 to 172.0,
        5000.0 to 160.0,
        5500.0 to 147.5,
        6000.0 to 128.0,
        6500.0 to 105.0
    )

    fun fullLoadTorqueNm(rpm: Double): Double {
        if (rpm.isNaN() || rpm <= 0) return 0.0
        if (rpm <= TORQUE_CURVE.first().first) return TORQUE_CURVE.first().second
        if (rpm >= TORQUE_CURVE.last().first) return TORQUE_CURVE.last().second
        for (i in 1 until TORQUE_CURVE.size) {
            val (x1, y1) = TORQUE_CURVE[i - 1]
            val (x2, y2) = TORQUE_CURVE[i]
            if (rpm <= x2) {
                val t = (rpm - x1) / (x2 - x1)
                return y1 + t * (y2 - y1)
            }
        }
        return TORQUE_CURVE.last().second
    }

    /** P = T·ω, kW, with T in Nm and rpm in revolutions per minute. */
    fun powerKw(rpm: Double, torqueNm: Double): Double = rpm * torqueNm / RPM_TO_KW

    fun fullLoadPowerKw(rpm: Double): Double = powerKw(rpm, fullLoadTorqueNm(rpm))

    /** J1979 torque PIDs (0161/0162) report percent of reference torque. */
    fun torqueNmFromPercent(percent: Double, referenceNm: Double = PEAK_TORQUE_NM): Double =
        percent / 100.0 * referenceNm

    /** Aerodynamic + rolling + grade resistance, N. */
    fun resistanceForceN(speedKmh: Double, massKg: Double, gradePct: Double = 0.0): Double {
        val v = max(0.0, speedKmh) / 3.6
        val gradeRad = Math.toRadians(gradePct.coerceIn(-30.0, 30.0))
        val aero = 0.5 * AIR_DENSITY_KG_M3 * DRAG_AREA_M2 * v * v
        val rolling = ROLLING_COEFF * massKg * GRAVITY * cos(gradeRad)
        val climb = massKg * GRAVITY * sin(gradeRad)
        return aero + rolling + climb
    }

    /**
     * Engine power needed to hold [speedKmh] while accelerating at [accelMs2] on [gradePct],
     * including driveline loss and accessories.
     */
    fun powerDemandKw(
        speedKmh: Double,
        accelMs2: Double,
        massKg: Double = KERB_KG + OCCUPANT_KG,
        gradePct: Double = 0.0,
        accessoryKw: Double = ACCESSORY_KW
    ): Double {
        val v = max(0.0, speedKmh) / 3.6
        val force = resistanceForceN(speedKmh, massKg, gradePct) + massKg * ROTATING_MASS_FACTOR * accelMs2
        val wheelKw = force * v / 1000.0
        return max(0.0, wheelKw / DRIVETRAIN_EFFICIENCY) + accessoryKw
    }

    /**
     * Brake thermal efficiency of a small turbocharged direct-injection petrol engine.
     *
     * Shape: a bell over rpm (friction and pumping work rise at the extremes, peak around
     * 2500-3000 rpm) multiplied by a load term — at part load the throttled intake wastes
     * pumping work, which is exactly why gentle high-gear cruising beats lugging.
     */
    fun brakeThermalEfficiency(rpm: Double, torqueNm: Double): Double {
        if (rpm <= 0 || torqueNm <= 0) return 0.10
        val fullLoad = fullLoadTorqueNm(rpm).coerceAtLeast(10.0)
        val load = (torqueNm / fullLoad).coerceIn(0.02, 1.0)
        val rpmTerm = (1.0 - 0.25 * square((rpm - 2750.0) / 3250.0)).coerceIn(0.55, 1.0)
        // Below ~1500 rpm combustion stability and friction collapse efficiency far more than
        // the flat bell curve suggests; without this the modelled sweet spot drifts to lugging
        // speeds no gearbox would actually hold in top gear.
        val lowRpmPenalty = if (rpm < 1500.0) {
            0.55 + 0.45 * ((rpm - 800.0) / 700.0).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val loadTerm = 0.30 + 0.70 * load
        return (0.40 * rpmTerm * lowRpmPenalty * loadTerm).coerceIn(0.08, 0.40)
    }

    /** Fuel flow in L/h for a delivered shaft power at a given brake thermal efficiency. */
    fun fuelRateLh(powerKw: Double, efficiency: Double): Double {
        val eta = efficiency.coerceIn(0.05, 0.45)
        return max(0.0, powerKw) * 3.6 / (eta * FUEL_ENERGY_MJ_PER_L)
    }

    /**
     * Steady-state consumption in L/100 km at [speedKmh] in a gear whose ratio is
     * [rpmPerKmh] engine revolutions per km/h. Returns null when that speed is impossible in
     * the gear (below idle or above redline).
     */
    fun litersPer100Km(
        speedKmh: Double,
        rpmPerKmh: Double,
        massKg: Double = KERB_KG + OCCUPANT_KG,
        gradePct: Double = 0.0
    ): Double? {
        if (speedKmh <= 1.0 || rpmPerKmh <= 0.0) return null
        val rpm = speedKmh * rpmPerKmh
        // LUGGING_RPM: below this crank speed the gearbox cannot cruise in a tall gear -
        // it would lug or downshift, so steady-state consumption is undefined there.
        if (rpm < LUGGING_RPM || rpm > 6500.0) return null
        val power = powerDemandKw(speedKmh, 0.0, massKg, gradePct)
        val torque = power * RPM_TO_KW / rpm
        if (torque > fullLoadTorqueNm(rpm)) return null // gear too tall for the speed
        val eta = brakeThermalEfficiency(rpm, torque)
        val lh = fuelRateLh(power, eta)
        return lh / speedKmh * 100.0
    }

    /** L/100 km sweep over speed for one gear ratio — the curve behind the sweet-spot marker. */
    fun efficiencySweep(
        rpmPerKmh: Double,
        massKg: Double = KERB_KG + OCCUPANT_KG,
        fromKmh: Double = 30.0,
        toKmh: Double = 140.0,
        stepKmh: Double = 5.0
    ): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var speed = fromKmh
        while (speed <= toKmh + 0.001) {
            val lp100 = litersPer100Km(speed, rpmPerKmh, massKg)
            if (lp100 != null) out.add(speed to lp100)
            speed += stepKmh
        }
        return out
    }

    /**
     * The speed with the lowest modelled cruise consumption for a gear ratio, searching the
     * *top* usable ratio when several are supplied (highest gear always wins at a given speed).
     */
    fun sweetSpotKmh(rpmPerKmh: Double, massKg: Double = KERB_KG + OCCUPANT_KG): Pair<Double, Double>? =
        efficiencySweep(rpmPerKmh, massKg).minByOrNull { it.second }

    /** km/L from L/100 km, guarding division by zero. */
    fun kmPerLiter(lp100: Double?): Double? =
        if (lp100 == null || lp100 <= 0.001) null else 100.0 / lp100

    /** Absolute difference helper for UI deltas. */
    fun delta(a: Double?, b: Double?): Double? =
        if (a == null || b == null) null else abs(a - b)

    private fun square(x: Double): Double = x * x
}
