package com.hanzilock.data

import com.hanzilock.quiz.Pinyin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /** Chinese sentence; spaces may mark word boundaries for the tile exercise. */
    val zh: String,
    val pinyin: String? = null,
    val en: String? = null,
)

data class Word(
    val id: Long,
    val hanzi: String,
    val traditional: String?,
    /** Tone-marked syllables separated by spaces: "xué xí". */
    val pinyin: String,
    val meanings: List<String>,
    /** Extra answers you accepted with "I was right" - accepted but not shown as definitions. */
    val altMeanings: List<String>,
    val examples: List<Example>,
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
    val pinyinDisplay: String get() = Pinyin.display(pinyin)
}

/** Editable content of a word (no progress). */
data class WordDraft(
    val hanzi: String,
    val traditional: String?,
    val pinyin: String,
    val meanings: List<String>,
    val examples: List<Example>,
    val tags: List<String>,
)

enum class QuizPart(val label: String) { PRONUNCIATION("pronunciation"), MEANING("meaning"), SENTENCE("sentence") }

data class Attempt(
    val id: Long,
    val sessionId: Long?,
    val wordId: Long,
    val hanzi: String,
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

/** One entry of content/registry/words.json. */
@Serializable
data class RegistryWord(
    val hanzi: String,
    val traditional: String? = null,
    val pinyin: String,
    val meanings: List<String>,
    val examples: List<Example> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class RegistryFile(
    val version: Int = 1,
    val words: List<RegistryWord> = emptyList(),
)
