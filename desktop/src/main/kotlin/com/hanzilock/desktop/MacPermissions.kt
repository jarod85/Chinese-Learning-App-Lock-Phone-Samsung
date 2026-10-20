package com.hanzilock.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether macOS lets Lingo Lock use the microphone and speech recognition (asked through the helper). */
class MacPermissions(private val helper: MacHelper) {
    /** "authorized", "denied", "restricted" or "notDetermined"; null until the helper answered. */
    data class State(val microphone: String, val speech: String) {
        val granted: Boolean get() = microphone == "authorized" && speech == "authorized"
        val denied: Boolean get() = microphone in BLOCKED || speech in BLOCKED
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    init {
        helper.addListener { e ->
            if (e.eventType == "auth") _state.value = State(e.string("mic") ?: "notDetermined", e.string("speech") ?: "notDetermined")
        }
    }

    /** Asks the helper for the current answers. */
    fun refresh() = helper.send("auth", "ask" to false)

    /** Asks macOS for permission (it shows its dialogs the first time only). */
    fun request() = helper.send("auth", "ask" to true)

    private companion object {
        val BLOCKED = setOf("denied", "restricted")
    }
}
