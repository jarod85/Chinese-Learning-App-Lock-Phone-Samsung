package com.hanzilock.quiz

/**
 * Offline check of an English meaning answer against a word's definitions.
 *
 * Deliberately a little lenient (word forms, small typos, common synonyms, "phone" for
 * "mobile phone"), but negations must agree ("not good" is not "good"). When Claude grading is
 * enabled, a "no" from here gets a second opinion from the model.
 */
object MeaningMatcher {
    private val STOP = setOf(
        "to", "a", "an", "the", "be", "of", "sb", "sth", "someone", "somebody", "something",
        "one", "one's", "oneself", "etc", "is", "are", "am", "it",
    )
    private val NEGATIONS = setOf(
        "not", "no", "never", "don't", "dont", "doesn't", "doesnt", "isn't", "isnt", "cannot",
        "can't", "cant", "without", "non", "won't", "wont",
    )

    /** Small synonym table: every word maps to the first word of its group. */
    private val SYNONYMS: Map<String, String> = listOf(
        "big large huge",
        "small little tiny",
        "mom mother mum mommy mama",
        "dad father daddy papa",
        "child kid",
        "taxi cab",
        "movie film",
        "tv television",
        "shop store",
        "phone telephone cellphone",
        "computer pc laptop",
        "hello hi",
        "goodbye bye",
        "thank thanks",
        "happy glad pleased",
        "beautiful pretty lovely",
        "speak talk say",
        "home house",
        "cup glass mug",
        "friend pal buddy",
        "doctor physician",
        "rain rainy",
        "cold chilly",
        "many much lots",
        "study learn",
        "reside live",
    ).flatMap { group ->
        val words = group.split(' ')
        words.map { stem(it) to stem(words.first()) }
    }.toMap()

    fun matches(answer: String, meanings: List<String>): Boolean {
        val answers = answer.split(Regex("[;,/]|\\bor\\b")).map(::normalize).filter { it.isNotBlank() }
        val definitions = definitions(meanings)
        return answers.any { a -> definitions.any { d -> similar(a, d) } }
    }

    /** Splits "to study; to learn / CL:個" style entries into single normalized definitions. */
    fun definitions(meanings: List<String>): List<String> =
        meanings.flatMap { it.split(';', '/', ',') }.map(::normalize).filter { it.isNotBlank() }.distinct()

    fun normalize(s: String): String {
        var t = s.lowercase().replace('’', '\'')
        t = t.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("\\bcl:.*"), " ")
        t = t.replace(Regex("\\b(lit|fig|abbr|coll|colloquial|dialect|archaic)\\b\\.?"), " ")
        t = t.replace(Regex("[^a-z0-9' ]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun stem(word: String): String {
        var s = word.removeSuffix("'s")
        when {
            s.length > 4 && s.endsWith("ies") -> return s.dropLast(3) + "y"
            s.length > 4 && s.endsWith("ied") -> return s.dropLast(3) + "y"
            s.length > 4 && s.endsWith("ing") -> s = s.dropLast(3)
            s.length > 4 && s.endsWith("ed") -> s = s.dropLast(2)
            s.length > 4 && (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("ches") ||
                s.endsWith("shes") || s.endsWith("oes")) -> s = s.dropLast(2)
            s.length > 3 && s.endsWith("s") && !s.endsWith("ss") -> s = s.dropLast(1)
        }
        if (s.length > 3 && s[s.length - 1] == s[s.length - 2] && s.last() !in "lsz") s = s.dropLast(1)
        if (s.length > 4 && s.endsWith("ly")) s = s.dropLast(2)
        return s
    }

    private fun tokens(normalized: String): List<String> =
        normalized.split(' ')
            .filter { it.isNotEmpty() && it !in STOP }
            .map { if (it in NEGATIONS) it else stem(it).let { s -> SYNONYMS[s] ?: s } }

    private fun similar(answer: String, definition: String): Boolean {
        if (answer == definition) return true
        val a = tokens(answer)
        val d = tokens(definition)
        if (a.isEmpty() || d.isEmpty()) {
            return editDistance(answer, definition) <= allowedTypos(definition)
        }
        if (a.any { it in NEGATIONS } != d.any { it in NEGATIONS }) return false
        val aSet = a.toSet()
        val dSet = d.toSet()
        val common = aSet.count { x -> dSet.any { y -> tokenEquals(x, y) } }
        val extra = aSet.size - common
        if (common == 0) return false
        // Same words (allowing word forms and typos).
        if (extra == 0 && common == dSet.size) return true
        // Part of a longer definition: "phone" for "mobile phone", "look" for "to look at".
        if (extra == 0 && common * 2 >= dSet.size) return true
        // Whole definition plus one extra word: "very good" for "good".
        return common == dSet.size && extra <= 1
    }

    private fun tokenEquals(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && editDistance(a, b) <= 1)

    private fun allowedTypos(s: String): Int = when {
        s.length >= 9 -> 2
        s.length >= 5 -> 1
        else -> 0
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
