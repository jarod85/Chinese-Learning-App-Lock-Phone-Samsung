package com.hanzilock.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Pronunciation audio through the phone's text-to-speech engine (Samsung or Google), in any language it has a voice for. */
class Speaker(context: Context) {
    enum class Status { STARTING, READY, UNAVAILABLE }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status

    private lateinit var tts: TextToSpeech
    private var current: Locale? = null

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            _status.value = if (result == TextToSpeech.SUCCESS) Status.READY else Status.UNAVAILABLE
        }
    }

    /** Whether a voice for [locale] is installed. */
    fun hasVoice(locale: Locale): Boolean {
        if (_status.value != Status.READY) return false
        val r = runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    /** Speaks [text] in [locale]; [slow] is handy for single words. */
    fun speak(text: String, locale: Locale, slow: Boolean = false) {
        if (_status.value != Status.READY || text.isBlank()) return
        if (current != locale) {
            val r = tts.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return
            current = locale
        }
        tts.setSpeechRate(if (slow) 0.75f else 0.95f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hz-${System.nanoTime()}")
    }

    fun stop() {
        if (_status.value == Status.READY) tts.stop()
    }
}
