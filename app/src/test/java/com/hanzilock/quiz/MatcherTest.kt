package com.hanzilock.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningMatcherTest {
    private fun ok(answer: String, vararg meanings: String) =
        assertTrue("'$answer' should match ${meanings.toList()}", MeaningMatcher.matches(answer, meanings.toList()))

    private fun no(answer: String, vararg meanings: String) =
        assertFalse("'$answer' should not match ${meanings.toList()}", MeaningMatcher.matches(answer, meanings.toList()))

    @Test
    fun acceptsFormsSynonymsAndTypos() {
        ok("study", "to study", "to learn")
        ok("learning", "to study", "to learn")
        ok("To Learn", "to study", "to learn")
        ok("techer", "teacher")
        ok("phone", "mobile phone")
        ok("very good", "good", "well")
        ok("big", "large")
        ok("mom", "mother")
        ok("o'clock", "o'clock", "point")
        ok("oclock", "o'clock", "point")
        ok("see you", "goodbye", "see you")
        ok("thanks", "thank you", "thanks")
        ok("you're welcome", "you're welcome", "don't mention it")
        ok("happy, glad", "happy", "glad")
        ok("to make a phone call", "to make a phone call", "to call")
        ok("a little", "a little", "a bit")
        ok("studied", "to study")
    }

    @Test
    fun rejectsWrongAnswers() {
        no("not good", "good")
        no("cat", "dog")
        no("sad", "happy", "glad")
        no("", "tea")
        no("tomorrow", "yesterday")
        no("buy", "to sell")
    }

    @Test
    fun cleansCedictStyleDefinitions() {
        assertEquals(listOf("to study", "to learn"), MeaningMatcher.definitions(listOf("to study; to learn")))
        assertEquals(listOf("teacher"), MeaningMatcher.definitions(listOf("teacher (CL:個|个[ge4])")))
        ok("teacher", "teacher (CL:個|个[ge4])")
    }
}

class SpeechMatcherTest {
    private val readings = mapOf(
        "事" to setOf("shi4"), "是" to setOf("shi4"), "十" to setOf("shi2"),
        "冬" to setOf("dong1"), "西" to setOf("xi1"), "学" to setOf("xue2"), "习" to setOf("xi2"),
    )
    private val lookup: (String) -> Set<String> = { readings[it].orEmpty() }

    @Test
    fun exactCharacters() {
        assertTrue(SpeechMatcher.match(listOf("学习"), "学习", listOf("xue2", "xi2"), lookup).matched)
        assertTrue(SpeechMatcher.match(listOf("学习。"), "学习", listOf("xue2", "xi2"), lookup).matched)
        assertTrue(SpeechMatcher.match(listOf("我在学习"), "学习", listOf("xue2", "xi2"), lookup).matched)
    }

    @Test
    fun homophonesCountButOtherTonesDont() {
        assertTrue(SpeechMatcher.match(listOf("事"), "是", listOf("shi4"), lookup).matched)
        assertFalse(SpeechMatcher.match(listOf("十"), "是", listOf("shi4"), lookup).matched)
        // Neutral tone in the target matches any tone.
        assertTrue(SpeechMatcher.match(listOf("冬西"), "东西", listOf("dong1", "xi5"), lookup).matched)
    }

    @Test
    fun laterCandidatesAreChecked() {
        val r = SpeechMatcher.match(listOf("十", "事"), "是", listOf("shi4"), lookup)
        assertTrue(r.matched)
        assertEquals("事", r.heard)
    }

    @Test
    fun digitsAreReadAsChinese() {
        assertTrue(SpeechMatcher.match(listOf("3"), "三", listOf("san1"), lookup).matched)
        assertEquals("十", SpeechMatcher.numberToHanzi("10"))
        assertEquals("十一", SpeechMatcher.numberToHanzi("11"))
        assertEquals("二十", SpeechMatcher.numberToHanzi("20"))
        assertEquals("一百零五", SpeechMatcher.numberToHanzi("105"))
        assertEquals("一百一十", SpeechMatcher.numberToHanzi("110"))
        assertEquals("一千零五", SpeechMatcher.numberToHanzi("1005"))
        assertEquals("零", SpeechMatcher.numberToHanzi("0"))
    }
}

class SentenceTilesTest {
    @Test
    fun spacesMarkWordBoundaries() {
        assertEquals(listOf("我", "每天", "学习", "汉语。"), SentenceTiles.tokens("我 每天 学习 汉语。", "学习"))
        assertEquals(
            listOf("不客气，", "这", "是", "我", "应该", "做", "的。"),
            SentenceTiles.tokens("不客气， 这 是 我 应该 做 的。", "不客气"),
        )
    }

    @Test
    fun segmentsWithTheDictionary() {
        val words = setOf("汉语", "每天")
        assertEquals(
            listOf("我", "每天", "学习", "汉语", "。"),
            SentenceTiles.segment("我每天学习汉语。", "学习") { it in words },
        )
        assertEquals(listOf("我", "每天", "学习", "汉语。"), SentenceTiles.tokens("我每天学习汉语。", "学习") { it in words })
    }

    @Test
    fun targetStaysOneTileWithoutDictionary() {
        assertEquals(listOf("我", "学习", "。"), SentenceTiles.segment("我学习。", "学习"))
    }

    @Test
    fun displayRemovesBoundarySpaces() {
        assertEquals("我每天学习汉语。", SentenceTiles.display("我 每天 学习 汉语。"))
        assertEquals("不客气，这是我应该做的。", SentenceTiles.display("不客气， 这 是 我 应该 做 的。"))
        assertEquals("我用 iPhone 手机", SentenceTiles.display("我 用 iPhone 手机"))
        assertEquals("I like 茶", SentenceTiles.display("I like 茶"))
    }

    @Test
    fun longSentencesAreMergedIntoFewerTiles() {
        val tokens = SentenceTiles.tokens("一 二 三 四 五 六 七 八 九 十 百 千", "五", maxTiles = 9)
        assertEquals(9, tokens.size)
        assertTrue("五" in tokens)
        assertEquals("一二三四五六七八九十百千", tokens.joinToString(""))
    }

    @Test
    fun checkingOrder() {
        val tokens = listOf("我", "的", "我")
        assertTrue(SentenceTiles.isCorrect(tokens, listOf(2, 1, 0)))
        assertFalse(SentenceTiles.isCorrect(tokens, listOf(1, 0, 2)))
        assertFalse(SentenceTiles.isCorrect(tokens, listOf(0, 1)))
        repeat(20) { assertNotEquals((0 until 5).toList(), SentenceTiles.shuffledOrder(5)) }
    }
}

class SrsTest {
    @Test
    fun winsClimbAndLossesReset() {
        val (box, due) = Srs.schedule(0, correct = true, now = 0)
        assertEquals(2, box)
        assertEquals(4 * 60 * 60_000L, due)
        assertEquals(1 to 10 * 60_000L, Srs.schedule(5, correct = false, now = 0))
        assertEquals(Srs.MAX_BOX, Srs.schedule(Srs.MAX_BOX, correct = true, now = 0).first)
    }
}
