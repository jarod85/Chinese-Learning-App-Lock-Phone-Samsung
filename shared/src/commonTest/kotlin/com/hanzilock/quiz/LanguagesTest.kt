package com.hanzilock.quiz

import com.hanzilock.data.ImportParser
import com.hanzilock.data.ParsedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseKanaTest {
    @Test
    fun convertsRomajiAndKatakana() {
        assertEquals("たべる", JapaneseKana.romajiToHiragana("taberu"))
        assertEquals("こんにちは", JapaneseKana.romajiToHiragana("konnichiha"))
        assertEquals("おんな", JapaneseKana.romajiToHiragana("onna"))
        assertEquals("きって", JapaneseKana.romajiToHiragana("kitte"))
        assertEquals("まっちゃ", JapaneseKana.romajiToHiragana("matcha"))
        assertEquals("しんぶん", JapaneseKana.romajiToHiragana("shinbun"))
        assertEquals("きょうと", JapaneseKana.romajiToHiragana("kyouto"))
        assertEquals("とうきょう", JapaneseKana.romajiToHiragana("tōkyō"))
        assertEquals("こおひい", JapaneseKana.normalizeReading("コーヒー"))
        assertEquals("かたかな", JapaneseKana.toHiragana("カタカナ"))
    }

    @Test
    fun checksReadings() {
        assertTrue(JapaneseKana.readingMatches("taberu", "たべる", strict = true))
        assertTrue(JapaneseKana.readingMatches("タベル", "たべる", strict = true))
        assertTrue(JapaneseKana.readingMatches("koohii", "コーヒー", strict = true))
        assertFalse(JapaneseKana.readingMatches("tokyo", "とうきょう", strict = true))
        assertTrue(JapaneseKana.readingMatches("tokyo", "とうきょう", strict = false))
        assertFalse(JapaneseKana.readingMatches("nomu", "たべる", strict = false))
    }
}

class PronunciationTest {
    private val none: (String) -> Set<String> = { emptySet() }

    @Test
    fun spelledLanguagesIgnoreCaseAccentsAndArticles() {
        assertTrue(Pronunciation.match("es", "el país", "", listOf("país"), listOf("País"), none).matched)
        assertTrue(Pronunciation.match("es", "el país", "", emptyList(), listOf("mi pais es grande"), none).matched)
        assertTrue(Pronunciation.match("fr", "être", "", emptyList(), listOf("Être"), none).matched)
        assertTrue(Pronunciation.match("ko", "하다", "", listOf("해요"), listOf("해요"), none).matched)
        assertFalse(Pronunciation.match("it", "casa", "", emptyList(), listOf("cosa"), none).matched)
        assertFalse(Pronunciation.match("es", "mar", "", emptyList(), listOf("marco"), none).matched)
    }

    @Test
    fun japaneseAcceptsKanjiOrKana() {
        assertTrue(Pronunciation.match("ja", "食べる", "たべる", emptyList(), listOf("食べる"), none).matched)
        assertTrue(Pronunciation.match("ja", "食べる", "たべる", emptyList(), listOf("タベル"), none).matched)
        assertTrue(Pronunciation.match("ja", "コーヒー", "", emptyList(), listOf("コーヒー"), none).matched)
        assertFalse(Pronunciation.match("ja", "食べる", "たべる", emptyList(), listOf("飲む"), none).matched)
    }

    @Test
    fun sentenceUse() {
        assertTrue(Pronunciation.sentenceUses("es", "Vivo en un país pequeño.", "el país", listOf("país")))
        assertTrue(Pronunciation.sentenceUses("ko", "시간이 참 빨라요.", "시간", listOf("시간이")))
        assertFalse(Pronunciation.sentenceUses("fr", "Je mange.", "boire", emptyList()))
        assertTrue(Pronunciation.sentenceUses("zh", "我 每天 学习。", "学习", emptyList()))
        // Korean particles and endings attach to the word.
        assertTrue(Pronunciation.sentenceUses("ko", "나는 발이 차다.", "나", emptyList()))
        assertTrue(Pronunciation.sentenceUses("ko", "공부를 하고 싶어요.", "하다", emptyList()))
        // Contractions (하 + 어요 = 해요) come from the word's forms.
        assertTrue(Pronunciation.sentenceUses("ko", "그는 매일 운동해요.", "하다", listOf("해요")))
        assertFalse(Pronunciation.sentenceUses("ko", "책을 읽어요.", "시간", emptyList()))
        // Japanese in kanji or kana.
        assertTrue(Pronunciation.sentenceUses("ja", "たべるのが好きです。", "食べる", emptyList(), "たべる"))
        assertFalse(Pronunciation.sentenceUses("ja", "水を飲む。", "食べる", emptyList(), "たべる"))
    }
}

class MultilingualTilesTest {
    @Test
    fun japaneseChunks() {
        assertEquals(listOf("明日は", "雨が", "降るでしょう。"), SentenceTiles.japaneseChunks("明日は雨が降るでしょう。"))
        assertEquals(listOf("あさって", "来てください。"), SentenceTiles.japaneseChunks("あさって来てください。"))
        assertEquals(listOf("トムの", "誕生日だ。"), SentenceTiles.tokens("トムの誕生日だ。", "", japanese = true))
    }

    @Test
    fun spacedLanguagesKeepTheirSpaces() {
        assertEquals("Vivo en un país pequeño.", SentenceTiles.display("Vivo en  un país pequeño. ", spaced = true))
        assertEquals("あさって来てください。", SentenceTiles.display("あさって 来て ください。"))
        assertEquals(listOf("Él", "no", "es", "mi", "tipo."), SentenceTiles.tokens("Él no es mi tipo.", "ser"))
    }
}

class AlternativeReadingsTest {
    @Test
    fun officialNeutralTonesAreAccepted() {
        val readings = listOf("zhī dào", "zhī dao")
        assertTrue(Pinyin.typedMatchesAny("zhi1dao4", readings, "知道", true))
        assertTrue(Pinyin.typedMatchesAny("zhi1dao", readings, "知道", true))
        assertFalse(Pinyin.typedMatchesAny("zhi1dao1", readings, "知道", true))
        assertFalse(Pinyin.typedMatchesAny("zhi1dao", listOf("zhī dào"), "知道", true))
    }
}

class ImportFormatsTest {
    @Test
    fun plainListsInAnyScript() {
        assertEquals(listOf("学习", "喜欢", "朋友"), ImportParser.parseText("学习\n喜欢, 朋友").map { it.term })
        assertEquals(listOf("por favor", "gracias", "casa"), ImportParser.parseText("por favor\ngracias,casa").map { it.term })
        assertEquals(listOf("食べる", "飲む"), ImportParser.parseText("食べる、飲む").map { it.term })
    }

    @Test
    fun headerRowsNameTheColumns() {
        assertEquals(
            listOf(ParsedWord("casa", meaning = "house"), ParsedWord("perro", meaning = "dog")),
            ImportParser.parseText("word,meaning\ncasa,house\nperro,dog"),
        )
        assertEquals(
            listOf(ParsedWord("食べる", reading = "たべる", meaning = "to eat")),
            ImportParser.parseText("Japanese,Reading,English\n食べる,たべる,to eat"),
        )
    }

    @Test
    fun japaneseTsvWithKanaReading() {
        assertEquals(listOf(ParsedWord("食べる", reading = "たべる", meaning = "to eat")), ImportParser.parseText("食べる\tたべる\tto eat"))
    }

    @Test
    fun oneColumnListWithAHeading() {
        assertEquals(listOf("你好", "谢谢"), ImportParser.parseText("${ImportParser.BOM}Hanzi\n你好\n谢谢\n").map { it.term })
        assertEquals(listOf("casa"), ImportParser.parseText("Words\ncasa").map { it.term })
    }
}
