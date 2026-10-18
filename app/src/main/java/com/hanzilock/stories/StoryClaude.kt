package com.hanzilock.stories

import com.hanzilock.data.AppJson
import com.hanzilock.quiz.ClaudeGrader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Claude's part in stories: writing a graded story, translating sentences, and the comprehension
 * questions and feedback at the end. Calls block - use a background thread.
 */

enum class StoryLength(val label: String, val factor: Double) {
    SHORT("Short", 0.6),
    MEDIUM("Medium", 1.0),
    LONG("Long", 1.6),
}

@Serializable
data class WrittenStory(
    val title: String,
    @SerialName("title_en") val titleEn: String = "",
    val paragraphs: List<WrittenParagraph> = emptyList(),
    val questions: List<StoryQuestion> = emptyList(),
)

@Serializable
data class WrittenParagraph(val sentences: List<WrittenSentence> = emptyList())

@Serializable
data class WrittenSentence(val text: String, val en: String = "")

/** Feedback on an answer to a comprehension question, or on your retelling of the story. */
@Serializable
data class StoryAnswerReview(
    val correct: Boolean,
    val feedback: String,
    @SerialName("feedback_pinyin") val feedbackPinyin: String = "",
    @SerialName("feedback_en") val feedbackEn: String = "",
    @SerialName("better_answer") val betterAnswer: String = "",
    @SerialName("better_answer_pinyin") val betterAnswerPinyin: String = "",
)

@Serializable
private data class Translations(val translations: List<String> = emptyList())

@Serializable
private data class Questions(val questions: List<StoryQuestion> = emptyList())

/** Roughly how many characters a medium-length story has at each HSK level. */
private val STORY_CHARS = mapOf(1 to 150, 2 to 250, 3 to 400, 4 to 550, 5 to 750, 6 to 950)

/** Picked at random when you don't give a topic, so the stories don't all sound alike. */
private val GENRES = listOf(
    "a funny everyday mishap", "a fable with animals", "a small mystery that gets solved", "a story about friendship",
    "a travel adventure in China", "a story set at school", "a story about a day at work", "a folk-tale style story",
    "a light science-fiction story", "a story about a family", "a story about food and cooking",
    "a heart-warming story about a stranger", "a story about a misunderstanding", "a story about a pet",
    "a story about a new neighbour", "a story about a lost item", "a story about a birthday or festival",
)

private val QUESTION_ITEM = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "question" to mapOf("type" to "string", "description" to "the question in simplified Chinese"),
        "pinyin" to mapOf("type" to "string", "description" to "pinyin of the question with tone marks"),
        "english" to mapOf("type" to "string", "description" to "English translation of the question"),
    ),
    "required" to listOf("question", "pinyin", "english"),
    "additionalProperties" to false,
)

private fun questionRules(level: String?) =
    "questions: 3 comprehension questions about the story in simple simplified Chinese (no harder than the story${level?.let { ", $it" } ?: ""}), " +
        "each answerable in one or two sentences from the text: what happened, and at least one asking why or how. " +
        "Give each question's pinyin with tone marks and its English translation."

/** A new story in simplified Chinese at HSK [level], with sentence translations and 3 questions. */
fun ClaudeGrader.writeStory(level: Int, length: StoryLength, topic: String, avoidTitles: List<String>): WrittenStory {
    val chars = ((STORY_CHARS[level] ?: 400) * length.factor).toInt()
    val sentence = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "text" to mapOf("type" to "string", "description" to "the sentence in simplified Chinese, a space between words"),
            "en" to mapOf("type" to "string", "description" to "natural English translation of the sentence"),
        ),
        "required" to listOf("text", "en"),
        "additionalProperties" to false,
    )
    val paragraph = mapOf(
        "type" to "object",
        "properties" to mapOf("sentences" to mapOf("type" to "array", "items" to sentence)),
        "required" to listOf("sentences"),
        "additionalProperties" to false,
    )
    val subject = topic.trim().ifEmpty { "your choice; this time make it ${GENRES.random()}" }
    val text = ask(
        system = """
            You write graded reading stories in simplified Chinese for a learner at HSK $level.
            Vocabulary: mostly words from HSK 1 to HSK $level, with at most a handful of harder words, only where the story needs them. Grammar and sentence length must suit HSK $level${if (level <= 2) " - keep sentences short and simple" else ""}.
            Write an original story of about $chars Chinese characters with a clear beginning, middle and end: characters, a small problem or surprise, and how it turns out. Make it natural and engaging, not a textbook dialogue. Use short paragraphs of 1-4 sentences.
            Split every paragraph into its sentences. text: the sentence in simplified Chinese with a space between words, punctuation included (for example "我 每天 学习 汉语 。"); en: a natural English translation of that sentence.
            title: a short Chinese title in simplified Chinese, without spaces; title_en: the title in English.
            ${questionRules("HSK $level")}
        """.trimIndent(),
        user = "Topic: $subject" + if (avoidTitles.isEmpty()) "" else "\nDon't reuse these titles: ${avoidTitles.joinToString("、")}",
        properties = linkedMapOf(
            "title" to ("string" to "Chinese title"),
            "title_en" to ("string" to "English title"),
        ),
        rawProperties = linkedMapOf(
            "paragraphs" to mapOf("type" to "array", "items" to paragraph),
            "questions" to mapOf("type" to "array", "items" to QUESTION_ITEM),
        ),
        timeout = Duration.ofSeconds(150),
    )
    return AppJson.decodeFromString<WrittenStory>(text)
}

/** English translations of [sentences] (Chinese, shown as written), in the same order. */
fun ClaudeGrader.translateSentences(title: String, sentences: List<String>): List<String> {
    if (sentences.isEmpty()) return emptyList()
    val text = ask(
        system = """
            Translate each numbered sentence of a Chinese story into natural English. Keep names and the story's tone consistent.
            Return exactly ${sentences.size} translations, in the same order, one per numbered sentence, without the numbers.
        """.trimIndent(),
        user = "Story: $title\n\n" + sentences.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
        properties = emptyMap(),
        rawProperties = mapOf("translations" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
        timeout = Duration.ofSeconds(90),
    )
    return AppJson.decodeFromString<Translations>(text).translations
}

/** Three comprehension questions about a story you have read. */
fun ClaudeGrader.storyQuestions(title: String, story: String, level: String?): List<StoryQuestion> {
    val text = ask(
        system = "You are a patient Chinese teacher. The learner has just read the story below.\n${questionRules(level)}",
        user = "Title: $title\n${level?.let { "Level: $it\n" } ?: ""}\n$story",
        properties = emptyMap(),
        rawProperties = mapOf("questions" to mapOf("type" to "array", "items" to QUESTION_ITEM)),
        timeout = Duration.ofSeconds(90),
    )
    return AppJson.decodeFromString<Questions>(text).questions
}

/**
 * Feedback on the learner's answer to [question], in simple Chinese with pinyin and an English
 * translation. With [question] null, [answer] is their explanation of the whole story.
 */
fun ClaudeGrader.reviewStoryAnswer(title: String, story: String, level: String?, question: String?, answer: String): StoryAnswerReview {
    val task = if (question != null) {
        """
            The learner read the story below and answered a comprehension question in Chinese.
            correct: true if the answer is right about the story (small language mistakes don't matter here).
            feedback: 1-3 short sentences: whether the answer is right and what is missing or wrong, then the most important language mistake, if any.
            better_answer: a correct, natural answer to the question in simple Chinese, close to the learner's own words where they were right.
        """
    } else {
        """
            The learner read the story below and was asked to explain it in their own words in Chinese.
            correct: true if they got the main points right (who, what happened and how it ended).
            feedback: 2-4 short sentences: what they understood well, any important point that is missing or wrong, then the most important language mistakes.
            better_answer: a short model explanation of the story (3-5 sentences) in simple Chinese, building on the learner's version.
        """
    }.trimIndent()
    val text = ask(
        system = """
            You are a patient, encouraging Chinese teacher.
            $task
            Write feedback and better_answer in simple simplified Chinese suited to the learner${level?.let { " ($it)" } ?: ""}. feedback_pinyin and better_answer_pinyin: their pinyin with tone marks. feedback_en: an English translation of the feedback.
            If the learner wrote pinyin or English instead of Chinese characters, judge the content anyway and remind them in the feedback to answer in Chinese characters.
        """.trimIndent(),
        user = "Title: $title\n\nStory:\n$story\n\n" + (question?.let { "Question: $it\n" } ?: "Task: explain the story in Chinese\n") +
            "Learner's answer: $answer",
        properties = linkedMapOf(
            "correct" to ("boolean" to "true if the answer is right about the story"),
            "feedback" to ("string" to "short feedback in simple Chinese"),
            "feedback_pinyin" to ("string" to "pinyin of the feedback"),
            "feedback_en" to ("string" to "English translation of the feedback"),
            "better_answer" to ("string" to "a model answer in simple Chinese"),
            "better_answer_pinyin" to ("string" to "pinyin of the model answer"),
        ),
        timeout = Duration.ofSeconds(90),
    )
    return AppJson.decodeFromString<StoryAnswerReview>(text)
}
