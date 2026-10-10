package com.example.data

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.asStateFlow
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
class WelcomeSpeaker(
    private val context: Context,
    initialVoiceId: String? = null
) : TextToSpeech.OnInitListener {

    /** One installable TTS voice the owner can pick for the greeting. */
    data class VoiceOption(val id: String, val label: String)

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private val _voices = kotlinx.coroutines.flow.MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: kotlinx.coroutines.flow.StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val _currentVoiceId = kotlinx.coroutines.flow.MutableStateFlow(initialVoiceId)
    val currentVoiceId: kotlinx.coroutines.flow.StateFlow<String?> = _currentVoiceId.asStateFlow()

    init {
        runCatching { tts = TextToSpeech(context, this) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            runCatching { tts?.language = Locale.getDefault() }
            publishVoices()
            // Re-apply the owner's saved choice once the engine is up.
            _currentVoiceId.value?.let { selectVoice(it) }
        } else {
            ready = false
        }
    }

    private fun publishVoices() {
        _voices.value = runCatching {
            tts?.voices
                ?.map { v -> VoiceOption(v.name, voiceLabel(v.locale.toLanguageTag(), v.name)) }
                ?.sortedBy { it.label }
                ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    /**
     * Switches the speaking voice (owner 2026-09-16: "Welcome voice is not good give me
     * option to choose"). Returns false when the engine doesn't have that voice.
     */
    fun selectVoice(id: String?): Boolean {
        _currentVoiceId.value = id
        if (id.isNullOrBlank()) return false
        val voice = runCatching { tts?.voices?.firstOrNull { it.name == id } }.getOrNull()
            ?: return false
        return runCatching { tts?.setVoice(voice) == TextToSpeech.SUCCESS }.getOrElse { false }
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

        fun voiceLabel(localeTag: String, voiceName: String): String =
            "$localeTag \u00b7 $voiceName"

        /** Saved voice wins only if the engine still offers it; otherwise default. */
        fun pickVoiceId(available: List<String>, saved: String?): String? =
            saved?.takeIf { it in available }
    }
}
