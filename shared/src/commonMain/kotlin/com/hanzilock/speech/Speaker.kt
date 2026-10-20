package com.hanzilock.speech

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Pronunciation audio through the device's text-to-speech voices, in any language it has a voice for. */
interface Speaker {
    enum class Status { STARTING, READY, UNAVAILABLE }

    val status: StateFlow<Status>

    /** Id of what is being spoken right now, or null when nothing is (stories follow along with it). */
    val utterance: StateFlow<String?>

    /** Whether a voice for [locale] is installed. */
    fun hasVoice(locale: Locale): Boolean

    /** Speaks [text] in [locale]; [slow] is handy for single words. */
    fun speak(text: String, locale: Locale, slow: Boolean = false)

    /** Speaks [parts] (id to text) one after another at [rate] (1 = normal); [utterance] says which one is playing. */
    fun speakAll(parts: List<Pair<String, String>>, locale: Locale, rate: Float)

    fun stop()
}
