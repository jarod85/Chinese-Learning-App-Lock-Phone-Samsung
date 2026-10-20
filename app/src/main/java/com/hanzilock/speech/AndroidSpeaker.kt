package com.hanzilock.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Pronunciation audio through the phone's text-to-speech engine (Samsung or Google), in any language it has a voice for. */
class AndroidSpeaker(context: Context) : Speaker {
    private val _status = MutableStateFlow(Speaker.Status.STARTING)
    override val status: StateFlow<Speaker.Status> = _status

    private val _utterance = MutableStateFlow<String?>(null)

    override val utterance: StateFlow<String?> = _utterance

    @Volatile
    private var lastQueued: String? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _utterance.value = utteranceId
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastQueued) _utterance.compareAndSet(utteranceId, null)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _utterance.compareAndSet(utteranceId, null)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _utterance.compareAndSet(utteranceId, null)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _utterance.compareAndSet(utteranceId, null)
        }
    }

    private lateinit var tts: TextToSpeech
    private var current: Locale? = null

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            if (result == TextToSpeech.SUCCESS) tts.setOnUtteranceProgressListener(progress)
            _status.value = if (result == TextToSpeech.SUCCESS) Speaker.Status.READY else Speaker.Status.UNAVAILABLE
        }
    }

    override fun hasVoice(locale: Locale): Boolean {
        if (_status.value != Speaker.Status.READY) return false
        val r = runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    private fun use(locale: Locale): Boolean {
        if (current != locale) {
            val r = tts.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
            current = locale
        }
        return true
    }

    override fun speak(text: String, locale: Locale, slow: Boolean) {
        if (_status.value != Speaker.Status.READY || text.isBlank() || !use(locale)) return
        tts.setSpeechRate(if (slow) 0.75f else 0.95f)
        val id = "hz-${System.nanoTime()}"
        lastQueued = id
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun speakAll(parts: List<Pair<String, String>>, locale: Locale, rate: Float) {
        if (_status.value != Speaker.Status.READY || parts.isEmpty() || !use(locale)) return
        tts.setSpeechRate(rate)
        lastQueued = parts.last().first
        parts.forEachIndexed { i, (id, text) ->
            tts.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    override fun stop() {
        if (_status.value == Speaker.Status.READY) tts.stop()
    }
}
