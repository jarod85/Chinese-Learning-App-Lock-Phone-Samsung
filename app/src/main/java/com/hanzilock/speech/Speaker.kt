package com.hanzilock.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Mandarin pronunciation audio through the phone's text-to-speech engine (Samsung or Google). */
class Speaker(context: Context) {
    enum class Status { STARTING, READY, NO_CHINESE_VOICE, UNAVAILABLE }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) {
                _status.value = Status.UNAVAILABLE
                return@TextToSpeech
            }
            val lang = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            _status.value = if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Status.NO_CHINESE_VOICE
            } else {
                Status.READY
            }
        }
    }

    /** Speaks Chinese text; [slow] is handy for single words. */
    fun speak(text: String, slow: Boolean = false) {
        if (_status.value != Status.READY || text.isBlank()) return
        tts.setSpeechRate(if (slow) 0.75f else 0.95f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hz-${System.nanoTime()}")
    }

    fun stop() {
        if (_status.value == Status.READY) tts.stop()
    }
}
