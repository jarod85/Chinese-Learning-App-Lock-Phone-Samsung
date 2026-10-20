package com.hanzilock.desktop

import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import javax.swing.SwingUtilities

/** Pronunciation audio with the Mac's voices (System Settings > Accessibility > Spoken Content). */
class MacSpeaker(private val helper: MacHelper) : Speaker {
    private val _status = MutableStateFlow(Speaker.Status.STARTING)
    override val status: StateFlow<Speaker.Status> = _status

    private val _utterance = MutableStateFlow<String?>(null)
    override val utterance: StateFlow<String?> = _utterance

    /** Language tags of the installed voices, e.g. "zh-CN". */
    @Volatile
    private var voices: Set<String> = emptySet()

    @Volatile
    private var lastQueued: String? = null

    init {
        helper.addListener { e ->
            when (e.eventType) {
                "voices" -> {
                    voices = (e["voices"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("lang") }.toSet()
                    _status.value = Speaker.Status.READY
                }
                "speak-start" -> _utterance.value = e.string("id")
                "speak-done" -> e.string("id")?.let { id -> if (id == lastQueued) _utterance.compareAndSet(id, null) }
                "speak-cancel" -> e.string("id")?.let { id -> _utterance.compareAndSet(id, null) }
                "exited" -> _utterance.value = null
            }
        }
        helper.send("voices")
    }

    override fun hasVoice(locale: Locale): Boolean {
        val tag = locale.toLanguageTag()
        return voices.any { v -> v.equals(tag, ignoreCase = true) } ||
            (locale.country.isEmpty() && voices.any { it.substringBefore('-').equals(locale.language, ignoreCase = true) })
    }

    override fun speak(text: String, locale: Locale, slow: Boolean) {
        if (text.isBlank()) return
        val id = "hz-${System.nanoTime()}"
        lastQueued = id
        helper.send("speak", "id" to id, "text" to text, "lang" to locale.toLanguageTag(), "rate" to if (slow) 0.75 else 0.95, "queue" to false)
    }

    override fun speakAll(parts: List<Pair<String, String>>, locale: Locale, rate: Float) {
        if (parts.isEmpty()) return
        lastQueued = parts.last().first
        parts.forEachIndexed { i, (id, text) ->
            helper.send("speak", "id" to id, "text" to text, "lang" to locale.toLanguageTag(), "rate" to rate, "queue" to (i > 0))
        }
    }

    override fun stop() = helper.send("stop")
}

/** Speech recognition with Apple's recognizer (the same one dictation uses). */
class MacSpeechInput(private val helper: MacHelper) : SpeechInput {
    override val isAvailable: Boolean get() = true

    @Volatile
    private var listener: SpeechInput.Listener? = null

    @Volatile
    private var current = 0

    init {
        helper.addListener { e ->
            val id = e["id"]?.jsonPrimitive?.intOrNull ?: return@addListener
            if (id != current) return@addListener
            val l = listener ?: return@addListener
            SwingUtilities.invokeLater {
                if (id != current) return@invokeLater
                when (e.eventType) {
                    "listen-ready" -> l.onReady()
                    "partial" -> e.string("text")?.takeIf { it.isNotBlank() }?.let(l::onPartial)
                    "level" -> e["db"]?.jsonPrimitive?.floatOrNull?.let(l::onLevel)
                    "result" -> {
                        listener = null
                        val texts = (e["texts"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.filter { it.isNotBlank() }
                        if (texts.isEmpty()) l.onError(SpeechInput.ERROR_NO_MATCH, SpeechInput.describe(SpeechInput.ERROR_NO_MATCH))
                        else l.onResults(texts)
                    }
                    "listen-error" -> {
                        listener = null
                        val code = e["code"]?.jsonPrimitive?.intOrNull ?: SpeechInput.ERROR_CLIENT
                        l.onError(code, e.string("message") ?: SpeechInput.describe(code))
                    }
                }
            }
        }
    }

    override fun start(language: String, listener: SpeechInput.Listener) {
        stop()
        val id = ++nextId
        current = id
        this.listener = listener
        helper.send("listen", "id" to id, "lang" to language)
    }

    override fun stop() {
        if (listener != null) helper.send("cancel-listen", "id" to current)
        listener = null
        current = 0
    }

    companion object {
        // Ids are unique across the quiz's and the reader's recognizers.
        @Volatile
        private var nextId = 0
    }
}

/** No voices and no speech recognition (the app running on Windows or Linux for development). */
class NoSpeaker : Speaker {
    override val status: StateFlow<Speaker.Status> = MutableStateFlow(Speaker.Status.UNAVAILABLE)
    override val utterance: StateFlow<String?> = MutableStateFlow(null)
    override fun hasVoice(locale: Locale) = false
    override fun speak(text: String, locale: Locale, slow: Boolean) = Unit
    override fun speakAll(parts: List<Pair<String, String>>, locale: Locale, rate: Float) = Unit
    override fun stop() = Unit
}

class NoSpeechInput : SpeechInput {
    override val isAvailable = false
    override fun start(language: String, listener: SpeechInput.Listener) =
        listener.onError(SpeechInput.ERROR_CLIENT, "Speech recognition needs the Mac.")
    override fun stop() = Unit
}
