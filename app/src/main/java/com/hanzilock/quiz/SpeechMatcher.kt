package com.hanzilock.quiz

/**
 * Decides whether what the speech recognizer heard is the target word.
 *
 * Recognizers often return a homophone (是 -> 事) for a single word, so besides an exact
 * character match we accept any recognized characters whose dictionary readings spell the target
 * pinyin, tones included (a neutral tone matches anything).
 */
object SpeechMatcher {
    data class Result(val matched: Boolean, val heard: String)

    /**
     * @param candidates recognizer results, best first
     * @param targetSyllables numbered syllables of the target, e.g. [xue2, xi2]
     * @param readings numbered readings of one character (as a String, to allow surrogate pairs)
     */
    fun match(
        candidates: List<String>,
        hanzi: String,
        targetSyllables: List<String>,
        readings: (String) -> Set<String>,
    ): Result {
        val target = hanOnly(hanzi)
        val heard = candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (target.isEmpty()) return Result(false, heard)
        for (candidate in candidates) {
            val han = hanOnly(normalizeNumbers(candidate))
            if (han.isEmpty()) continue
            if (han.contains(target)) return Result(true, candidate.trim())
            if (soundsLike(han, target, targetSyllables, readings)) return Result(true, candidate.trim())
        }
        return Result(false, heard)
    }

    private fun soundsLike(
        heard: String,
        target: String,
        targetSyllables: List<String>,
        readings: (String) -> Set<String>,
    ): Boolean {
        val targetChars = codePointStrings(target)
        if (targetChars.size != targetSyllables.size) return false
        val heardChars = codePointStrings(heard)
        val n = targetChars.size
        for (start in 0..heardChars.size - n) {
            val fits = (0 until n).all { i ->
                val ch = heardChars[start + i]
                ch == targetChars[i] || readings(ch).any { Pinyin.syllableMatches(targetSyllables[i], it) }
            }
            if (fits) return true
        }
        return false
    }

    fun isHan(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    fun hanOnly(s: String): String {
        val sb = StringBuilder()
        s.codePoints().forEach { if (isHan(it)) sb.appendCodePoint(it) }
        return sb.toString()
    }

    private fun codePointStrings(s: String): List<String> {
        val out = ArrayList<String>()
        s.codePoints().forEach { out.add(String(Character.toChars(it))) }
        return out
    }

    /** Recognizers write numbers as digits ("3", "10"); spell them the Chinese way. */
    fun normalizeNumbers(s: String): String = Regex("\\d+").replace(s) { numberToHanzi(it.value) }

    private const val DIGITS = "零一二三四五六七八九"

    fun numberToHanzi(digits: String): String {
        val n = digits.toIntOrNull()
        if (n == null || n > 9999 || (digits.length > 1 && digits.startsWith("0"))) {
            return digits.map { DIGITS[it - '0'] }.joinToString("")
        }
        if (n == 0) return "零"
        val sb = StringBuilder()
        var rest = n
        var zeroPending = false
        for ((unit, name) in listOf(1000 to '千', 100 to '百', 10 to '十')) {
            val d = rest / unit
            rest %= unit
            if (d > 0) {
                if (zeroPending) { sb.append('零'); zeroPending = false }
                if (!(unit == 10 && d == 1 && sb.isEmpty())) sb.append(DIGITS[d])
                sb.append(name)
            } else if (sb.isNotEmpty() && rest > 0) {
                zeroPending = true
            }
        }
        if (rest > 0) {
            if (zeroPending) sb.append('零')
            sb.append(DIGITS[rest])
        }
        return sb.toString()
    }
}
