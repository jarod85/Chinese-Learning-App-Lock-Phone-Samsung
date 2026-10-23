package com.hanzilock.ui.tutor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanzilock.AppCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The Chinese tutor docked at the bottom of the app: a conversation with Claude, which you can
 * write to in any language and which answers only in Chinese. The conversation lasts while the
 * app is open; nothing is saved.
 */
class TutorViewModel(private val app: AppCore) : ViewModel() {
    /** One message of the conversation: yours or the tutor's. */
    data class Message(val fromLearner: Boolean, val text: String)

    data class Ui(
        /** The panel is open (otherwise only its bar shows). */
        val open: Boolean = false,
        val messages: List<Message> = emptyList(),
        val input: String = "",
        /** The tutor is answering: its reply so far is the last message (empty until the first words arrive). */
        val answering: Boolean = false,
        /** Why the last question couldn't be answered (it is back in the input, to send again). */
        val error: String? = null,
    ) {
        /** The tutor's latest reply, shown on the closed bar. */
        val lastReply: String? get() = messages.lastOrNull { !it.fromLearner && it.text.isNotBlank() }?.text
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var answer: Job? = null

    /** Goes up with every new conversation, so a reply still arriving for the old one is dropped. */
    @Volatile private var conversation = 0

    fun open() = _ui.update { it.copy(open = true) }

    fun close() = _ui.update { it.copy(open = false) }

    fun toggle() = _ui.update { it.copy(open = !it.open) }

    fun onInput(text: String) = _ui.update { it.copy(input = text, error = null) }

    /** Starts over (stopping an answer that's still coming). */
    fun newConversation() {
        conversation++
        answer?.cancel()
        app.speaker.stop()
        _ui.update { Ui(open = it.open) }
    }

    /** Asks [text] (what you typed, by default). The reply appears word by word as Claude writes it. */
    fun send(text: String = _ui.value.input) {
        val question = text.trim()
        val before = _ui.value
        if (question.isEmpty() || before.answering) return
        val current = conversation
        val asked = before.messages + Message(fromLearner = true, text = question)
        _ui.update { it.copy(messages = asked + Message(fromLearner = false, text = ""), input = "", answering = true, error = null) }
        answer = viewModelScope.launch {
            val grader = app.graderOrNull()
            val error = if (grader == null) {
                NO_CLAUDE
            } else {
                try {
                    val reply = withContext(Dispatchers.IO) {
                        grader.tutorReply(asked.map { it.text }) { soFar ->
                            ensureActive()   // stops reading the reply after "New conversation"
                            if (current == conversation) _ui.update { it.copy(messages = asked + Message(fromLearner = false, text = soFar)) }
                        }
                    }
                    if (current == conversation) _ui.update { it.copy(messages = asked + Message(fromLearner = false, text = reply), answering = false) }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "Claude couldn't answer. Try again."
                }
            }
            // Leave the question out of the conversation (a part-written reply too) and give it back to send again.
            if (error != null && current == conversation) _ui.update { it.copy(messages = before.messages, input = question, answering = false, error = error) }
        }
    }

    fun speak(text: String) = app.speaker.speak(text, Locale.SIMPLIFIED_CHINESE)

    companion object {
        /** Questions to start with, shown while the conversation is empty. */
        val SUGGESTIONS = listOf(
            "“主要”在“你理解了故事的主要内容”里是什么意思？",
            "What's the difference between 了 and 过?",
            "这个句子对吗：我昨天去了商店买东西。",
        )

        const val NO_CLAUDE = "The tutor is Claude: add an Anthropic API key in Settings > AI grading " +
            "(not set to \"Always offline\") and be online."

    }
}
