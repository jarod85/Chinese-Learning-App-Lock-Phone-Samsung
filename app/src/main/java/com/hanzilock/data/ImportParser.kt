package com.hanzilock.data

import com.hanzilock.quiz.SentenceTiles

/** A word read from an import file; missing fields are filled from the dictionary later. */
data class ParsedWord(
    val hanzi: String,
    val traditional: String? = null,
    val pinyin: String? = null,
    val meaning: String? = null,
)

/**
 * Parses word lists from text:
 *  - Pleco flashcard exports: `headword<TAB>pinyin<TAB>definition`, where the headword may be
 *    `简体[繁體]`; lines starting with `//` are categories and are skipped.
 *  - CSV with the same three columns.
 *  - Plain lists of Chinese words, one or several per line.
 */
object ImportParser {
    /** Byte-order mark some editors put at the start of text files. */
    val BOM: Char = Char(0xFEFF)

    fun parseText(text: String): List<ParsedWord> {
        val out = ArrayList<ParsedWord>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim().trimStart(BOM)
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
            val columns = when {
                '\t' in line -> line.split('\t')
                line.count { it == ',' } >= 2 && hasHan(line.substringBefore(',')) -> splitCsv(line)
                else -> null
            }
            if (columns == null) {
                // Plain list: every run of Chinese characters is a word.
                line.split(Regex("[\\s,，、;；/|]+")).map { it.trim() }.filter { hasHan(it) }
                    .forEach { out.add(headword(it)) }
                continue
            }
            val head = columns[0].trim()
            if (!hasHan(head)) continue
            val pinyin = columns.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && !hasHan(it) }
            val meaning = columns.drop(2).joinToString("; ") { it.trim() }.trim().trim(';', ' ').takeIf { it.isNotEmpty() }
            out.add(headword(head).copy(pinyin = pinyin, meaning = meaning))
        }
        return out.distinctBy { it.hanzi }
    }

    /** "学习[學習]" -> simplified 学习, traditional 學習. */
    private fun headword(text: String): ParsedWord {
        val m = Regex("^([^\\[]+)\\[([^\\]]+)\\]$").find(text.trim())
        return if (m != null) {
            val simp = m.groupValues[1].trim()
            val trad = m.groupValues[2].trim()
            ParsedWord(hanzi = simp, traditional = trad.takeIf { it != simp })
        } else {
            ParsedWord(hanzi = text.trim())
        }
    }

    private fun hasHan(s: String): Boolean = s.any { SentenceTiles.isHanChar(it) && !Character.isSurrogate(it) }

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
