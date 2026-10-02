package com.hanzilock.quiz

import java.text.Normalizer

/**
 * Decides whether what the speech recogniser heard is the word, for any language:
 * Chinese goes through pinyin/homophone matching, Japanese also accepts the kana reading, and
 * everything else compares words ignoring case and accents, accepting inflected forms and a
 * noun said without its article ("cambio" for "el cambio").
 */
object Pronunciation {
    data class Result(val matched: Boolean, val heard: String)

    private val ARTICLES = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "le", "les", "l", "une", "des", "il", "lo", "gli", "i", "uno",
        "der", "die", "das", "den", "dem", "ein", "eine", "o", "a", "os", "as", "um", "uma", "de", "het", "en", "ett",
    )

    fun match(
        lang: String,
        term: String,
        reading: String,
        forms: List<String>,
        candidates: List<String>,
        readings: (String) -> Set<String>,
    ): Result = when (lang) {
        "zh", "yue" -> SpeechMatcher.match(candidates, term, Pinyin.syllables(reading), readings).let { Result(it.matched, it.heard) }
        "ja" -> japanese(candidates, term, reading, forms)
        else -> spelled(candidates, term, forms)
    }

    private fun heardOf(candidates: List<String>) = candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun japanese(candidates: List<String>, term: String, reading: String, forms: List<String>): Result {
        val written = (listOf(term) + forms).map(::stripJa).filter { it.isNotEmpty() }
        val kana = JapaneseKana.normalizeReading(reading.ifEmpty { if (term.all { JapaneseKana.isKana(it) }) term else "" })
        for (c in candidates) {
            val clean = stripJa(c)
            if (written.any { clean.contains(it) }) return Result(true, c.trim())
            if (kana.isNotEmpty() && JapaneseKana.expandLongVowels(JapaneseKana.toHiragana(clean)).contains(kana)) return Result(true, c.trim())
        }
        return Result(false, heardOf(candidates))
    }

    private fun stripJa(s: String): String = s.filter { !it.isWhitespace() && Character.isLetterOrDigit(it) || it == 'ー' }

    /** Lower-case, accents removed, only letters/digits, single spaces. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder()
        var space = false
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.isLetterOrDigit(c) -> { if (space && sb.isNotEmpty()) sb.append(' '); sb.append(c); space = false }
                else -> space = true
            }
        }
        return sb.toString()
    }

    /** The accepted spoken forms of a word: itself, its forms, and without a leading article. */
    fun variants(term: String, forms: List<String>): List<List<String>> {
        val out = LinkedHashSet<List<String>>()
        for (f in listOf(term) + forms) {
            val tokens = normalize(f).split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            out.add(tokens)
            if (tokens.size > 1 && tokens[0] in ARTICLES) out.add(tokens.drop(1))
        }
        return out.toList()
    }

    private fun spelled(candidates: List<String>, term: String, forms: List<String>): Result {
        val wanted = variants(term, forms)
        for (c in candidates) {
            val heard = normalize(c).split(' ').filter { it.isNotEmpty() }
            if (wanted.any { containsSequence(heard, it) }) return Result(true, c.trim())
        }
        return Result(false, heardOf(candidates))
    }

    fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    /** For the offline sentence check: does the sentence use the word (or one of its forms)? */
    fun sentenceUses(lang: String, sentence: String, term: String, forms: List<String>, reading: String = ""): Boolean = when (lang) {
        "zh", "yue" -> (listOf(term) + forms).any { it.isNotBlank() && sentence.contains(it) }
        // Written in kanji or in kana.
        "ja" -> (listOf(term, reading) + forms).any { it.isNotBlank() && sentence.contains(it) }
        // Korean attaches particles and endings to the word (나는, 시간이, 했어요), so look inside words.
        "ko" -> (listOf(koreanStem(term)) + forms).any { it.isNotBlank() && sentence.contains(it) }
        else -> {
            val tokens = normalize(sentence).split(' ').filter { it.isNotEmpty() }
            variants(term, forms).any { containsSequence(tokens, it) }
        }
    }

    /** 하다 -> 하, -이다 -> 이: the part of a dictionary form that stays when it is conjugated. */
    private fun koreanStem(term: String): String =
        term.trim().removePrefix("-").let { if (it.length > 1) it.removeSuffix("다") else it }
}
