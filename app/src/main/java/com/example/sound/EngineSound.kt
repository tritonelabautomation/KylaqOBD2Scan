package com.example.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Procedural engine-sound synthesis (RevHeadz-style parity, 2026-09-12).
 *
 * HONESTY NOTE: RevHeadz plays licensed recordings of real engines. We cannot and do not
 * ship recordings - this is a PHYSICS-BASED SYNTHESIZER: the fundamental frequency is the
 * engine's true firing frequency (rpm/60 * cylinders/2 for a four-stroke), shaped by a
 * per-profile harmonic bank, waveshaper grit, cam rumble and overrun backfires. It sounds
 * like a stylised engine organ, not a Ferrari - and it is driven by YOUR real telemetry
 * (PID 010C rpm, 0104 load) which RevHeadz users report is the part that breaks most often.
 *
 * Everything in this file is pure Kotlin/JVM (no Android imports) so it is unit-testable.
 */

data class EngineProfile(
    val id: String,
    val name: String,
    val cylinders: Int,
    val idleRpm: Double,
    val redlineRpm: Double,
    /** Amplitudes of firing-frequency harmonics 1..n; first is conventionally 1.0. */
    val harmonics: List<Double>,
    /** Waveshaper grit (tanh drive): higher = more aggressive exhaust note. */
    val drive: Double,
    /** Low-frequency cam-lobe rumble mix, 0..1. */
    val rumble: Double,
    /** Overrun backfire aggressiveness, 0..1. */
    val backfire: Double,
    val note: String
)

object EngineSoundProfiles {

    val TSI_TRIPLE = EngineProfile(
        id = "tsi3", name = "1.0 TSI Triple (your Kylaq)", cylinders = 3,
        idleRpm = 800.0, redlineRpm = 6500.0,
        harmonics = listOf(1.0, 0.55, 0.35, 0.2, 0.12, 0.08),
        drive = 2.2, rumble = 0.25, backfire = 0.02,
        note = "Thumpy three-pot turbo note - the honest one"
    )
    val V8_MUSCLE = EngineProfile(
        id = "v8m", name = "4.7L V8 American Muscle", cylinders = 8,
        idleRpm = 700.0, redlineRpm = 6800.0,
        harmonics = listOf(1.0, 0.8, 0.5, 0.35, 0.25, 0.15, 0.1),
        drive = 3.5, rumble = 0.45, backfire = 0.08,
        note = "Lumpy cross-plane idle, deep pull"
    )
    val V12_ITALIAN = EngineProfile(
        id = "v12i", name = "6.0L V12 Italian Supercar", cylinders = 12,
        idleRpm = 850.0, redlineRpm = 9500.0,
        harmonics = listOf(1.0, 0.6, 0.45, 0.3, 0.22, 0.18, 0.12, 0.08),
        drive = 2.8, rumble = 0.2, backfire = 0.12,
        note = "Screams to 9500 - gearbox of a GT3"
    )
    val ROTARY = EngineProfile(
        id = "rot13", name = "1.3L Twin Rotor", cylinders = 6, // 3 firing pulses/rev ~= 6-cyl 4-stroke math
        idleRpm = 900.0, redlineRpm = 9000.0,
        harmonics = listOf(1.0, 0.9, 0.7, 0.5, 0.3),
        drive = 4.0, rumble = 0.15, backfire = 0.2,
        note = "Buzzy flat rotor scream, popcorn on lift-off"
    )
    val V4_SPORTBIKE = EngineProfile(
        id = "v4sb", name = "1000cc V4 Superbike", cylinders = 4,
        idleRpm = 1200.0, redlineRpm = 14000.0,
        harmonics = listOf(1.0, 0.7, 0.5, 0.4, 0.3, 0.2, 0.15, 0.1),
        drive = 3.2, rumble = 0.1, backfire = 0.15,
        note = "14000 rpm howl - use headphones"
    )
    val CHAINSAW = EngineProfile(
        id = "cs100", name = "100cc Chainsaw (joke pack)", cylinders = 2,
        idleRpm = 3000.0, redlineRpm = 13000.0,
        harmonics = listOf(1.0, 0.95, 0.9, 0.85),
        drive = 5.0, rumble = 0.05, backfire = 0.05,
        note = "RevHeadz ships one too - two-stroke anger"
    )

    val ALL = listOf(TSI_TRIPLE, V8_MUSCLE, V12_ITALIAN, ROTARY, V4_SPORTBIKE, CHAINSAW)

    fun byId(id: String): EngineProfile = ALL.firstOrNull { it.id == id } ?: TSI_TRIPLE

    /** True four-stroke firing frequency: rpm/60 revolutions * cylinders/2 power pulses. */
    fun firingFrequencyHz(profile: EngineProfile, rpm: Double): Double =
        rpm / 60.0 * profile.cylinders / 2.0

    /** Backfires only happen on overrun: throttle closed, revs still high. */
    fun backfireAllowed(profile: EngineProfile, rpm: Double, throttle01: Double): Boolean =
        profile.backfire > 0.0 && throttle01 < 0.1 && rpm > 3000.0
}

/** Mutable phase/noise state so frequency changes never zipper the waveform. */
class EngineSynthState(profile: EngineProfile, seed: Long = 42L) {
    val phases: DoubleArray = DoubleArray(profile.harmonics.size)
    var rumbleLp: Double = 0.0
    var crackleEnv: Double = 0.0
    val random: Random = Random(seed)
}

class EngineSoundSynth(private val sampleRate: Int = 44100) {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val MIN_AUDIBLE_RPM = 200.0
    }

    /**
     * Fills [out] with 16-bit mono PCM for the given instant. Call continuously with
     * smoothly changing rpm/throttle - phase state carries across buffers.
     */
    fun render(
        state: EngineSynthState,
        profile: EngineProfile,
        rpmRaw: Double,
        throttle01Raw: Double,
        out: ShortArray
    ) {
        val rpm = rpmRaw.coerceIn(MIN_AUDIBLE_RPM, profile.redlineRpm * 1.1)
        val throttle = throttle01Raw.coerceIn(0.0, 1.0)
        val f = EngineSoundProfiles.firingFrequencyHz(profile, rpm)
        val amps = profile.harmonics
        val rpmNorm = ((rpm - profile.idleRpm) / (profile.redlineRpm - profile.idleRpm)).coerceIn(0.0, 1.0)
        // Loudness: idle purr -> full-throttle shout.
        val gain = 0.18 + 0.5 * throttle + 0.32 * rpmNorm
        val twoPiOverSr = 2.0 * PI / sampleRate
        val canBackfire = EngineSoundProfiles.backfireAllowed(profile, rpm, throttle)

        for (i in out.indices) {
            var sample = 0.0
            for (k in amps.indices) {
                val harmonic = k + 1
                sample += amps[k] * sin(state.phases[k])
                state.phases[k] += harmonic * f * twoPiOverSr
                if (state.phases[k] >= 2.0 * PI) state.phases[k] -= 2.0 * PI
            }
            // Waveshaper grit: turns the sine stack into an exhaust pulse train.
            sample = tanh(profile.drive * sample / amps.size)

            // Cam rumble: one-pole low-passed white noise, stronger at low rpm (lopy idle).
            val white = state.random.nextDouble(-1.0, 1.0)
            state.rumbleLp += 0.02 * (white - state.rumbleLp)
            sample += profile.rumble * state.rumbleLp * 3.0 * (1.2 - rpmNorm)

            // Overrun backfires: decaying crackle bursts, probability scaled by profile.
            if (canBackfire && state.crackleEnv <= 0.0 &&
                state.random.nextDouble() < profile.backfire * 0.0005
            ) {
                state.crackleEnv = 1.0
            }
            if (state.crackleEnv > 0.0) {
                sample += state.crackleEnv * state.random.nextDouble(-1.0, 1.0) * 0.9
                state.crackleEnv *= 0.9995 // ~20 ms pop at 44.1 kHz
                if (state.crackleEnv < 0.001) state.crackleEnv = 0.0
            }

            val shaped = (sample * gain * Short.MAX_VALUE * 0.55)
            out[i] = shaped.coerceIn(-32767.0, 32767.0).toShort()
        }
    }

    /** Convenience for tests/tools: peak abs amplitude of a rendered buffer. */
    fun peak(buffer: ShortArray): Int = buffer.maxOfOrNull { abs(it.toInt()) } ?: 0
}
