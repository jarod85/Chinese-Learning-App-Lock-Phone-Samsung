package com.hanzilock.ui.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanzilock.AppCore
import com.hanzilock.data.DictEntry
import com.hanzilock.speech.SpeechInput
import com.hanzilock.stories.ComprehensionLevel
import com.hanzilock.stories.Story
import com.hanzilock.stories.StoryAnswerReview
import com.hanzilock.stories.StoryBody
import com.hanzilock.stories.StoryImporter
import com.hanzilock.stories.StoryQuestion
import com.hanzilock.stories.StoryText
import com.hanzilock.stories.reviewStoryAnswer
import com.hanzilock.stories.storyQuestions
import com.hanzilock.stories.translateSentences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reading one story: its pages, looking up a word (pointing at it, clicking it for its sentence's
 * translation, or pressing and holding it), searching the dictionary, the English translation of a
 * page (Claude, fetched the first time it is shown), reading aloud sentence by sentence, and the
 * comprehension questions and explanation at the end, which Claude gives feedback on.
 */
class StoryViewModel(private val app: AppCore) : ViewModel() {
    /** A word you can look up: where it is in its [Block]'s text. */
    data class WordSpan(val start: Int, val end: Int, val word: String)

    /** A paragraph (or the part of one) on a page; [sentences] = sentence index to its characters. */
    data class Block(val text: String, val words: List<WordSpan>, val sentences: List<Pair<Int, IntRange>>)

    data class Page(val sentences: IntRange, val blocks: List<Block>)

    /** A word on a page: the block it's in and where. */
    data class WordRef(val page: Int, val block: Int, val span: WordSpan)

    /** What the dictionary says about a word; character by character when it isn't one word. */
    data class Meaning(val entries: List<DictEntry>, val characters: List<DictEntry>) {
        val pinyin: String get() = entries.firstOrNull()?.pinyinMarked ?: characters.joinToString(" ") { it.pinyinMarked }
    }

    /** A dictionary entry found by the search, and the pages of the story it's on. */
    data class SearchHit(val entry: DictEntry, val pages: List<Int>)

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

    /** Kept separately for each level while the reader is open. */
    data class QuestionProgress(val answers: List<String>, val reviews: List<StoryAnswerReview?>)

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
        /** The word under the mouse pointer. */
        val hovered: WordRef? = null,
        /** The word whose meaning is shown at the top: the last one you pointed at or clicked. */
        val shown: WordRef? = null,
        /** The word you clicked (or tapped), and the sentence it's in (-1 for none). */
        val selected: WordRef? = null,
        val selectedSentence: Int = -1,
        /** Words looked up so far. */
        val meanings: Map<String, Meaning> = emptyMap(),
        /** The search panel: what you typed, what the dictionary found, and the word marked in the story. */
        val searching: Boolean = false,
        val searchQuery: String = "",
        val searchResults: List<SearchHit> = emptyList(),
        val marked: String? = null,
        /** Pages [marked] is on. */
        val markedPages: List<Int> = emptyList(),
        val reading: Boolean = false,
        /** Sentence being read aloud (or where reading stopped), -1 for none. */
        val readingSentence: Int = -1,
        val slow: Boolean = false,
        /** A short message, e.g. that no Chinese voice is installed. */
        val note: String? = null,
        val comprehensionLevel: ComprehensionLevel = ComprehensionLevel.INFERENTIAL,
        val questionLevelsLoading: Set<ComprehensionLevel> = emptySet(),
        val questionErrors: Map<ComprehensionLevel, String> = emptyMap(),
        val progressByLevel: Map<ComprehensionLevel, QuestionProgress> = emptyMap(),
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
        val questions: List<StoryQuestion> get() = story?.body?.questionsFor(comprehensionLevel).orEmpty()
        val questionsLoading: Boolean get() = comprehensionLevel in questionLevelsLoading
        val questionsError: String? get() = questionErrors[comprehensionLevel]
        val questionProgress: QuestionProgress
            get() = progressByLevel[comprehensionLevel] ?: QuestionProgress(List(questions.size) { "" }, List(questions.size) { null })
        val answers: List<String> get() = questionProgress.answers
        val reviews: List<StoryAnswerReview?> get() = questionProgress.reviews
        val allReviewed: Boolean
            get() = explanationReview != null && reviews.isNotEmpty() && reviews.all { it != null }

        fun withProgress(progress: QuestionProgress): Ui = copy(progressByLevel = progressByLevel + (comprehensionLevel to progress))
    }

    private val _ui = MutableStateFlow(Ui(slow = app.settings.storySlow, comprehensionLevel = app.settings.storyComprehensionLevel))
    val ui: StateFlow<Ui> = _ui
    private var storyId: Long? = null
    private var lookupJob: Job? = null
    private var searchJob: Job? = null
    /** Translation and question requests may finish together; merge both into the latest body. */
    private val bodyMutex = Mutex()
    private var dictationId = 0
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
        if (_ui.value.selected.let { it != null && it.page != page }) clearSelection()
        if (_ui.value.shown.let { it != null && it.page != page }) _ui.update { it.copy(hovered = null, shown = null) }
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
            val m = withContext(Dispatchers.IO) { meaningOf(span.word) }
            _ui.update { u -> if (u.lookup?.span == span) u.copy(lookup = u.lookup.copy(loading = false, entries = m.entries, characters = m.characters)) else u }
        }
    }

    fun dismissLookup() {
        lookupJob?.cancel()
        if (_ui.value.lookup != null) _ui.update { it.copy(lookup = null) }
    }

    /** The mouse pointer is on [word] of [block] (-1: on no word of it). */
    fun hover(page: Int, block: Int, word: Int) {
        val ref = wordRef(page, block, word)
        val current = _ui.value.hovered
        if (ref == current) return
        // Leaving a paragraph only ends hovering if the pointer hasn't reached a word of the next one yet.
        if (ref == null && (current == null || current.page != page || current.block != block)) return
        _ui.update { it.copy(hovered = ref, shown = ref ?: it.shown) }
        ref?.let { loadMeaning(it.span.word) }
    }

    /** Clicking a word shows what its sentence means (translated by Claude if it isn't yet); clicking it again lets go. */
    fun select(page: Int, block: Int, word: Int) {
        val ref = wordRef(page, block, word)
        if (ref == null || ref == _ui.value.selected) return clearSelection()
        val sentence = _ui.value.pages[page].blocks[block].sentences.firstOrNull { ref.span.start in it.second }?.first ?: -1
        dismissLookup()
        _ui.update { it.copy(selected = ref, selectedSentence = sentence, shown = ref) }
        loadMeaning(ref.span.word)
        if (sentence >= 0 && _ui.value.story?.body?.chunks?.getOrNull(sentence)?.en.isNullOrBlank()) ensureTranslated(page)
    }

    fun clearSelection() {
        if (_ui.value.selected != null) _ui.update { it.copy(selected = null, selectedSentence = -1) }
    }

    private fun wordRef(page: Int, block: Int, word: Int): WordRef? =
        _ui.value.pages.getOrNull(page)?.blocks?.getOrNull(block)?.words?.getOrNull(word)?.let { WordRef(page, block, it) }

    private fun loadMeaning(word: String) {
        if (word in _ui.value.meanings) return
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { meaningOf(word) }
            // Until the dictionary is ready, nothing is found: look again next time.
            if (app.dictionary.isReady) _ui.update { it.copy(meanings = it.meanings + (word to m)) }
        }
    }

    private fun meaningOf(word: String): Meaning {
        val entries = app.dictionary.lookup(word)
        val characters = if (entries.isEmpty() && word.length > 1) word.mapNotNull { app.dictionary.lookup(it.toString()).firstOrNull() } else emptyList()
        return Meaning(entries.take(4), characters)
    }

    // ---- search ----------------------------------------------------------------------------------

    fun openSearch() = _ui.update { it.copy(searching = true) }

    /** Puts the search away but keeps what you typed and the word marked in the story. */
    fun hideSearch() = _ui.update { it.copy(searching = false) }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searching = false, marked = null, markedPages = emptyList()) }
    }

    /**
     * Searches the dictionary as you type (characters, pinyin or English); words that are in the story
     * come first. Chinese you type is marked in the story too.
     */
    fun search(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        mark(query.trim().takeIf { StoryText.hasHan(it) })
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(200)
            val pages = _ui.value.pages
            val hits = withContext(Dispatchers.IO) {
                app.dictionary.search(query).map { SearchHit(it, pagesWith(pages, it.simplified)) }.sortedByDescending { it.pages.isNotEmpty() }
            }
            _ui.update { it.copy(searchResults = hits) }
        }
    }

    /** Marks [term] wherever it is in the story (null: nothing). */
    fun mark(term: String?) = _ui.update { it.copy(marked = term, markedPages = term?.let { t -> pagesWith(it.pages, t) }.orEmpty()) }

    private fun pagesWith(pages: List<Page>, term: String): List<Int> =
        pages.indices.filter { p -> pages[p].blocks.any { term in it.text } }

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
            r.onSuccess { translations ->
                updateBody(story.id) { current ->
                    val chunks = current.chunks.toMutableList()
                    missing.forEachIndexed { k, i ->
                        translations.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { chunks[i] = chunks[i].copy(en = it) }
                    }
                    current.copy(chunks = chunks)
                }
                _ui.update { it.copy(translating = false) }
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

    fun setComprehensionLevel(level: ComprehensionLevel) {
        if (level == _ui.value.comprehensionLevel || _ui.value.checking != null) return
        stopDictation()
        app.settings.storyComprehensionLevel = level
        _ui.update { it.copy(comprehensionLevel = level, checkError = null) }
        loadQuestions()
    }

    private suspend fun updateBody(id: Long, update: (StoryBody) -> StoryBody) = bodyMutex.withLock {
        val current = _ui.value.story?.takeIf { it.id == id } ?: return@withLock
        val body = update(current.body)
        withContext(Dispatchers.IO) { app.stories.saveBody(id, body) }
        _ui.update { u -> if (u.story?.id == id) u.copy(story = u.story.copy(body = body)) else u }
    }

    fun loadQuestions() {
        val s = _ui.value
        val story = s.story ?: return
        if (s.questions.isNotEmpty() || s.questionsLoading) return
        val level = s.comprehensionLevel
        val grader = app.graderOrNull()
        if (grader == null) {
            _ui.update {
                it.copy(questionErrors = it.questionErrors + (level to "The questions come from Claude: ${StoryImporter.NO_CLAUDE.substringAfter(": ")}"))
            }
            return
        }
        _ui.update { it.copy(questionLevelsLoading = it.questionLevelsLoading + level, questionErrors = it.questionErrors - level) }
        viewModelScope.launch {
            val r = runCatching {
                val questions = withContext(Dispatchers.IO) { grader.storyQuestions(story.title, story.plainText, story.level, level) }
                updateBody(story.id) { it.withQuestions(level, questions) }
            }
            if (_ui.value.story?.id != story.id) return@launch
            r.onSuccess {
                _ui.update {
                    it.copy(
                        questionLevelsLoading = it.questionLevelsLoading - level,
                        progressByLevel = it.progressByLevel - level,
                    )
                }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        questionLevelsLoading = it.questionLevelsLoading - level,
                        questionErrors = it.questionErrors + (level to (e.message ?: "Claude couldn't write the questions.")),
                    )
                }
            }
        }
    }

    fun onAnswer(index: Int, text: String) = _ui.update {
        if (index == EXPLANATION) it.copy(explanation = text, checkError = null)
        else it.withProgress(it.questionProgress.copy(answers = it.answers.toMutableList().also { a -> if (index in a.indices) a[index] = text }))
            .copy(checkError = null)
    }

    /** Claude checks your answer to question [index] (or your explanation, [EXPLANATION]). */
    fun check(index: Int) {
        val s = _ui.value
        val story = s.story ?: return
        val answer = (if (index == EXPLANATION) s.explanation else s.answers.getOrNull(index)).orEmpty().trim()
        if (answer.isEmpty() || s.checking != null) return
        val question = if (index == EXPLANATION) null else s.questions.getOrNull(index)?.question ?: return
        val grader = app.graderOrNull()
        if (grader == null) {
            _ui.update { it.copy(checkError = index to StoryImporter.NO_CLAUDE) }
            return
        }
        stopDictation()
        _ui.update { it.copy(checking = index, checkError = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { grader.reviewStoryAnswer(story.title, story.plainText, story.level, question, answer, s.comprehensionLevel) }
            }
            _ui.update { u ->
                r.fold(
                    { review ->
                        if (index == EXPLANATION) u.copy(checking = null, explanationReview = review)
                        else u.withProgress(u.questionProgress.copy(reviews = u.reviews.toMutableList().also { if (index in it.indices) it[index] = review }))
                            .copy(checking = null)
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
        stopDictation()
        stopReading()
        val request = ++dictationId
        _ui.update { it.copy(listening = index, partial = "", checkError = null) }
        speech.start(app.languages.get("zh").locale, object : SpeechInput.Listener {
            override fun onPartial(text: String) {
                if (request == dictationId) _ui.update { it.copy(partial = text) }
            }
            override fun onResults(candidates: List<String>) {
                if (request != dictationId) return
                _ui.update { it.copy(listening = null, partial = "") }
                val current = if (index == EXPLANATION) _ui.value.explanation else _ui.value.answers.getOrNull(index).orEmpty()
                candidates.firstOrNull()?.let { onAnswer(index, current + it) }
            }
            override fun onError(code: Int, message: String) {
                if (request == dictationId) _ui.update { it.copy(listening = null, partial = "", checkError = index to message) }
            }
        })
    }

    private fun stopDictation() {
        if (_ui.value.listening == null) return
        dictationId++
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
