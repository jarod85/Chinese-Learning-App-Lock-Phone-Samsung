package com.hanzilock.quiz

/**
 * Pinyin helpers.
 *
 * Internally a syllable is written as letters + tone digit, with ü spelled "v":
 * "xue2", "lv4", "ma5" (5 = neutral tone). Stored word pinyin uses tone marks with one space
 * between syllables ("xué xí"), which is what [normalizeToMarked] produces.
 */
object Pinyin {
    private val TONE_MARKS: Map<Char, String> = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'v' to "ǖǘǚǜ",
    )

    /** Tone-marked vowel -> (plain letter, tone). */
    private val MARKED: Map<Char, Pair<Char, Int>> = buildMap {
        for ((base, marks) in TONE_MARKS) marks.forEachIndexed { i, c -> put(c, base to i + 1) }
        put('ń', 'n' to 2); put('ň', 'n' to 3); put('ǹ', 'n' to 4); put('ḿ', 'm' to 2)
    }

    private val SEPARATORS = Regex("[\\s'’`\\-·,.;:!?，。、！？；：]+")

    /** "xué" -> "xue2", "lǜ" -> "lv4", "ma" -> "ma5", "xue2" -> "xue2", "lu:4" -> "lv4". */
    fun toNumberedSyllable(syllable: String): String {
        var tone = 0
        val letters = StringBuilder()
        for (c in syllable.lowercase().replace("u:", "v").replace('ü', 'v')) {
            val marked = MARKED[c]
            when {
                marked != null -> { letters.append(marked.first); tone = marked.second }
                c in '1'..'5' -> tone = c - '0'
                c in 'a'..'z' -> letters.append(c)
            }
        }
        if (letters.isEmpty()) return ""
        return letters.toString() + (if (tone == 0) 5 else tone)
    }

    /** "xue2" -> "xué", "lv4" -> "lǚ"... "ma5" -> "ma", "Bei3" -> "Běi". Non-syllables pass through. */
    fun toMarkedSyllable(numbered: String): String {
        if (numbered.isEmpty() || numbered.none { it.isLetter() }) return numbered
        val capital = numbered.first().isUpperCase()
        val s = numbered.lowercase().replace("u:", "v")
        val tone = s.last().takeIf { it in '0'..'5' }?.let { it - '0' } ?: 5
        val letters = if (s.last().isDigit()) s.dropLast(1) else s
        val idx = if (tone in 1..4) markIndex(letters) else -1
        val out = if (idx < 0) {
            letters
        } else {
            letters.substring(0, idx) + TONE_MARKS.getValue(letters[idx])[tone - 1] + letters.substring(idx + 1)
        }.replace('v', 'ü')
        return if (capital) out.replaceFirstChar { it.uppercaseChar() } else out
    }

    /** Standard placement: a/e first, then the o of "ou", otherwise the last vowel. */
    private fun markIndex(letters: String): Int {
        letters.indexOf('a').let { if (it >= 0) return it }
        letters.indexOf('e').let { if (it >= 0) return it }
        letters.indexOf("ou").let { if (it >= 0) return it }
        for (i in letters.indices.reversed()) if (letters[i] in "iouv") return i
        return -1
    }

    /** CC-CEDICT style "xue2 xi2" -> "xué xí" (keeps punctuation tokens like "·"). */
    fun numberedToMarked(text: String): String =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { toMarkedSyllable(it) }

    /**
     * Any pinyin spelling - tone marks or numbers, with or without spaces/apostrophes -
     * as a list of numbered syllables: "xuéxí" / "xue2xi2" / "xue2 xi2" -> [xue2, xi2].
     */
    fun syllables(text: String): List<String> {
        val cleaned = text.lowercase().replace("u:", "v").replace('ü', 'v')
        return cleaned.split(SEPARATORS).filter { it.isNotEmpty() }.flatMap(::splitChunk)
    }

    /** Re-spells any pinyin input as tone-marked syllables separated by spaces ("Beijing" stays capitalised). */
    fun normalizeToMarked(text: String): String {
        val out = ArrayList<String>()
        for (chunk in text.replace("u:", "v").replace("U:", "V").split(SEPARATORS).filter { it.isNotEmpty() }) {
            val capital = chunk.first().isUpperCase()
            splitChunk(chunk.lowercase().replace('ü', 'v')).forEachIndexed { i, syllable ->
                val marked = toMarkedSyllable(syllable)
                out.add(if (i == 0 && capital) marked.replaceFirstChar { it.uppercaseChar() } else marked)
            }
        }
        return out.joinToString(" ")
    }

    /** Tone-marked syllables written together, the way dictionaries display a word: "xuéxí". */
    fun display(pinyin: String): String {
        val parts = pinyin.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val sb = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            // An apostrophe separates a syllable starting with a/e/o from the previous one.
            if (i > 0 && p.first().lowercaseChar().let { it in "aeoāáǎàēéěèōóǒò" }) sb.append('\'')
            sb.append(p)
        }
        return sb.toString()
    }

    /** Letters only, for dictionary search: "xué xí" / "xue2 xi2" -> "xuexi", "lǜ" -> "lv". */
    fun searchKey(text: String): String {
        val sb = StringBuilder()
        for (c in text.lowercase().replace("u:", "v").replace('ü', 'v')) {
            val marked = MARKED[c]
            when {
                marked != null -> sb.append(marked.first)
                c in 'a'..'z' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** True if [text] is made of pinyin syllables (optionally with tone marks or numbers). */
    fun looksLikePinyin(text: String): Boolean {
        val chunks = text.lowercase().replace("u:", "v").replace('ü', 'v').split(SEPARATORS).filter { it.isNotEmpty() }
        if (chunks.isEmpty()) return false
        return chunks.all { chunk ->
            chunk.all { it in 'a'..'z' || it in '0'..'5' || MARKED.containsKey(it) } &&
                PinyinSyllables.segment(lettersOf(chunk).first, emptySet()) != null
        }
    }

    /** Letters of a chunk with per-letter tone marks and the letter indexes followed by a tone digit. */
    private fun lettersOf(chunk: String): Triple<String, List<Int>, Map<Int, Int>> {
        val letters = StringBuilder()
        val tones = ArrayList<Int>()
        val digitAfter = HashMap<Int, Int>()
        for (c in chunk) {
            val marked = MARKED[c]
            when {
                marked != null -> { letters.append(marked.first); tones.add(marked.second) }
                c in 'a'..'z' -> { letters.append(c); tones.add(0) }
                c in '0'..'5' && letters.isNotEmpty() -> digitAfter[letters.length - 1] = if (c == '0') 5 else c - '0'
            }
        }
        return Triple(letters.toString(), tones, digitAfter)
    }

    private fun splitChunk(chunk: String): List<String> {
        val (letters, tones, digitAfter) = lettersOf(chunk)
        if (letters.isEmpty()) return emptyList()
        val ranges = PinyinSyllables.segment(letters, digitAfter.keys.map { it + 1 }.toSet())
            ?: return listOf(toNumberedSyllable(chunk))
        return ranges.map { r ->
            val tone = (r.first..r.last).map { tones[it] }.firstOrNull { it != 0 } ?: digitAfter[r.last] ?: 5
            letters.substring(r.first, r.last + 1) + tone
        }
    }

    /** Letters (tone-less, ü as v) and the explicit tones 1-4, in order. */
    data class Canonical(val letters: String, val tones: List<Int>)

    fun canonical(text: String): Canonical {
        val letters = StringBuilder()
        val tones = ArrayList<Int>()
        for (c in text.lowercase().replace("u:", "v").replace('ü', 'v')) {
            val marked = MARKED[c]
            when {
                marked != null -> { letters.append(marked.first); tones.add(marked.second) }
                c in 'a'..'z' -> letters.append(c)
                c in '1'..'4' -> tones.add(c - '0')
            }
        }
        return Canonical(letters.toString(), tones)
    }

    /**
     * Checks typed pinyin against a word's pinyin. Spacing, apostrophes, tone marks vs numbers
     * and ü/v/u: spellings don't matter. Neutral-tone syllables must be left unmarked.
     * 一 and 不 also accept their tone-sandhi forms (yí/yì, bú).
     */
    fun typedAnswerMatches(answer: String, targetPinyin: String, hanzi: String, requireTones: Boolean): Boolean {
        val typed = canonical(answer)
        val target = syllables(targetPinyin)
        if (target.isEmpty()) return false
        if (typed.letters != target.joinToString("") { it.dropLast(1) }) return false
        if (!requireTones) return true
        val chars = hanzi.codePoints().toArray()
        val aligned = chars.size == target.size
        val allowed = target.mapIndexedNotNull { i, syl ->
            val tone = syl.last() - '0'
            if (tone == 5) return@mapIndexedNotNull null
            when (if (aligned) chars[i] else 0) {
                '一'.code -> setOf(1, 2, 4)
                '不'.code -> setOf(2, 4)
                else -> setOf(tone)
            }
        }
        if (typed.tones.size != allowed.size) return false
        return typed.tones.zip(allowed).all { (tone, ok) -> tone in ok }
    }

    /** Whether a dictionary reading (e.g. "shi4") fits a target syllable; neutral tone matches any. */
    fun syllableMatches(target: String, reading: String): Boolean {
        if (target.isEmpty() || reading.isEmpty()) return false
        if (target.dropLast(1) != reading.dropLast(1)) return false
        val t = target.last()
        val r = reading.last()
        return t == r || t == '5' || r == '5'
    }
}
