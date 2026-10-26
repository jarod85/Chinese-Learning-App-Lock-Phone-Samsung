package com.hanzilock.stories

import com.hanzilock.data.DictState
import com.hanzilock.data.DictionaryRepository
import com.hanzilock.quiz.ClaudeGrader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a story from any source into sentences with marked word boundaries and saves it:
 * written by Claude, fetched from a link, or pasted. Traditional characters become simplified.
 */
class StoryImporter(
    private val stories: StoryRepository,
    private val dictionary: DictionaryRepository,
    private val grader: () -> ClaudeGrader?,
) {
    class ImportException(message: String) : Exception(message)

    /** Claude writes a new story at HSK [level]; returns its id. */
    suspend fun write(
        level: Int,
        length: StoryLength,
        topic: String,
        comprehension: ComprehensionLevel = ComprehensionLevel.INFERENTIAL,
    ): Long = withContext(Dispatchers.IO) {
        val g = grader() ?: throw ImportException(NO_CLAUDE)
        val w = runCatching { g.writeStory(level, length, topic, stories.recentTitles(), comprehension) }
            .getOrElse { throw ImportException(it.message ?: "Claude couldn't write a story. Try again.") }
        val sentences = w.paragraphs.map { p -> p.sentences.filter { it.text.isNotBlank() } }.filter { it.isNotEmpty() }
        val forms = forms(sentences.flatten().filterNot { StoryText.isSpaced(it.text) }.joinToString("\n") { it.text })
        val chunks = sentences.flatMap { p ->
            p.mapIndexed { i, s ->
                val words = if (StoryText.isSpaced(s.text)) StoryText.tokensOfSpaced(s.text) else StoryText.segment(s.text) { forms[it] }
                StoryChunk(words.joinToString(" "), s.en.trim(), p = i == 0)
            }
        }
        if (chunks.isEmpty()) throw ImportException("Claude's story came back empty. Try again.")
        ensureActive()
        stories.add(
            StoryDraft(
                title = w.title.replace(" ", "").ifEmpty { StoryText.titleFrom(StoryText.join(chunks.first().tokens)) },
                titleEn = w.titleEn.trim(),
                source = SOURCE_CLAUDE,
                url = null,
                level = "HSK $level",
                body = StoryBody(chunks).withQuestions(comprehension, w.questions.filter { it.question.isNotBlank() }.take(3)),
            ),
        )
    }

    /** The story at [url] (an existing copy is reused); returns its id. */
    suspend fun fromLink(url: String): Long = withContext(Dispatchers.IO) {
        stories.findByUrl(url)?.let { return@withContext it }
        val page = try {
            WebStories.fetch(url)
        } catch (e: WebStories.WebException) {
            throw ImportException(e.message ?: "Couldn't load the page.")
        }
        stories.findByUrl(page.url)?.let { return@withContext it }
        val paragraphs = cap(page.paragraphs)
        val words = if (page.spaced) paragraphs.map(StoryText::tokensOfSpaced) else segment(paragraphs)
        val chunks = StoryText.chunks(words)
        if (chunks.isEmpty()) throw ImportException("No Chinese story text found on that page.")
        ensureActive()
        stories.add(
            StoryDraft(
                title = page.title.ifBlank { StoryText.titleFrom(StoryText.join(chunks.first().tokens)) },
                titleEn = "",
                source = page.source,
                url = page.url,
                level = page.level?.let { "HSK $it" },
                body = StoryBody(chunks),
            ),
        )
    }

    /** Chinese text you pasted or shared; returns the new story's id. */
    suspend fun fromText(text: String): Long = withContext(Dispatchers.IO) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // A short first line followed by the text is taken as the title.
        val title = lines.firstOrNull()?.takeIf { lines.size > 1 && it.length <= 30 && StoryText.hasHan(it) }
        val body = if (title != null) lines.drop(1) else lines
        val chunks = StoryText.chunks(segment(cap(body)))
        if (chunks.isEmpty()) throw ImportException("There's no Chinese text in it.")
        ensureActive()
        stories.add(
            StoryDraft(
                title = title ?: StoryText.titleFrom(StoryText.join(chunks.first().tokens)),
                titleEn = "",
                source = SOURCE_TEXT,
                url = null,
                level = null,
                body = StoryBody(chunks),
            ),
        )
    }

    /** Very long pages are cut to the first [MAX_CHARS] characters (whole paragraphs). */
    private fun cap(paragraphs: List<String>): List<String> {
        var total = 0
        return paragraphs.takeWhile { p -> (total < MAX_CHARS).also { total += p.length } }
    }

    private suspend fun segment(paragraphs: List<String>): List<List<String>> {
        val forms = forms(paragraphs.joinToString("\n"))
        return paragraphs.map { p -> StoryText.segment(p) { forms[it] } }
    }

    /** Dictionary forms of every word that may occur in [text] (waits for the dictionary on a first start). */
    private suspend fun forms(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        withTimeoutOrNull(120_000) { dictionary.state.first { it is DictState.Ready || it is DictState.Failed || it is DictState.Missing } }
        return dictionary.wordForms(StoryText.candidates(text))
    }

    companion object {
        const val SOURCE_CLAUDE = "Claude"
        const val SOURCE_TEXT = "Pasted text"
        private const val MAX_CHARS = 8000
        const val NO_CLAUDE = "This needs Claude: add an Anthropic API key in Settings > AI grading " +
            "(not set to \"Always offline\") and be online."
    }
}
