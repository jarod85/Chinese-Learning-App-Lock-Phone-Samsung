package com.hanzilock.quiz

import kotlin.random.Random

/**
 * Offline sentence exercise: the example sentence is cut into word tiles, shuffled, and the
 * learner rebuilds it. Examples in the registry may mark word boundaries with spaces
 * ("我 每天 学习 汉语。"); otherwise the sentence is segmented with the dictionary.
 */
object SentenceTiles {
    private const val PUNCTUATION = "，。！？、：；“”‘’（）《》…—·,.!?:;\"'()~～"

    fun isPunctuation(c: Char): Boolean = c in PUNCTUATION || Character.getType(c).let {
        it == Character.OTHER_PUNCTUATION.toInt() || it == Character.START_PUNCTUATION.toInt() ||
            it == Character.END_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
            it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }

    fun isHanChar(c: Char): Boolean =
        Character.isSurrogate(c) || Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    private fun isCjkPunctuation(c: Char): Boolean = c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF

    /**
     * The sentence as it should be shown: the word-boundary spaces between Chinese characters
     * are removed; a space between Chinese and Latin text ("我用 iPhone") is kept.
     */
    fun display(sentence: String): String {
        val s = sentence.trim()
        val sb = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            if (c.isWhitespace()) {
                val prev = sb.lastOrNull()
                val next = s.getOrNull(i + 1)
                val keep = prev != null && next != null && !next.isWhitespace() &&
                    !(isHanChar(prev) && isHanChar(next)) && !isCjkPunctuation(prev) && !isCjkPunctuation(next)
                if (keep) sb.append(' ')
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Tiles in the correct order. The target word is always kept as one tile.
     * [isWord] tells whether a run of characters is a dictionary word (for unspaced sentences).
     */
    fun tokens(sentence: String, target: String, maxTiles: Int = 9, isWord: ((String) -> Boolean)? = null): List<String> {
        val raw = sentence.trim()
        val base = if (raw.any { it.isWhitespace() }) raw.split(Regex("\\s+")) else segment(raw, target, isWord)
        val glued = ArrayList<String>()
        for (t in base) {
            if (t.isEmpty()) continue
            if (t.all { isPunctuation(it) } && glued.isNotEmpty()) glued[glued.lastIndex] = glued.last() + t else glued.add(t)
        }
        return mergeToLimit(glued, target, maxTiles)
    }

    /** Forward maximum matching (up to 4 characters) against the dictionary. */
    fun segment(s: String, target: String, isWord: ((String) -> Boolean)? = null): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (target.isNotEmpty() && s.startsWith(target, i)) {
                out.add(target); i += target.length; continue
            }
            val c = s[i]
            if (!isHanChar(c)) {
                var j = i + 1
                while (j < s.length && !isHanChar(s[j]) && !s[j].isWhitespace() && isPunctuation(s[j]) == isPunctuation(c)) j++
                out.add(s.substring(i, j))
                i = j
                continue
            }
            var len = minOf(4, s.length - i)
            while (len > 1) {
                val w = s.substring(i, i + len)
                if (w.all(::isHanChar) && (isWord?.invoke(w) == true || (target.isNotEmpty() && w == target))) break
                len--
            }
            out.add(s.substring(i, i + len))
            i += len
        }
        return out.filter { it.isNotBlank() }
    }

    private fun mergeToLimit(tokens: List<String>, target: String, max: Int): List<String> {
        val t = tokens.toMutableList()
        while (t.size > max) {
            var best = -1
            var bestLen = Int.MAX_VALUE
            for (i in 0 until t.size - 1) {
                if (t[i] == target || t[i + 1] == target) continue
                val len = t[i].length + t[i + 1].length
                if (len < bestLen) { bestLen = len; best = i }
            }
            if (best < 0) break
            t[best] = t[best] + t[best + 1]
            t.removeAt(best + 1)
        }
        return t
    }

    /** Order in which tiles are offered: a shuffle that is never the solution itself. */
    fun shuffledOrder(count: Int, random: Random = Random.Default): List<Int> {
        val order = (0 until count).toMutableList()
        if (count < 2) return order
        repeat(10) {
            order.shuffle(random)
            if (order != (0 until count).toList()) return order
        }
        order.reverse()
        return order
    }

    /** Right if the picked tiles spell the sentence (tiles with equal text are interchangeable). */
    fun isCorrect(tokens: List<String>, pickedIndexes: List<Int>): Boolean =
        pickedIndexes.size == tokens.size && pickedIndexes.joinToString("") { tokens[it] } == tokens.joinToString("")
}
