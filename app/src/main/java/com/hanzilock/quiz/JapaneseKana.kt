package com.hanzilock.quiz

/**
 * Kana helpers for checking a typed Japanese reading: katakana and romaji (Hepburn, Kunrei or
 * the usual IME spellings like "toukyou", "tōkyō", "kyouto") are all turned into hiragana.
 */
object JapaneseKana {
    fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゟ'
    fun isKatakana(c: Char): Boolean = c in 'ァ'..'ヿ'
    fun isKana(c: Char): Boolean = isHiragana(c) || isKatakana(c)

    /** カタカナ -> かたかな (ー stays). */
    fun toHiragana(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c)
    }

    private val VOWEL_ROW: Map<Char, Char> = buildMap {
        "あかさたなはまやらわがざだばぱぁゃ".forEach { put(it, 'あ') }
        "いきしちにひみりぎじぢびぴぃ".forEach { put(it, 'い') }
        "うくすつぬふむゆるぐずづぶぷぅゅゔ".forEach { put(it, 'う') }
        "えけせてねへめれげぜでべぺぇ".forEach { put(it, 'え') }
        "おこそとのほもよろをごぞどぼぽぉょ".forEach { put(it, 'お') }
    }

    /** Replaces the long-vowel mark with the vowel it lengthens: こーひー -> こおひい. */
    fun expandLongVowels(s: String): String = buildString(s.length) {
        for (c in s) {
            if (c == 'ー' && isNotEmpty()) append(VOWEL_ROW[last()] ?: 'ー') else append(c)
        }
    }

    private val TABLE: Map<String, String> = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "she" to "しぇ", "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "che" to "ちぇ", "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "cya" to "ちゃ", "cyu" to "ちゅ", "cyo" to "ちょ",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の", "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ", "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も", "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ", "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        "la" to "ら", "li" to "り", "lu" to "る", "le" to "れ", "lo" to "ろ",
        "wa" to "わ", "wo" to "を", "wi" to "うぃ", "we" to "うぇ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご", "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ", "je" to "じぇ", "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "dzu" to "づ", "de" to "で", "do" to "ど",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ", "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ", "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
        "xtsu" to "っ", "xtu" to "っ", "ltu" to "っ", "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
    )

    private const val VOWELS = "aiueo"

    fun romajiToHiragana(input: String): String {
        val s = input.lowercase()
            .replace("ā", "aa").replace("ī", "ii").replace("ū", "uu").replace("ē", "ei").replace("ō", "ou")
            .replace("â", "aa").replace("î", "ii").replace("û", "uu").replace("ê", "ei").replace("ô", "ou")
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c !in 'a'..'z') {
                out.append(if (c == '-') 'ー' else c)
                i++
                continue
            }
            val next = s.getOrNull(i + 1)
            // Doubled consonant (kk, tt, pp, ss, tch) -> small tsu.
            if (c !in VOWELS && c != 'n' && (next == c || (c == 't' && next == 'c'))) {
                out.append('っ')
                i++
                continue
            }
            if (c == 'n' && (next == null || (next !in VOWELS && next != 'y'))) {
                out.append('ん')
                i += if (next == '\'') 2 else 1
                continue
            }
            var matched = false
            for (len in 4 downTo 1) {
                if (i + len > s.length) continue
                val kana = TABLE[s.substring(i, i + len)] ?: continue
                out.append(kana)
                i += len
                matched = true
                break
            }
            if (!matched) {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Any spelling of a reading as plain hiragana, long vowels written out, spaces dropped. */
    fun normalizeReading(s: String): String =
        expandLongVowels(toHiragana(romajiToHiragana(s.trim()))).filter { isKana(it) }

    /** "toukyou" matches "tokyo" when lenient: long vowels (おう/うう/ああ...) collapsed. */
    private fun collapseLong(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val prev = sb.lastOrNull()
            val prevVowel = prev?.let { VOWEL_ROW[it] }
            val redundant = (c == 'う' && (prevVowel == 'お' || prevVowel == 'う')) || (c == 'い' && prevVowel == 'え') ||
                (c in "あいうえお" && prevVowel == c)
            if (!redundant) sb.append(c)
        }
        return sb.toString()
    }

    fun readingMatches(typed: String, reading: String, strict: Boolean): Boolean {
        val a = normalizeReading(typed)
        val b = normalizeReading(reading)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (!strict && collapseLong(a) == collapseLong(b))
    }
}
