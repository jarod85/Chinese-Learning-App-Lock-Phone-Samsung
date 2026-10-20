package com.hanzilock.speech

/**
 * Speech recognition in the language being practised (Google / Samsung voice input on the phone,
 * Apple's on the Mac). Use it from the main thread. Each [start] cancels the previous recognition.
 */
interface SpeechInput {
    interface Listener {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onLevel(rmsDb: Float) {}
        fun onResults(candidates: List<String>)
        fun onError(code: Int, message: String)
    }

    val isAvailable: Boolean

    /** Listens in [language], a BCP 47 tag such as "zh-CN". */
    fun start(language: String, listener: Listener)

    fun stop()

    companion object {
        // Error codes, the same numbers as Android's SpeechRecognizer.ERROR_*.
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO = 3
        const val ERROR_SERVER = 4
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun describe(code: Int): String = when (code) {
            ERROR_NO_MATCH -> "Didn't catch that - try again, a little louder."
            ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the mic and speak."
            ERROR_NETWORK, ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the network (or the offline Chinese pack)."
            ERROR_AUDIO -> "Microphone problem."
            ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
            ERROR_RECOGNIZER_BUSY -> "Recognizer busy - try again."
            ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> "Speech service error - try again."
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> "Chinese speech recognition isn't installed. Type the pinyin instead."
            ERROR_TOO_MANY_REQUESTS -> "Too many requests - wait a moment."
            else -> "Speech recognition error ($code) - try again."
        }
    }
}
