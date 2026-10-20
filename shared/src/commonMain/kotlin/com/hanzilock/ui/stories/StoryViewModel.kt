package com.hanzilock.ui.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanzilock.AppCore
import com.hanzilock.data.DictEntry
import com.hanzilock.speech.SpeechInput
import com.hanzilock.stories.Story
import com.hanzilock.stories.StoryAnswerReview
import com.hanzilock.stories.StoryImporter
import com.hanzilock.stories.StoryText
import com.hanzilock.stories.reviewStoryAnswer
import com.hanzilock.stories.storyQuestions
import com.hanzilock.stories.translateSentences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reading one story: its pages, looking up a word, the English translation of a page (Claude,
 * fetched the first time it is shown), reading aloud sentence by sentence, and the comprehension
 * questions and explanation at the end, which Claude gives feedback on.
 */
class StoryViewModel(private val app: AppCore) : ViewModel() {
    /** A word you can look up: where it is in its [Block]'s text. */
    data class WordSpan(val start: Int, val end: Int, val word: String)

    /** A paragraph (or the part of one) on a page; [sentences] = sentence index to its characters. */
    data class Block(val text: String, val words: List<WordSpan>, val sentences: List<Pair<Int, IntRange>>)

    data class Page(val sentences: IntRange, val blocks: List<Block>)

    /** The word you pressed and what the dictionary says about it. */
    data class Lookup(
        val page: Int,
        val block: Int,
        val span: WordSpan,
        /** Show the card at the top of the page (the word is in the lower half). */
        val atTop: Boolean,
        val loading: Boolean = true,
        val entries: List<DictEntry> = emptyList(),
        /** Character by character, when the word itself isn't in the dictionary. */
        val characters: List<DictEntry> = emptyList(),
    )

    data class Ui(
        val story: Story? = null,
        val pages: List<Page> = emptyList(),
        /** Page of each sentence. */
        val sentencePage: List<Int> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val showEnglish: Boolean = false,
        val translating: Boolean = false,
        val translateError: String? = null,
        val lookup: Lookup? = null,
        val reading: Boolean = false,
        /** Sentence being read aloud (or where reading stopped), -1 for none. */
        val readingSentence: Int = -1,
        val slow: Boolean = false,
        /** A short message, e.g. that no Chinese voice is installed. */
        val note: String? = null,
        val questionsLoading: Boolean = false,
        val questionsError: String? = null,
        /** Your answers to the questions, and Claude's feedback on them. */
        val answers: List<String> = emptyList(),
        val reviews: List<StoryAnswerReview?> = emptyList(),
        /** Your explanation of the whole story in Chinese, and the feedback on it. */
        val explanation: String = "",
        val explanationReview: StoryAnswerReview? = null,
        /** Answer being checked: a question's index, [EXPLANATION], or null. */
        val checking: Int? = null,
        val checkError: Pair<Int, String>? = null,
        /** Answer being dictated: a question's index, [EXPLANATION], or null. */
        val listening: Int? = null,
        val partial: String = "",
    ) {
        val allReviewed: Boolean
            get() = explanationReview != null && reviews.isNotEmpty() && reviews.all { it != null }
    }

    private val _ui = MutableStateFlow(Ui(slow = app.settings.storySlow))
    val ui: StateFlow<Ui> = _ui
    private var storyId: Long? = null
    private var lookupJob: Job? = null
    private val speech = app.newSpeechInput()
    private val zh get() = app.languages.get("zh").javaLocale

    init {
        // Follow the sentence being read aloud; reading ends when the voice goes quiet.
        viewModelScope.launch {
            app.speaker.utterance.collect { id ->
                val prefix = "story:${storyId}:"
                val index = id?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
                if (index != null) {
                    _ui.update { it.copy(reading = true, readingSentence = index) }
                } else if (_ui.value.reading) {
                    _ui.update {
                        val last = it.story?.body?.chunks?.lastIndex ?: -1
                        it.copy(reading = false, readingSentence = if (it.readingSentence == last) -1 else it.readingSentence)
                    }
                }
            }
        }
    }

    fun start(id: Long) {
        if (storyId == id) return
        storyId = id
        viewModelScope.launch {
            val story = withContext(Dispatchers.IO) { app.stories.get(id) }
            if (story == null) {
                _ui.update { it.copy(loading = false, error = "This story no longer exists.") }
                return@launch
            }
            val (pages, sentencePage) = withContext(Dispatchers.Default) { layout(story) }
            _ui.update {
                it.copy(
                    story = story,
                    pages = pages,
                    sentencePage = sentencePage,
                    loading = false,
                    answers = List(story.body.questions.size) { "" },
                    reviews = List(story.body.questions.size) { null },
                )
            }
        }
    }

    private fun layout(story: Story): Pair<List<Page>, List<Int>> {
        val chunks = story.body.chunks
        val sentencePage = MutableList(chunks.size) { 0 }
        val pages = StoryText.paginate(chunks).mapIndexed { pageIndex, range ->
            val blocks = ArrayList<Block>()
            var text = StringBuilder()
            var words = ArrayList<WordSpan>()
            var sentences = ArrayList<Pair<Int, IntRange>>()
            fun flush() {
                if (text.isNotEmpty()) blocks += Block(text.toString(), words, sentences)
                text = StringBuilder()
                words = ArrayList()
                sentences = ArrayList()
            }
            for (i in range) {
                sentencePage[i] = pageIndex
                val chunk = chunks[i]
                if (chunk.p) flush()
                val tokens = chunk.tokens
                val joined = StoryText.layout(tokens)
                val base = text.length
                tokens.forEachIndexed { t, word ->
                    if (StoryText.hasHan(word)) words += WordSpan(base + joined.starts[t], base + joined.starts[t] + word.length, word)
                }
                text.append(joined.text)
                sentences += i to (base until text.length)
            }
            flush()
            Page(range, blocks)
        }
        return pages to sentencePage
    }

    fun onPage(page: Int) {
        val story = _ui.value.story ?: return
        if (_ui.value.lookup?.page != page) dismissLookup()
        if (page == story.page) return
        _ui.update { it.copy(story = story.copy(page = page)) }
        viewModelScope.launch(Dispatchers.IO) { app.stories.savePage(story.id, page) }
    }

    // ---- words ------------------------------------------------------------------------------------

    fun press(page: Int, block: Int, word: Int, atTop: Boolean) {
        val span = _ui.value.pages.getOrNull(page)?.blocks?.getOrNull(block)?.words?.getOrNull(word) ?: return
        lookupJob?.cancel()
        _ui.update { it.copy(lookup = Lookup(page, block, span, atTop)) }
        lookupJob = viewModelScope.launch {
            val (entries, characters) = withContext(Dispatchers.IO) {
                val entries = app.dictionary.lookup(span.word)
                val characters = if (entries.isEmpty() && span.word.length > 1) {
                    span.word.map { app.dictionary.lookup(it.toString()).firstOrNull() }.filterNotNull()
                } else emptyList()
                entries.take(4) to characters
            }
            _ui.update { u -> if (u.lookup?.span == span) u.copy(lookup = u.lookup.copy(loading = false, entries = entries, characters = characters)) else u }
        }
    }

    fun dismissLookup() {
        lookupJob?.cancel()
        if (_ui.value.lookup != null) _ui.update { it.copy(lookup = null) }
    }

    fun speakWord(word: String) = app.speaker.speak(word, zh, slow = true)

    fun speak(text: String) = app.speaker.speak(text, zh)

    // ---- English translation ---------------------------------------------------------------------

    fun toggleEnglish() = _ui.update { it.copy(showEnglish = !it.showEnglish, translateError = null) }

    /** Claude translates the sentences of [page] (and the next page) that have no translation yet. */
    fun ensureTranslated(page: Int) {
        val s = _ui.value
        val story = s.story ?: return
        if (s.translating) return
        val missing = (page..page + 1).flatMap { s.pages.getOrNull(it)?.sentences ?: IntRange.EMPTY }
            .filter { story.body.chunks[it].en.isBlank() }
        if (missing.isEmpty()) return
        val grader = app.graderOrNull()
        if (grader == null) {
            _ui.update { it.copy(translateError = "Translations come from Claude: ${StoryImporter.NO_CLAUDE.substringAfter(": ")}") }
            return
        }
        _ui.update { it.copy(translating = true, translateError = null) }
        viewModelScope.launch {
            val texts = missing.map { StoryText.join(story.body.chunks[it].tokens) }
            val r = withContext(Dispatchers.IO) { runCatching { grader.translateSentences(story.title, texts) } }
            val current = _ui.value.story
            if (current == null || current.id != story.id) return@launch
            r.onSuccess { translations ->
                val chunks = current.body.chunks.toMutableList()
                missing.forEachIndexed { k, i ->
                    translations.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { chunks[i] = chunks[i].copy(en = it) }
                }
                val body = current.body.copy(chunks = chunks)
                withContext(Dispatchers.IO) { app.stories.saveBody(current.id, body) }
                _ui.update { it.copy(story = current.copy(body = body), translating = false) }
            }.onFailure { e ->
                _ui.update { it.copy(translating = false, translateError = e.message ?: "Couldn't translate. Tap to try again.") }
            }
        }
    }

    // ---- reading aloud ---------------------------------------------------------------------------

    /** Reads from the start of [page] to the end of the story (or on from where it was paused on that page). */
    fun play(page: Int) {
        val s = _ui.value
        val story = s.story ?: return
        val range = s.pages.getOrNull(page)?.sentences ?: return
        if (!app.speaker.hasVoice(zh)) {
            _ui.update {
                it.copy(note = "No Chinese voice is installed: Settings > General management > Text-to-speech > ⚙ > Install voice data.")
            }
            return
        }
        val from = s.readingSentence.takeIf { it in range } ?: range.first
        val parts = (from..story.body.chunks.lastIndex).map { "story:${story.id}:$it" to StoryText.join(story.body.chunks[it].tokens) }
        stopDictation()
        _ui.update { it.copy(reading = true, readingSentence = from, note = null) }
        app.speaker.speakAll(parts, zh, if (s.slow) 0.6f else 0.9f)
    }

    fun stopReading() {
        if (!_ui.value.reading) return
        _ui.update { it.copy(reading = false) }
        app.speaker.stop()
    }

    fun toggleSlow() {
        val slow = !_ui.value.slow
        app.settings.storySlow = slow
        val wasReading = _ui.value.reading
        val page = _ui.value.readingSentence.let { _ui.value.sentencePage.getOrNull(it) }
        _ui.update { it.copy(slow = slow) }
        if (wasReading && page != null) play(page)
    }

    fun clearNote() = _ui.update { it.copy(note = null) }

    // ---- comprehension ---------------------------------------------------------------------------

    fun loadQuestions() {
        val s = _ui.value
        val story = s.story ?: return
        if (story.body.questions.isNotEmpty() || s.questionsLoading) return
        val grader = app.graderOrNull()
        if (grader == null) {
            _ui.update { it.copy(questionsError = "The questions come from Claude: ${StoryImporter.NO_CLAUDE.substringAfter(": ")}") }
            return
        }
        _ui.update { it.copy(questionsLoading = true, questionsError = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { grader.storyQuestions(story.title, story.plainText, story.level) } }
            val current = _ui.value.story
            if (current == null || current.id != story.id) return@launch
            r.onSuccess { found ->
                val questions = found.filter { it.question.isNotBlank() }.take(5)
                val body = current.body.copy(questions = questions)
                withContext(Dispatchers.IO) { app.stories.saveBody(current.id, body) }
                _ui.update {
                    it.copy(
                        story = current.copy(body = body),
                        questionsLoading = false,
                        answers = List(questions.size) { "" },
                        reviews = List(questions.size) { null },
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(questionsLoading = false, questionsError = e.message ?: "Claude couldn't write the questions.") }
            }
        }
    }

    fun onAnswer(index: Int, text: String) = _ui.update {
        if (index == EXPLANATION) it.copy(explanation = text, checkError = null)
        else it.copy(answers = it.answers.toMutableList().also { a -> if (index in a.indices) a[index] = text }, checkError = null)
    }

    /** Claude checks your answer to question [index] (or your explanation, [EXPLANATION]). */
    fun check(index: Int) {
        val s = _ui.value
        val story = s.story ?: return
        val answer = (if (index == EXPLANATION) s.explanation else s.answers.getOrNull(index)).orEmpty().trim()
        if (answer.isEmpty() || s.checking != null) return
        val question = if (index == EXPLANATION) null else story.body.questions.getOrNull(index)?.question ?: return
        val grader = app.graderOrNull()
        if (grader == null) {
            _ui.update { it.copy(checkError = index to StoryImporter.NO_CLAUDE) }
            return
        }
        stopDictation()
        _ui.update { it.copy(checking = index, checkError = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { grader.reviewStoryAnswer(story.title, story.plainText, story.level, question, answer) }
            }
            _ui.update { u ->
                r.fold(
                    { review ->
                        if (index == EXPLANATION) u.copy(checking = null, explanationReview = review)
                        else u.copy(checking = null, reviews = u.reviews.toMutableList().also { if (index in it.indices) it[index] = review })
                    },
                    { e -> u.copy(checking = null, checkError = index to (e.message ?: "Claude couldn't check it. Try again.")) },
                )
            }
        }
    }

    /** Say your answer instead of typing it: what you say is added to the answer. */
    fun toggleDictation(index: Int) {
        if (_ui.value.listening == index) {
            stopDictation()
            return
        }
        stopReading()
        _ui.update { it.copy(listening = index, partial = "", checkError = null) }
        speech.start(app.languages.get("zh").locale, object : SpeechInput.Listener {
            override fun onPartial(text: String) = _ui.update { it.copy(partial = text) }
            override fun onResults(candidates: List<String>) {
                _ui.update { it.copy(listening = null, partial = "") }
                val current = if (index == EXPLANATION) _ui.value.explanation else _ui.value.answers.getOrNull(index).orEmpty()
                onAnswer(index, current + candidates.first())
            }
            override fun onError(code: Int, message: String) =
                _ui.update { it.copy(listening = null, partial = "", checkError = index to message) }
        })
    }

    private fun stopDictation() {
        if (_ui.value.listening == null) return
        speech.stop()
        _ui.update { it.copy(listening = null, partial = "") }
    }

    fun finish() {
        val story = _ui.value.story ?: return
        _ui.update { it.copy(story = story.copy(finishedAt = System.currentTimeMillis())) }
        viewModelScope.launch(Dispatchers.IO) { app.stories.markFinished(story.id) }
    }

    override fun onCleared() {
        speech.stop()
        stopReading()
    }

    companion object {
        /** Index used for the explanation of the whole story (questions are 0, 1, 2, ...). */
        const val EXPLANATION = -1
    }
}
