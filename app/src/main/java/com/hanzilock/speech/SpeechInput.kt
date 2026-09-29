package com.hanzilock.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around Android's SpeechRecognizer (Google / Samsung voice input on the S24 FE).
 * Must be used from the main thread. Each [start] cancels the previous recognition.
 */
class SpeechInput(private val context: Context) {
    interface Listener {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onLevel(rmsDb: Float) {}
        fun onResults(candidates: List<String>)
        fun onError(code: Int, message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Google's recognizer handles Mandarin reliably, so use it when it's installed (it is on
     * Galaxy phones sold outside China); otherwise, or if it fails, the phone's default one.
     */
    private fun preferredService(): ComponentName? {
        if (preferredFailed) return null
        val services = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrNull().orEmpty()
        for (pkg in PREFERRED_PACKAGES) {
            services.firstOrNull { it.serviceInfo?.packageName == pkg }?.serviceInfo?.let {
                return ComponentName(it.packageName, it.name)
            }
        }
        return null
    }

    fun start(language: String, listener: Listener) {
        stop()
        val token = ++generation
        val preferred = preferredService()
        val r = preferred?.let { runCatching { SpeechRecognizer.createSpeechRecognizer(context, it) }.getOrNull() }
            ?: SpeechRecognizer.createSpeechRecognizer(context)
        val usingPreferred = preferred != null
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private var done = false
            private fun live() = token == generation && !done

            override fun onReadyForSpeech(params: Bundle?) { if (live()) listener.onReady() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { if (live()) listener.onLevel(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!live()) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                done = true
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                if (list.isEmpty()) listener.onError(SpeechRecognizer.ERROR_NO_MATCH, describe(SpeechRecognizer.ERROR_NO_MATCH))
                else listener.onResults(list)
            }

            override fun onError(error: Int) {
                if (!live()) return
                done = true
                if (usingPreferred && error in SERVICE_ERRORS) preferredFailed = true   // use the default next time
                listener.onError(error, describe(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    fun stop() {
        generation++
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    companion object {
        private val PREFERRED_PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

        /** Errors that suggest the chosen recognition service itself doesn't work here. */
        private val SERVICE_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )

        @Volatile
        private var preferredFailed = false

        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that - try again, a little louder."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the mic and speak."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs the network (or the offline Chinese pack)."
            SpeechRecognizer.ERROR_AUDIO -> "Microphone problem."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy - try again."
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service error - try again."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "Chinese speech recognition isn't installed. Type the pinyin instead."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests - wait a moment."
            else -> "Speech recognition error ($code) - try again."
        }
    }
}
