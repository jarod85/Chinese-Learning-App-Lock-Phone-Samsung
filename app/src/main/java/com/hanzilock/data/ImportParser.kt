package com.hanzilock.data

import com.hanzilock.quiz.JapaneseKana
import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.SentenceTiles

/** A word read from an import file; missing fields are filled in afterwards. */
data class ParsedWord(
    val term: String,
    val traditional: String? = null,
    val reading: String? = null,
    val meaning: String? = null,
)

/**
 * Reads word lists:
 *  - just the words - one per line, or separated by commas / 、 / spaces (for Chinese, Japanese, Korean);
 *  - Pleco flashcard exports and TSV: `word<TAB>pinyin<TAB>definition` (`简体[繁體]` headwords are split);
 *  - CSV with a header row naming the columns (word / pinyin or reading / meaning or english), which is
 *    also how to give meanings for words in alphabetic languages ("word,meaning");
 *  - CSV rows like `学习,xué xí,to study` for Chinese/Japanese without a header.
 */
object ImportParser {
    /** Byte-order mark some editors put at the start of text files. */
    val BOM: Char = Char(0xFEFF)

    private val TERM_HEADERS = setOf(
        "word", "words", "term", "hanzi", "chinese", "character", "characters", "kanji", "japanese", "korean", "hangul",
        "vocab", "vocabulary", "expression", "spanish", "french", "italian", "german", "portuguese", "russian",
    )
    private val READING_HEADERS = setOf("pinyin", "reading", "kana", "furigana", "hiragana", "pronunciation", "romaji")
    private val MEANING_HEADERS = setOf("meaning", "meanings", "english", "definition", "definitions", "translation", "gloss")

    fun parseText(text: String): List<ParsedWord> {
        val lines = text.lines().map { it.trim().trimStart(BOM).trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
        if (lines.isEmpty()) return emptyList()
        val header = cells(lines.first()).map { it.lowercase() }
        val termCol = header.indexOfFirst { it in TERM_HEADERS }
        // A one-column list can still start with a heading ("Hanzi", "Words").
        if (termCol == 0 && header.size == 1) return parseText(lines.drop(1).joinToString("\n"))
        if (termCol >= 0 && header.size >= 2) {
            val readingCol = header.indexOfFirst { it in READING_HEADERS }
            val meaningCol = header.indexOfFirst { it in MEANING_HEADERS }
            return lines.drop(1).mapNotNull { line ->
                val row = cells(line)
                val term = row.getOrNull(termCol)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                headword(term).copy(
                    reading = row.getOrNull(readingCol)?.takeIf { it.isNotEmpty() },
                    meaning = row.getOrNull(meaningCol)?.takeIf { it.isNotEmpty() },
                )
            }.distinctBy { it.term }
        }
        val out = ArrayList<ParsedWord>()
        for (line in lines) {
            val row = cells(line).filter { it.isNotEmpty() }
            if (row.isEmpty()) continue
            val tabular = '\t' in line || (row.size >= 2 && hasCjk(row[0]) && row.drop(1).all { !hasCjk(it) || kanaOnly(it) })
            if (tabular) {
                out.add(tableRow(row))
                continue
            }
            for (cell in row) {
                val parts = if (hasCjk(cell)) cell.split(Regex("[\\s、，；;/|]+")) else cell.split(Regex("[；;/|]+"))
                parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(headword(it)) }
            }
        }
        return out.distinctBy { it.term }
    }

    /** word, then (for Chinese/Japanese) an optional reading, then the meaning. */
    private fun tableRow(row: List<String>): ParsedWord {
        val head = headword(row[0])
        val rest = row.drop(1)
        if (!hasCjk(row[0])) return head.copy(meaning = rest.joinToString("; ").ifEmpty { null })
        val second = rest.firstOrNull()
        val isReading = second != null && (kanaOnly(second) || (!hasCjk(second) && Pinyin.looksLikePinyin(second)))
        return head.copy(
            reading = if (isReading) second else null,
            meaning = (if (isReading) rest.drop(1) else rest).joinToString("; ").ifEmpty { null },
        )
    }

    private fun cells(line: String): List<String> = (if ('\t' in line) line.split('\t') else splitCsv(line)).map { it.trim() }

    /** "学习[學習]" -> simplified 学习, traditional 學習. */
    private fun headword(text: String): ParsedWord {
        val m = Regex("^([^\\[]+)\\[([^\\]]+)\\]$").find(text.trim())
        return if (m != null && hasCjk(m.groupValues[1])) {
            val simp = m.groupValues[1].trim()
            val trad = m.groupValues[2].trim()
            ParsedWord(term = simp, traditional = trad.takeIf { it != simp })
        } else {
            ParsedWord(term = text.trim())
        }
    }

    fun hasCjk(s: String): Boolean = s.any {
        (SentenceTiles.isHanChar(it) && !Character.isSurrogate(it)) || JapaneseKana.isKana(it) ||
            Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL
    }

    private fun kanaOnly(s: String): Boolean = s.isNotEmpty() && s.all { JapaneseKana.isKana(it) || it == 'ー' }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
