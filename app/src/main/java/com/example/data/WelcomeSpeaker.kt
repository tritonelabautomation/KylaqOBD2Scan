package com.example.data

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Car Welcome voice (owner request 2026-09-16, from the MacroDroid "Car Welcome"
 * recipe screenshots): the moment the OBD link connects, the phone speaks a
 * greeting - natively, no third-party automation app needed.
 *
 * Uses the platform TTS engine over the media stream. Honesty rule: this is the
 * PHONE speaking through the phone/media audio path - never claimed to come
 * from the OBD adapter or head unit.
 */
class WelcomeSpeaker(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        runCatching { tts = TextToSpeech(context, this) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            runCatching { tts?.language = Locale.getDefault() }
        } else {
            ready = false
        }
    }

    /** True when a TTS engine is available on this phone. */
    fun isReady(): Boolean = ready

    /** Speaks [message] immediately, replacing anything queued. No-op without a TTS engine. */
    fun speak(message: String) {
        if (!ready || message.isBlank()) return
        runCatching {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "kylaq_car_welcome")
        }
    }

    /** MacroDroid-style optional action: park the media stream at [pct]% before speaking. */
    fun setMediaVolumePercent(pct: Int) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel(pct, max), 0)
        }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    companion object {
        const val DEFAULT_MESSAGE = "Welcome back! Fasten your seatbelt and have a safe drive."

        /** Re-spam guard: one greeting per link session, never closer than 5 minutes. */
        const val MIN_INTERVAL_MS = 5 * 60_000L

        fun shouldSpeak(lastSpokeMs: Long, nowMs: Long): Boolean =
            lastSpokeMs <= 0L || nowMs - lastSpokeMs >= MIN_INTERVAL_MS

        /** `{car}` placeholder = garage vehicle name; empty template falls back to default. */
        fun resolveMessage(template: String, vehicleName: String): String =
            template.replace("{car}", vehicleName, ignoreCase = true).trim()
                .ifBlank { DEFAULT_MESSAGE }

        fun volumeLevel(pct: Int, streamMax: Int): Int =
            ((streamMax.toLong() * pct.coerceIn(0, 100)) / 100L).toInt()
    }
}
