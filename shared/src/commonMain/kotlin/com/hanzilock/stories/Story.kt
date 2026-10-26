package com.hanzilock.stories

import kotlinx.serialization.Serializable

/** One sentence of a story: the unit that is read aloud, highlighted and translated. */
@Serializable
data class StoryChunk(
    /** The sentence in simplified Chinese, its words separated by single spaces ("我 每天 学习 汉语 。"). */
    val text: String,
    /** English translation; empty until Claude has translated it. */
    val en: String = "",
    /** First sentence of a paragraph. */
    val p: Boolean = false,
) {
    val tokens: List<String> get() = StoryText.tokens(text)
}

/** A comprehension question asked after the last page. */
@Serializable
data class StoryQuestion(val question: String, val pinyin: String = "", val english: String = "")

/** Thinking depth, separate from the story's HSK vocabulary level. */
@Serializable
enum class ComprehensionLevel(val label: String, val description: String, val answerHint: String) {
    LITERAL(
        "Literal",
        "Find facts the story states directly: who, what, where and when.",
        "Answer in Chinese using details from the story.",
    ),
    INFERENTIAL(
        "Inferential",
        "Read between the lines: explain motives, feelings and likely outcomes using clues.",
        "Answer in Chinese and explain which clues support your interpretation.",
    ),
    EVALUATIVE(
        "Evaluative",
        "Judge choices, compare alternatives and defend your view with details from the story.",
        "Give your view in Chinese, with a reason and a detail from the story. Different views can be valid.",
    ),
}

/** Everything in a story that isn't shown in the list: the text, translations and questions. */
@Serializable
data class StoryBody(
    val chunks: List<StoryChunk> = emptyList(),
    /** Questions saved before levels existed are literal questions. */
    val questions: List<StoryQuestion> = emptyList(),
    val questionSets: Map<ComprehensionLevel, List<StoryQuestion>> = emptyMap(),
) {
    fun questionsFor(level: ComprehensionLevel): List<StoryQuestion> =
        if (level == ComprehensionLevel.LITERAL) questions else questionSets[level].orEmpty()

    fun withQuestions(level: ComprehensionLevel, found: List<StoryQuestion>): StoryBody =
        if (level == ComprehensionLevel.LITERAL) copy(questions = found)
        else copy(questionSets = questionSets + (level to found))
}

data class Story(
    val id: Long,
    /** Chinese title (or the source's own title). */
    val title: String,
    val titleEn: String,
    /** "Claude", "Mandarin Bean", the web site's name, or "Pasted text". */
    val source: String,
    val url: String?,
    /** "HSK 3", or null when unknown. */
    val level: String?,
    val body: StoryBody,
    /** Page you were on when you left the story. */
    val page: Int,
    val finishedAt: Long?,
    val createdAt: Long,
) {
    /** The whole text as shown, one paragraph per line (for Claude). */
    val plainText: String
        get() = buildString {
            body.chunks.forEachIndexed { i, c ->
                if (c.p && i > 0) append('\n')
                append(StoryText.join(c.tokens))
            }
        }
}

/** A row of the story list. */
data class StorySummary(
    val id: Long,
    val title: String,
    val titleEn: String,
    val source: String,
    val level: String?,
    val chars: Int,
    val page: Int,
    val pages: Int,
    val finishedAt: Long?,
    val createdAt: Long,
)

/** A story ready to be saved. */
data class StoryDraft(
    val title: String,
    val titleEn: String,
    val source: String,
    val url: String?,
    val level: String?,
    val body: StoryBody,
)
