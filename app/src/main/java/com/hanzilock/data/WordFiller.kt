package com.hanzilock.data

import com.hanzilock.core.Languages
import com.hanzilock.core.Settings
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.Pinyin

/**
 * Fills in what words added to a set are missing - reading, meanings, and an example sentence
 * with its reading (pinyin / kana) - using Claude, so every new word arrives ready to practise.
 * Does nothing without an API key or offline. Blocks - call it from a background thread.
 */
class WordFiller(
    private val words: WordRepository,
    private val languages: Languages,
    private val settings: Settings,
    private val grader: () -> ClaudeGrader?,
) {
    data class Result(
        /** Words that got a meaning they didn't have (they can now be practised). */
        val gotMeaning: Int,
        /** Batches Claude couldn't answer (offline, refused, ...). */
        val failedBatches: Int,
    )

    /**
     * True when Claude could add something to [w]: a meaning, a reading, an example, or an example
     * with its reading (not for words you edited yourself - your example stays first).
     */
    fun needsHelp(w: Word): Boolean {
        val hasReadings = languages.get(w.lang).canTypeReading
        return w.meanings.isEmpty() || w.examples.isEmpty() ||
            hasReadings && (w.reading.isBlank() || !w.userEdited && w.examples.none { !it.reading.isNullOrBlank() })
    }

    /** Asks Claude for whatever the words [ids] of [lang] are missing, [BATCH] words per request. */
    @Synchronized
    fun fill(lang: String, ids: Collection<Long>, progress: (String) -> Unit = {}): Result {
        val claude = grader() ?: return Result(0, 0)
        val todo = ids.distinct().mapNotNull { words.get(it) }.filter(::needsHelp)
        val hasReadings = languages.get(lang).canTypeReading
        val languageName = languages.get(lang).name
        var gotMeaning = 0
        var failed = 0
        todo.chunked(BATCH).forEachIndexed { n, batch ->
            progress("Asking Claude to fill in details… ${n * BATCH + 1}-${n * BATCH + batch.size} of ${todo.size}")
            val described = runCatching { claude.describeWords(languageName, batch.map { it.term }) }
                .getOrElse { failed++; return@forEachIndexed }
            for (w in batch) {
                val d = described.firstOrNull { it.term == w.term } ?: continue
                val needExample = w.examples.isEmpty() || hasReadings && !w.userEdited && w.examples.none { !it.reading.isNullOrBlank() }
                val example = Example(d.example.trim(), d.exampleReading.trim().ifEmpty { null }, d.exampleTranslation.trim().ifEmpty { null })
                val meanings = if (w.meanings.isEmpty()) d.meanings.map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                words.complete(
                    w.id,
                    reading = d.reading.trim().takeIf { w.reading.isBlank() && it.isNotEmpty() }?.let {
                        if (lang == "zh") Pinyin.normalizeToMarked(it).ifEmpty { it } else it
                    },
                    meanings = meanings,
                    // Claude's example (with its reading) goes first: it's the one the quiz and word page show.
                    examples = if (needExample && example.text.isNotEmpty()) listOf(example) + w.examples else emptyList(),
                )
                if (meanings.isNotEmpty()) gotMeaning++
            }
        }
        return Result(gotMeaning, failed)
    }

    /**
     * Catches words that reached your own sets some other way (a set imported on the PC, a restored
     * backup): each word in a custom set is looked at once, the first time Claude is available.
     */
    fun fillNewWords() {
        if (grader() == null) return
        val pending = words.customSetWordsAfter(settings.filledUpToWordId)
        if (pending.isEmpty()) return
        val failed = pending.filter(::needsHelp).groupBy { it.lang }
            .map { (lang, list) -> fill(lang, list.map { it.id }).failedBatches }.sum()
        if (failed == 0) settings.filledUpToWordId = pending.maxOf { it.id }
    }

    companion object {
        private const val BATCH = 20
    }
}
