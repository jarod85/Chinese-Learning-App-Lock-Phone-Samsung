package com.hanzilock.ui.learn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanzilock.HanziLockApp
import com.hanzilock.core.LanguageProfile
import com.hanzilock.data.Word
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.SentenceTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Learning mode for one word, guided by Claude and written only in the language being learned:
 * what the word means, how it's used with example sentences, then your own sentences, which
 * Claude corrects. Nothing here is graded or changes the word's review schedule.
 */
class LearnViewModel(application: Application) : AndroidViewModel(application) {
    /** One of your sentences and Claude's review of it. */
    data class Answer(val sentence: String, val review: ClaudeGrader.SentenceReview)

    data class Ui(
        val word: Word? = null,
        val language: LanguageProfile? = null,
        val lesson: ClaudeGrader.Lesson? = null,
        val loading: Boolean = true,
        /** Why the lesson couldn't be loaded. */
        val error: String? = null,
        val input: String = "",
        val checking: Boolean = false,
        /** Why the last sentence couldn't be checked (you can try again). */
        val checkError: String? = null,
        val answers: List<Answer> = emptyList(),
        val showReading: Boolean = true,
    ) {
        val right: Int get() = answers.count { it.review.correct }
        val done: Boolean get() = answers.size >= SENTENCES
    }

    private val app = application as HanziLockApp
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private var wordId: Long? = null

    fun start(id: Long) {
        if (wordId == id) return
        wordId = id
        _ui.value = Ui()
        viewModelScope.launch { loadLesson() }
    }

    fun retry() {
        viewModelScope.launch { loadLesson() }
    }

    private suspend fun loadLesson() {
        val id = wordId ?: return
        _ui.update { it.copy(loading = true, error = null) }
        val word = withContext(Dispatchers.IO) { app.words.get(id) }
        if (word == null) {
            _ui.update { it.copy(loading = false, error = "This word no longer exists.") }
            return
        }
        val profile = app.languages.get(word.lang)
        _ui.update { it.copy(word = word, language = profile) }
        val grader = app.graderOrNull() ?: run {
            _ui.update { it.copy(loading = false, error = NO_CLAUDE) }
            return
        }
        val r = withContext(Dispatchers.IO) { runCatching { grader.teachWord(info(word, profile)) } }
        _ui.update { it.copy(loading = false, lesson = r.getOrNull(), error = r.exceptionOrNull()?.let(::describe)) }
    }

    fun onInput(text: String) = _ui.update { it.copy(input = text, checkError = null) }

    fun toggleReading() = _ui.update { it.copy(showReading = !it.showReading) }

    /** Sends your sentence to Claude. Right or wrong, it counts towards the lesson's sentences. */
    fun submit() {
        val s = _ui.value
        val word = s.word ?: return
        val profile = s.language ?: return
        val sentence = s.input.trim()
        if (sentence.isEmpty() || s.checking) return
        _ui.update { it.copy(checking = true, checkError = null) }
        viewModelScope.launch {
            val grader = app.graderOrNull()
            val r = if (grader == null) {
                Result.failure(ClaudeGrader.GraderException(NO_CLAUDE))
            } else {
                withContext(Dispatchers.IO) { runCatching { grader.reviewSentence(info(word, profile), sentence) } }
            }
            _ui.update { u ->
                r.fold(
                    { review -> u.copy(checking = false, input = "", answers = u.answers + Answer(sentence, review)) },
                    { e -> u.copy(checking = false, checkError = describe(e)) },
                )
            }
        }
    }

    fun speak(text: String, slow: Boolean = false) {
        val profile = _ui.value.language ?: return
        app.speaker.speak(SentenceTiles.display(text, profile.spaced), profile.javaLocale, slow)
    }

    private fun info(word: Word, profile: LanguageProfile) =
        ClaudeGrader.WordInfo(word.term, word.readingDisplay, word.meanings, profile.name)

    private fun describe(e: Throwable): String = e.message ?: "Claude couldn't answer. Try again."

    companion object {
        /** How many of your own sentences a lesson asks for. */
        const val SENTENCES = 3
        const val NO_CLAUDE = "Learning mode is guided by Claude: add an Anthropic API key in Settings > AI grading " +
            "(not set to \"Always offline\") and be online."
    }
}
