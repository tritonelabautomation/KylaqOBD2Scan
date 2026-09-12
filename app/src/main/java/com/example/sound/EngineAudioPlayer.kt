package com.example.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Thin Android wrapper that streams [EngineSoundSynth] output to an AudioTrack.
 *
 * Runs its own dedicated render thread (audio loops must not share coroutine pools with
 * telemetry). Callers (RevTheaterScreen) push the latest rpm/throttle; the thread renders
 * continuously so pitch tracks live OBD data buffer-by-buffer (~46 ms granularity at 2048
 * frames - tight enough that revs feel instant).
 */
class EngineAudioPlayer {

    @Volatile var profile: EngineProfile = EngineSoundProfiles.TSI_TRIPLE
        private set
    @Volatile var rpm: Double = 0.0
    @Volatile var throttle: Double = 0.0
    // private: the generated setVolume(F)V would clash with fun setVolume below.
    @Volatile private var volumeValue: Float = 0.8f
    @Volatile var running: Boolean = false
        private set

    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private val synth = EngineSoundSynth()

    fun start(initialProfile: EngineProfile) {
        if (running) {
            profile = initialProfile
            return
        }
        profile = initialProfile
        running = true
        thread = Thread({
            // Synth state is sized to the profile's harmonic count - when the user
            // switches engine packs mid-playback the state MUST be rebuilt or the
            // render loop indexes past the old phase array (crash).
            var stateProfile = profile
            var state = EngineSynthState(stateProfile)
            val buffer = ShortArray(2048)
            val minBuf = AudioTrack.getMinBufferSize(
                EngineSoundSynth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val at = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(EngineSoundSynth.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(minBuf, buffer.size * 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track = at
            try {
                at.setVolume(volumeValue)
                at.play()
                while (running) {
                    val current = profile
                    if (current.id != stateProfile.id) {
                        stateProfile = current
                        state = EngineSynthState(current)
                    }
                    // No live revs (engine off / not connected): idle the engine audibly
                    // instead of silence - RevTheater is theater, after all.
                    val effectiveRpm = if (rpm > 0.0) rpm else current.idleRpm
                    synth.render(state, current, effectiveRpm, throttle, buffer)
                    at.write(buffer, 0, buffer.size)
                }
            } finally {
                runCatching { at.stop() }
                runCatching { at.release() }
                track = null
            }
        }, "engine-sound-render").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun setVolume(v: Float) {
        volumeValue = v.coerceIn(0f, 1f)
        track?.setVolume(volumeValue)
    }

    fun stop() {
        running = false
        thread?.let { t -> runCatching { t.join(500) } }
        thread = null
    }
}
