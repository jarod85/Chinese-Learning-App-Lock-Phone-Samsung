package com.hanzilock.ui.quiz

import android.app.Application
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanzilock.HanziLockApp
import com.hanzilock.core.LanguageProfile
import com.hanzilock.data.Example
import com.hanzilock.data.QuizPart
import com.hanzilock.data.Word
import com.hanzilock.quiz.ActiveSession
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.JapaneseKana
import com.hanzilock.quiz.MeaningMatcher
import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.Pronunciation
import com.hanzilock.quiz.SentenceTiles
import com.hanzilock.quiz.SessionKind
import com.hanzilock.quiz.SessionManager
import com.hanzilock.quiz.SpeechMatcher
import com.hanzilock.quiz.WordOutcome
import com.hanzilock.speech.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One word = three checks, in order: say it (speech recognition in the word's language, or typed
 * pinyin / kana), give the English meaning, use it in a sentence. The first miss ends the word as
 * a loss (it's logged and the quiz moves on to another word); passing all three is a win.
 */
class QuizViewModel(application: Application) : AndroidViewModel(application) {
    enum class Step { PRONUNCIATION, MEANING, SENTENCE, RESULT }
    enum class Phase { LOADING, QUIZ, SUMMARY, NO_WORDS, CLOSED }
    enum class SentenceMode { WRITE_AI, TILES, WRITE_OFFLINE }

    data class ResultUi(
        val win: Boolean,
        val failedPart: QuizPart?,
        val message: String?,
        val feedback: String?,
        val corrected: String?,
        val translation: String?,
        val canOverrideMeaning: Boolean,
    )

    data class SummaryUi(val wins: Int, val losses: Int, val unlocked: Boolean, val nextResetAt: Long?)

    data class Ui(
        val phase: Phase = Phase.LOADING,
        val kind: SessionKind = SessionKind.PRACTICE,
        val session: ActiveSession? = null,
        val word: Word? = null,
        val language: LanguageProfile? = null,
        val step: Step = Step.PRONUNCIATION,
        val passed: Set<Step> = emptySet(),
        // pronunciation
        val typedReading: Boolean = false,
        val speechAvailable: Boolean = true,
        val canSkipPronunciation: Boolean = false,
        val listening: Boolean = false,
        val level: Float = 0f,
        val partial: String = "",
        val speechTriesLeft: Int = 2,
        val readingInput: String = "",
        // meaning
        val meaningInput: String = "",
        // sentence
        val sentenceMode: SentenceMode = SentenceMode.WRITE_OFFLINE,
        val sentenceInput: String = "",
        val tiles: List<String> = emptyList(),
        val pool: List<Int> = emptyList(),
        val picked: List<Int> = emptyList(),
        val tileExample: Example? = null,
        // shared
        val busy: Boolean = false,
        val note: String? = null,
        val result: ResultUi? = null,
        val summary: SummaryUi? = null,
    )

    private val app = application as HanziLockApp
    private val speech = SpeechInput(application)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private var started = false
    private var registryReadings: Map<String, Set<String>>? = null

    // What was answered for the current word (logged with the attempt).
    private var heard: String? = null
    private var meaningAnswer: String? = null
    private var sentenceAnswer: String? = null
    private var gradedBy = "offline"

    private fun profileOf(word: Word): LanguageProfile = app.languages.get(word.lang)

    /** Loads (or resumes) a session; does nothing while one is already on screen. */
    fun start(kind: SessionKind) {
        val phase = _ui.value.phase
        if (started && (phase == Phase.LOADING || phase == Phase.QUIZ)) return
        started = true
        _ui.value = Ui(phase = Phase.LOADING, kind = kind)
        viewModelScope.launch { loadSession(kind) }
    }

    private suspend fun loadSession(kind: SessionKind) {
        app.ready.first { it }
        when (val start = withContext(Dispatchers.IO) { app.sessions.startOrResume(kind) }) {
            is SessionManager.Start.Running -> showWord(start.session)
            SessionManager.Start.NoWords -> {
                // Nothing to practice: don't hold the phone hostage.
                if (kind == SessionKind.LOCK) app.lockEngine.markCompleted()
                _ui.update { it.copy(phase = Phase.NO_WORDS) }
            }
            SessionManager.Start.NotNeeded -> _ui.update { it.copy(phase = Phase.CLOSED) }
        }
    }

    private suspend fun showWord(session: ActiveSession) {
        var s = session
        while (true) {
            if (s.isComplete) {
                finish(s)
                return
            }
            val id = s.currentWordId ?: run { finish(s); return }
            val word = withContext(Dispatchers.IO) { app.words.get(id) }
            if (word != null) {
                heard = null; meaningAnswer = null; sentenceAnswer = null; gradedBy = "offline"
                val profile = profileOf(word)
                val speechOk = speech.isAvailable
                _ui.value = Ui(
                    phase = Phase.QUIZ,
                    kind = _ui.value.kind,
                    session = s,
                    word = word,
                    language = profile,
                    step = Step.PRONUNCIATION,
                    typedReading = profile.canTypeReading && (app.settings.preferTypedPinyin || !speechOk),
                    speechAvailable = speechOk,
                    canSkipPronunciation = !profile.canTypeReading && app.settings.allowSkipPronunciation,
                    speechTriesLeft = app.settings.speechAttempts,
                )
                return
            }
            s = withContext(Dispatchers.IO) { app.sessions.skipCurrent() } ?: run {
                _ui.update { it.copy(phase = Phase.CLOSED) }
                return
            }
        }
    }

    private suspend fun finish(session: ActiveSession) {
        withContext(Dispatchers.IO) { app.sessions.finish("completed") }
        _ui.update {
            it.copy(
                phase = Phase.SUMMARY,
                summary = SummaryUi(
                    wins = session.wins,
                    losses = session.losses,
                    unlocked = session.kind == SessionKind.LOCK,
                    nextResetAt = app.lockEngine.nextResetAt(),
                ),
            )
        }
    }

    /**
     * Called when the lock screen comes back to the front: close it if nothing is due any more,
     * or deal a new session if an old summary is showing and the next one is already due.
     */
    fun onHostResume() {
        val u = _ui.value
        if (u.kind != SessionKind.LOCK || u.phase == Phase.LOADING) return
        val due = app.lockEngine.isLockDue()
        val running = app.sessions.active.value != null
        when (u.phase) {
            Phase.QUIZ -> if (!running && !due) _ui.update { it.copy(phase = Phase.CLOSED) }
            Phase.SUMMARY, Phase.NO_WORDS -> if (due) {
                _ui.value = Ui(phase = Phase.LOADING, kind = SessionKind.LOCK)
                viewModelScope.launch { loadSession(SessionKind.LOCK) }
            }
            else -> Unit
        }
    }

    // ---- step 1: pronunciation -------------------------------------------------------------------

    fun setTypedReading(typed: Boolean) {
        speech.stop()
        _ui.update { it.copy(typedReading = typed, listening = false, note = null) }
    }

    fun onReadingInput(text: String) = _ui.update { it.copy(readingInput = text) }

    fun toggleListening() {
        val word = _ui.value.word ?: return
        if (_ui.value.listening) {
            speech.stop()
            _ui.update { it.copy(listening = false, level = 0f) }
            return
        }
        _ui.update { it.copy(listening = true, partial = "", note = null, level = 0f) }
        speech.start(profileOf(word).locale, object : SpeechInput.Listener {
            override fun onPartial(text: String) = _ui.update { it.copy(partial = text) }
            override fun onLevel(rmsDb: Float) = _ui.update { it.copy(level = rmsDb) }
            override fun onResults(candidates: List<String>) {
                _ui.update { it.copy(listening = false, level = 0f) }
                judgeSpeech(word, candidates)
            }
            override fun onError(code: Int, message: String) {
                val unsupported = code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                _ui.update {
                    it.copy(
                        listening = false, level = 0f, note = message,
                        typedReading = it.typedReading || (unsupported && it.language?.canTypeReading == true),
                    )
                }
            }
        })
    }

    private fun judgeSpeech(word: Word, candidates: List<String>) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                Pronunciation.match(word.lang, word.term, word.reading, word.forms, candidates, ::readingsOf)
            }
            if (_ui.value.word?.id != word.id || _ui.value.step != Step.PRONUNCIATION) return@launch
            heard = result.heard
            if (result.matched) {
                pass(Step.PRONUNCIATION, "Heard “${result.heard}” ✓")
                return@launch
            }
            val left = _ui.value.speechTriesLeft - 1
            if (left <= 0) {
                fail(QuizPart.PRONUNCIATION, "We heard “${result.heard}”.")
            } else {
                _ui.update { it.copy(speechTriesLeft = left, note = "Heard “${result.heard}” - not quite. $left more ${if (left == 1) "try" else "tries"}.") }
            }
        }
    }

    private fun readingsOf(ch: String): Set<String> {
        val fromDictionary = app.dictionary.readings(ch)
        if (fromDictionary.isNotEmpty()) return fromDictionary
        val map = registryReadings ?: buildRegistryReadings().also { registryReadings = it }
        return map[ch].orEmpty()
    }

    /** Character readings taken from your Chinese words, used until the dictionary is imported. */
    private fun buildRegistryReadings(): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        for (w in app.words.list("zh", limit = Int.MAX_VALUE)) {
            val chars = w.term.codePoints().toArray().map { String(Character.toChars(it)) }
            val syllables = Pinyin.syllables(w.reading)
            if (chars.size != syllables.size) continue
            chars.zip(syllables).forEach { (c, s) -> map.getOrPut(c) { HashSet() }.add(s) }
        }
        return map
    }

    /** Typed pinyin (Chinese) or kana / romaji (Japanese). */
    fun submitReading() {
        val word = _ui.value.word ?: return
        val input = _ui.value.readingInput.trim()
        if (input.isEmpty()) return
        heard = "typed: $input"
        val strict = app.settings.requireTones
        val ok = when (word.lang) {
            "ja" -> JapaneseKana.readingMatches(input, word.reading.ifEmpty { word.term }, strict)
            // For Chinese, forms hold other accepted pinyin (the official HSK one, e.g. zhīdao for 知道).
            else -> Pinyin.typedMatchesAny(input, listOf(word.reading) + word.forms.filter(Pinyin::looksLikePinyin), word.term, strict)
        }
        if (ok) pass(Step.PRONUNCIATION, "✓ ${word.readingDisplay}") else fail(QuizPart.PRONUNCIATION, "You typed “$input”.")
    }

    /** "Can't talk right now" for languages without a typed-reading check: logged, not scored. */
    fun skipPronunciation() {
        speech.stop()
        heard = "(pronunciation skipped)"
        pass(Step.PRONUNCIATION, "Pronunciation skipped - say it out loud next time.")
    }

    // ---- step 2: meaning ---------------------------------------------------------------------------

    fun onMeaningInput(text: String) = _ui.update { it.copy(meaningInput = text) }

    fun submitMeaning() {
        val word = _ui.value.word ?: return
        val answer = _ui.value.meaningInput.trim()
        if (answer.isEmpty() || _ui.value.busy) return
        meaningAnswer = answer
        if (MeaningMatcher.matches(answer, word.acceptedMeanings)) {
            pass(Step.MEANING, "✓ ${word.meanings.take(3).joinToString("; ")}")
            return
        }
        val grader = app.graderOrNull()
        if (grader == null) {
            fail(QuizPart.MEANING, "“$answer” isn't one of the meanings.", canOverride = app.settings.allowMeaningOverride)
            return
        }
        _ui.update { it.copy(busy = true, note = "Asking Claude…") }
        viewModelScope.launch {
            val verdict = withContext(Dispatchers.IO) { runCatching { grader.checkMeaning(info(word), answer) } }
            if (_ui.value.word?.id != word.id) return@launch
            _ui.update { it.copy(busy = false, note = null) }
            verdict.onSuccess { v ->
                gradedBy = "claude"
                if (v.correct) pass(Step.MEANING, "✓ ${v.feedback}") else fail(QuizPart.MEANING, v.feedback)
            }.onFailure { e ->
                fail(
                    QuizPart.MEANING,
                    "“$answer” isn't one of the meanings. (${e.message ?: "Claude unavailable"})",
                    canOverride = app.settings.allowMeaningOverride,
                )
            }
        }
    }

    /** "I was right": accept the answer, remember it for next time, and carry on with the word. */
    fun overrideMeaning() {
        val word = _ui.value.word ?: return
        val answer = meaningAnswer ?: return
        gradedBy = "self"
        viewModelScope.launch(Dispatchers.IO) { app.words.addAltMeaning(word.id, answer) }
        _ui.update { it.copy(step = Step.MEANING, result = null) }
        pass(Step.MEANING, "Accepted “$answer” - it will count next time too.")
    }

    // ---- step 3: sentence ----------------------------------------------------------------------------

    private fun prepareSentence(word: Word) {
        val example = word.examples.randomOrNull()
        when {
            app.graderOrNull() != null -> _ui.update { it.copy(sentenceMode = SentenceMode.WRITE_AI) }
            example != null -> setupTiles(word, example, note = null)
            else -> _ui.update { it.copy(sentenceMode = SentenceMode.WRITE_OFFLINE) }
        }
    }

    private fun setupTiles(word: Word, example: Example, note: String?) {
        viewModelScope.launch {
            val tokens = withContext(Dispatchers.IO) {
                SentenceTiles.tokens(example.text, word.term, japanese = word.lang == "ja") {
                    word.lang == "zh" && app.dictionary.isWord(it)
                }
            }
            _ui.update {
                it.copy(
                    sentenceMode = SentenceMode.TILES,
                    tiles = tokens,
                    pool = SentenceTiles.shuffledOrder(tokens.size),
                    picked = emptyList(),
                    tileExample = example,
                    busy = false,
                    note = note,
                )
            }
        }
    }

    fun onSentenceInput(text: String) = _ui.update { it.copy(sentenceInput = text) }

    /** Speak the sentence instead of typing it (dictation in the word's language fills the text box). */
    fun toggleDictation() {
        val word = _ui.value.word ?: return
        if (_ui.value.listening) {
            speech.stop()
            _ui.update { it.copy(listening = false, level = 0f) }
            return
        }
        _ui.update { it.copy(listening = true, partial = "", note = null) }
        speech.start(profileOf(word).locale, object : SpeechInput.Listener {
            override fun onPartial(text: String) = _ui.update { it.copy(partial = text) }
            override fun onLevel(rmsDb: Float) = _ui.update { it.copy(level = rmsDb) }
            override fun onResults(candidates: List<String>) =
                _ui.update { it.copy(listening = false, level = 0f, partial = "", sentenceInput = candidates.first()) }
            override fun onError(code: Int, message: String) =
                _ui.update { it.copy(listening = false, level = 0f, partial = "", note = message) }
        })
    }

    fun submitSentence() {
        val word = _ui.value.word ?: return
        val sentence = _ui.value.sentenceInput.trim()
        if (sentence.isEmpty() || _ui.value.busy) return
        sentenceAnswer = sentence
        if (_ui.value.sentenceMode == SentenceMode.WRITE_OFFLINE) {
            judgeOffline(word, sentence, prefix = null)
            return
        }
        val grader = app.graderOrNull(requireNetwork = false)
        if (grader == null) {
            judgeOffline(word, sentence, prefix = "AI grading is off.")
            return
        }
        _ui.update { it.copy(busy = true, note = "Claude is checking your sentence…") }
        viewModelScope.launch {
            val verdict = withContext(Dispatchers.IO) { runCatching { grader.gradeSentence(info(word), sentence) } }
            if (_ui.value.word?.id != word.id) return@launch
            _ui.update { it.copy(busy = false, note = null) }
            verdict.onSuccess { v ->
                gradedBy = "claude"
                if (v.correct && v.usesTargetWord) {
                    win(feedback = v.feedback, corrected = v.correctedSentence, translation = v.translation)
                } else {
                    fail(QuizPart.SENTENCE, null, feedback = v.feedback, corrected = v.correctedSentence, translation = v.translation)
                }
            }.onFailure { e ->
                val example = word.examples.randomOrNull()
                val why = e.message ?: "Claude is unavailable."
                if (example != null) {
                    sentenceAnswer = null
                    setupTiles(word, example, note = "$why Build the example sentence instead.")
                } else {
                    judgeOffline(word, sentence, prefix = why)
                }
            }
        }
    }

    /** Without AI a free sentence can only be sanity-checked: it must use the word and have more words. */
    private fun judgeOffline(word: Word, sentence: String, prefix: String?) {
        gradedBy = "offline"
        val profile = profileOf(word)
        val uses = Pronunciation.sentenceUses(word.lang, sentence, word.term, word.forms, word.reading)
        val longEnough = if (profile.spaced) {
            Pronunciation.normalize(sentence).split(' ').count { it.isNotEmpty() } >= 3
        } else {
            SpeechMatcher.hanOnly(sentence).length + sentence.count { JapaneseKana.isKana(it) } >= word.term.length + 2
        }
        val lead = prefix?.let { "$it " }.orEmpty()
        if (uses && longEnough) {
            win(feedback = "${lead}Your sentence uses ${word.term}; grammar can't be checked offline - compare it with the example.")
        } else {
            fail(QuizPart.SENTENCE, "${lead}The sentence must use ${word.term} plus a few more words.")
        }
    }

    fun pickTile(index: Int) = _ui.update { if (index in it.pool) it.copy(pool = it.pool - index, picked = it.picked + index) else it }

    fun unpickTile(index: Int) = _ui.update { if (index in it.picked) it.copy(picked = it.picked - index, pool = it.pool + index) else it }

    fun checkTiles() {
        val u = _ui.value
        if (u.picked.size != u.tiles.size) return
        val spaced = u.language?.spaced == true
        sentenceAnswer = u.picked.joinToString(if (spaced) " " else "") { u.tiles[it] }
        gradedBy = "offline"
        if (SentenceTiles.isCorrect(u.tiles, u.picked)) {
            win(feedback = null, translation = u.tileExample?.en)
        } else {
            fail(QuizPart.SENTENCE, "Your order: $sentenceAnswer", translation = u.tileExample?.en)
        }
    }

    // ---- outcome ---------------------------------------------------------------------------------------

    private fun pass(step: Step, note: String?) {
        val word = _ui.value.word ?: return
        val next = when (step) {
            Step.PRONUNCIATION -> Step.MEANING
            Step.MEANING -> Step.SENTENCE
            else -> Step.RESULT
        }
        _ui.update { it.copy(passed = it.passed + step, step = next, note = note, busy = false, listening = false) }
        if (next == Step.SENTENCE) prepareSentence(word)
    }

    private fun win(feedback: String?, corrected: String? = null, translation: String? = null) {
        val word = _ui.value.word ?: return
        _ui.update {
            it.copy(
                passed = it.passed + Step.SENTENCE,
                step = Step.RESULT,
                busy = false,
                note = null,
                result = ResultUi(true, null, null, feedback, corrected, translation, canOverrideMeaning = false),
            )
        }
        speakWord(word)
    }

    private fun fail(
        part: QuizPart,
        message: String?,
        canOverride: Boolean = false,
        feedback: String? = null,
        corrected: String? = null,
        translation: String? = null,
    ) {
        val word = _ui.value.word ?: return
        speech.stop()
        _ui.update {
            it.copy(
                step = Step.RESULT,
                busy = false,
                listening = false,
                note = null,
                result = ResultUi(false, part, message, feedback, corrected, translation, canOverride && part == QuizPart.MEANING),
            )
        }
        speakWord(word)
    }

    /** Records the word's result and deals the next word (or ends the session). */
    fun next() {
        val u = _ui.value
        val word = u.word ?: return
        val result = u.result ?: return
        if (u.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val feedback = listOfNotNull(result.message, result.feedback).joinToString(" ").ifBlank { null }
            val session = withContext(Dispatchers.IO) {
                app.sessions.record(
                    WordOutcome(word.id, result.win, result.failedPart, heard, meaningAnswer, sentenceAnswer, feedback, gradedBy),
                )
            }
            if (session == null) {
                _ui.update { it.copy(phase = Phase.CLOSED) }
            } else {
                showWord(session)
            }
        }
    }

    // ---- escapes ---------------------------------------------------------------------------------------

    fun skipWithPin() {
        speech.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.sessions.skipWithPin() }
            _ui.update { it.copy(phase = Phase.CLOSED) }
        }
    }

    fun pauseLock(minutes: Int) {
        speech.stop()
        app.lockEngine.pauseUntil(System.currentTimeMillis() + minutes * 60_000L)
        _ui.update { it.copy(phase = Phase.CLOSED) }
    }

    fun leavePractice() {
        speech.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.sessions.abandonPractice() }
            _ui.update { it.copy(phase = Phase.CLOSED) }
        }
    }

    private fun speakWord(word: Word) = app.speaker.speak(word.term, profileOf(word).javaLocale, slow = true)

    /** Speaks a word or sentence in the current word's language. */
    fun speak(text: String, slow: Boolean = false) {
        val profile = _ui.value.language ?: app.languages.active
        app.speaker.speak(SentenceTiles.display(text, profile.spaced), profile.javaLocale, slow)
    }

    private fun info(word: Word) = ClaudeGrader.WordInfo(word.term, word.readingDisplay, word.meanings, profileOf(word).name)

    override fun onCleared() {
        speech.stop()
        super.onCleared()
    }
}
