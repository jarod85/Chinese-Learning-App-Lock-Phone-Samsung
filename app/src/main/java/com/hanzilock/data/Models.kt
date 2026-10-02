@file:OptIn(ExperimentalSerializationApi::class)

package com.hanzilock.data

import com.hanzilock.quiz.Pinyin
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

/** Compact JSON for database columns and preferences. */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** Files you export (backups): every field written out. */
val ExportJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

@Serializable
data class Example(
    /** The sentence; spaces may mark word boundaries for the tile exercise (Chinese). */
    @JsonNames("zh") val text: String,
    @JsonNames("pinyin") val reading: String? = null,
    val en: String? = null,
)

data class Word(
    val id: Long,
    /** Language code: zh, ja, ko, es, fr, it, ... */
    val lang: String,
    /** The word as written: 学习, 食べる, 사랑, el cambio. */
    val term: String,
    val traditional: String?,
    /** Pinyin ("xué xí", tone marks, one space per syllable) or kana reading; empty if none. */
    val reading: String,
    val meanings: List<String>,
    /** Extra answers you accepted with "I was right" - accepted but not shown as definitions. */
    val altMeanings: List<String>,
    val examples: List<Example>,
    /**
     * Inflected forms / alternative spellings accepted when you say or use the word ("es" for ser). For
     * Chinese: other accepted pinyin, e.g. the official HSK reading zhī dao next to CC-CEDICT's zhī dào.
     */
    val forms: List<String>,
    val tags: List<String>,
    val source: String,
    val userEdited: Boolean,
    val enabled: Boolean,
    val box: Int,
    val dueAt: Long,
    val wins: Int,
    val losses: Int,
    val lastSeenAt: Long,
    val createdAt: Long,
) {
    val isNew: Boolean get() = lastSeenAt == 0L
    val acceptedMeanings: List<String> get() = meanings + altMeanings
    val readingDisplay: String get() = if (lang == "zh") Pinyin.display(reading) else reading

    /** Imported words that still need a meaning (the quiz skips them until they have one). */
    val needsDetails: Boolean get() = meanings.isEmpty()
}

/** Editable content of a word (no progress). */
data class WordDraft(
    val lang: String,
    val term: String,
    val traditional: String?,
    val reading: String,
    val meanings: List<String>,
    val examples: List<Example>,
    val forms: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** A group of words you can switch on or off for practice (HSK 3, JLPT N4, "My food words", ...). */
data class WordSet(
    val id: Long,
    val key: String,
    val lang: String,
    val name: String,
    val level: String?,
    val sort: Int,
    val bundled: Boolean,
    val custom: Boolean,
    val enabled: Boolean,
    val rev: String?,
    val wordCount: Int,
    val seenCount: Int,
)

enum class QuizPart(val label: String) { PRONUNCIATION("pronunciation"), MEANING("meaning"), SENTENCE("sentence") }

data class Attempt(
    val id: Long,
    val sessionId: Long?,
    val wordId: Long,
    val lang: String,
    val term: String,
    val at: Long,
    val win: Boolean,
    val failedPart: QuizPart?,
    val heard: String?,
    val meaningAnswer: String?,
    val sentenceAnswer: String?,
    val feedback: String?,
    val gradedBy: String?,
)

data class DayStat(val epochDay: Long, val wins: Int, val losses: Int)

data class Totals(
    val words: Int,
    val seen: Int,
    val wins: Int,
    val losses: Int,
    val sessionsCompleted: Int,
    val sessionsSkipped: Int,
    val streakDays: Int,
)

data class DictEntry(
    val id: Long,
    val simplified: String,
    val traditional: String,
    val pinyinNumbered: String,
    val definitions: List<String>,
) {
    val pinyinMarked: String get() = Pinyin.numberedToMarked(pinyinNumbered)
}

// ---- set files (content/sets) ------------------------------------------------------------------

/** One word of a set file. Old registry files used "hanzi"/"pinyin"; both are accepted. */
@Serializable
data class SetWord(
    @JsonNames("hanzi") val term: String,
    val traditional: String? = null,
    @JsonNames("pinyin") val reading: String? = null,
    val meanings: List<String> = emptyList(),
    val examples: List<Example> = emptyList(),
    val forms: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class SetFile(
    val key: String = "",
    val lang: String = "zh",
    val name: String = "",
    val rev: String? = null,
    val words: List<SetWord> = emptyList(),
)

/** Entry of content/sets/index.json. */
@Serializable
data class SetInfo(
    val key: String,
    val lang: String,
    val name: String,
    val level: String? = null,
    val sort: Int = 0,
    val file: String = "",
    val count: Int = 0,
    val rev: String? = null,
    /** Switched on the first time the set is installed. */
    val enabled: Boolean = false,
    val custom: Boolean = false,
    val source: String? = null,
)
