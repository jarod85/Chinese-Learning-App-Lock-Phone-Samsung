package com.hanzilock.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Pronunciation audio through the phone's text-to-speech engine (Samsung or Google), in any language it has a voice for. */
class Speaker(context: Context) {
    enum class Status { STARTING, READY, UNAVAILABLE }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status

    private val _utterance = MutableStateFlow<String?>(null)

    /** Id of what is being spoken right now, or null when nothing is (stories follow along with it). */
    val utterance: StateFlow<String?> = _utterance

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
            _status.value = if (result == TextToSpeech.SUCCESS) Status.READY else Status.UNAVAILABLE
        }
    }

    /** Whether a voice for [locale] is installed. */
    fun hasVoice(locale: Locale): Boolean {
        if (_status.value != Status.READY) return false
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

    /** Speaks [text] in [locale]; [slow] is handy for single words. */
    fun speak(text: String, locale: Locale, slow: Boolean = false) {
        if (_status.value != Status.READY || text.isBlank() || !use(locale)) return
        tts.setSpeechRate(if (slow) 0.75f else 0.95f)
        val id = "hz-${System.nanoTime()}"
        lastQueued = id
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Speaks [parts] (id to text) one after another at [rate]; [utterance] says which one is playing. */
    fun speakAll(parts: List<Pair<String, String>>, locale: Locale, rate: Float) {
        if (_status.value != Status.READY || parts.isEmpty() || !use(locale)) return
        tts.setSpeechRate(rate)
        lastQueued = parts.last().first
        parts.forEachIndexed { i, (id, text) ->
            tts.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        if (_status.value == Status.READY) tts.stop()
    }
}
