package com.hanzilock.stories

import com.hanzilock.quiz.SentenceTiles

/**
 * Story text without Android: cutting Chinese into words (with a dictionary lookup), paragraphs
 * into sentences ([StoryChunk]s) and sentences into pages. A stored sentence keeps its word
 * boundaries as single spaces, and [join] shows it without them.
 */
object StoryText {
    /** Longest dictionary word tried when cutting text into words. */
    const val MAX_WORD = 6

    /** About this many characters fit on a page at the reader's text size. */
    const val PAGE_CHARS = 170

    private const val SENTENCE_END = "。！？!?；;…"
    private const val CLOSING = "”’」』）)》\"'"

    fun hasHan(s: String): Boolean = s.any(SentenceTiles::isHanChar)

    /** Letters and digits of alphabetic text: two such words keep a space between them ("iPhone 15"). */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() && !SentenceTiles.isHanChar(c)

    /** The words of a stored sentence. */
    fun tokens(chunk: String): List<String> = chunk.split(' ').filter { it.isNotEmpty() }

    /** A run of words as it is shown, with [starts] = where each word begins in [text]. */
    class Joined(val text: String, val starts: IntArray) {
        /** Index of the word at character [offset], or -1. */
        fun wordAt(offset: Int): Int {
            if (offset < 0 || offset >= text.length || starts.isEmpty()) return -1
            var i = starts.binarySearch(offset)
            if (i < 0) i = -i - 2
            return i
        }
    }

    fun layout(tokens: List<String>): Joined {
        val sb = StringBuilder()
        val starts = IntArray(tokens.size)
        tokens.forEachIndexed { i, t ->
            if (i > 0 && sb.isNotEmpty() && t.isNotEmpty() && isWordChar(sb.last()) && isWordChar(t.first())) sb.append(' ')
            starts[i] = sb.length
            sb.append(t)
        }
        return Joined(sb.toString(), starts)
    }

    fun join(tokens: List<String>): String = layout(tokens).text

    /**
     * Cuts [text] into words. Runs of Chinese characters are matched against the dictionary,
     * forwards and backwards (the cut with fewer words, then fewer single characters, wins);
     * [lookup] returns the simplified form of a dictionary word (so traditional text comes out
     * simplified), or null if it isn't one. Other text is split at spaces and punctuation.
     */
    fun segment(text: String, maxLen: Int = MAX_WORD, lookup: (String) -> String?): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val start = i
            if (SentenceTiles.isHanChar(text[i])) {
                while (i < text.length && SentenceTiles.isHanChar(text[i])) i++
                out += segmentHan(text.substring(start, i), maxLen, lookup)
            } else {
                while (i < text.length && !SentenceTiles.isHanChar(text[i])) i++
                splitOther(text.substring(start, i), out)
            }
        }
        return out
    }

    /** Every run of 1..[maxLen] Chinese characters in [text]: what [segment] may look up. */
    fun candidates(text: String, maxLen: Int = MAX_WORD): Set<String> {
        val out = HashSet<String>()
        var i = 0
        while (i < text.length) {
            if (!SentenceTiles.isHanChar(text[i])) { i++; continue }
            val start = i
            while (i < text.length && SentenceTiles.isHanChar(text[i])) i++
            for (a in start until i) for (len in 1..minOf(maxLen, i - a)) out += text.substring(a, a + len)
        }
        return out
    }

    private fun segmentHan(run: String, maxLen: Int, lookup: (String) -> String?): List<String> {
        val fwd = ArrayList<String>()
        var i = 0
        while (i < run.length) {
            var len = minOf(maxLen, run.length - i)
            while (len > 1 && lookup(run.substring(i, i + len)) == null) len--
            fwd += run.substring(i, i + len)
            i += len
        }
        val bwd = ArrayDeque<String>()
        var j = run.length
        while (j > 0) {
            var len = minOf(maxLen, j)
            while (len > 1 && lookup(run.substring(j - len, j)) == null) len--
            bwd.addFirst(run.substring(j - len, j))
            j -= len
        }
        val pick = when {
            fwd.size != bwd.size -> if (fwd.size < bwd.size) fwd else bwd
            fwd.count { it.length == 1 } < bwd.count { it.length == 1 } -> fwd
            else -> bwd.toList()
        }
        return pick.map { lookup(it) ?: it }
    }

    /** Words of alphabetic text and digits stay whole; each punctuation mark ("……" counts as one) is a word of its own. */
    private fun splitOther(s: String, out: MutableList<String>) {
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) out += word.toString()
            word.clear()
        }
        for (c in s) {
            when {
                c.isWhitespace() -> flush()
                c.isLetterOrDigit() -> word.append(c)
                else -> {
                    flush()
                    val last = out.lastOrNull()
                    if (last != null && last.all { it == c } && (c == '…' || c == '—' || c == '.' || c == '-')) out[out.lastIndex] = last + c
                    else out += c.toString()
                }
            }
        }
        flush()
    }

    /**
     * Words of a sentence whose word boundaries are already marked with spaces (as Claude writes
     * them, "我 每天 学习 汉语。"); punctuation stuck to a word is split off.
     */
    fun tokensOfSpaced(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap { piece ->
        segment(piece, maxLen = Int.MAX_VALUE) { it }
    }

    /** Whether [text] marks its word boundaries with spaces. */
    fun isSpaced(text: String): Boolean {
        val t = text.trim()
        return (1 until t.length - 1).any { i ->
            t[i].isWhitespace() && (SentenceTiles.isHanChar(t[i - 1]) || SentenceTiles.isHanChar(t[i + 1]))
        }
    }

    private fun isEnd(token: String) = token.isNotEmpty() && token.all { it in SENTENCE_END }
    private fun isClosing(token: String) = token.isNotEmpty() && token.all { it in CLOSING }

    /** Paragraphs (as words) to sentences; paragraphs without Chinese are dropped. */
    fun chunks(paragraphs: List<List<String>>): List<StoryChunk> {
        val out = ArrayList<StoryChunk>()
        for (words in paragraphs) {
            if (words.none(::hasHan)) continue
            var start = 0
            var i = 0
            var first = true
            fun emit(end: Int) {
                val sentence = words.subList(start, end)
                if (sentence.isNotEmpty()) {
                    out += StoryChunk(sentence.joinToString(" "), p = first)
                    first = false
                }
                start = end
            }
            while (i < words.size) {
                if (isEnd(words[i])) {
                    var end = i + 1
                    while (end < words.size && (isEnd(words[end]) || isClosing(words[end]))) end++
                    emit(end)
                    i = end
                } else {
                    i++
                }
            }
            if (start < words.size) emit(words.size)
        }
        return out
    }

    /**
     * Pages of about [maxChars] characters, as ranges of sentence indexes. A paragraph break costs
     * a few characters, and a page that is mostly full ends where a paragraph does.
     */
    fun paginate(chunks: List<StoryChunk>, maxChars: Int = PAGE_CHARS, breakCost: Int = 8): List<IntRange> {
        val pages = ArrayList<IntRange>()
        var start = 0
        var size = 0
        chunks.forEachIndexed { i, c ->
            val len = join(c.tokens).length
            val cost = len + if (c.p && i > start) breakCost else 0
            if (i > start && (size + cost > maxChars || (c.p && size >= maxChars * 3 / 4))) {
                pages += start until i
                start = i
                size = len
            } else {
                size += cost
            }
        }
        if (start < chunks.size) pages += start until chunks.size
        return pages
    }

    /** Short title from the start of a text. */
    fun titleFrom(text: String, max: Int = 16): String {
        val first = text.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val sentence = first.takeWhile { it !in SENTENCE_END && it != '，' && it != ',' }
        val t = sentence.ifEmpty { first }
        return if (t.length <= max) t else t.take(max) + "…"
    }
}
