package com.hanzilock.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinTest {
    @Test
    fun numberedSyllables() {
        assertEquals("xue2", Pinyin.toNumberedSyllable("xué"))
        assertEquals("lv4", Pinyin.toNumberedSyllable("lǜ"))
        assertEquals("lv4", Pinyin.toNumberedSyllable("lu:4"))
        assertEquals("ma5", Pinyin.toNumberedSyllable("ma"))
        assertEquals("bei3", Pinyin.toNumberedSyllable("Běi"))
    }

    @Test
    fun markedSyllables() {
        assertEquals("xué", Pinyin.toMarkedSyllable("xue2"))
        assertEquals("lǜ", Pinyin.toMarkedSyllable("lv4"))
        assertEquals("lǜ", Pinyin.toMarkedSyllable("lu:4"))
        assertEquals("gǒu", Pinyin.toMarkedSyllable("gou3"))
        assertEquals("liù", Pinyin.toMarkedSyllable("liu4"))
        assertEquals("guì", Pinyin.toMarkedSyllable("gui4"))
        assertEquals("ma", Pinyin.toMarkedSyllable("ma5"))
        assertEquals("Běi", Pinyin.toMarkedSyllable("Bei3"))
        assertEquals("r", Pinyin.toMarkedSyllable("r5"))
        assertEquals("xué xí", Pinyin.numberedToMarked("xue2 xi2"))
    }

    @Test
    fun splitsRunTogetherPinyin() {
        assertEquals(listOf("xue2", "xi2"), Pinyin.syllables("xuéxí"))
        assertEquals(listOf("xue2", "xi2"), Pinyin.syllables("xue2xi2"))
        assertEquals(listOf("xue2", "xi2"), Pinyin.syllables("xue2 xi2"))
        assertEquals(listOf("xi1", "an1"), Pinyin.syllables("Xī'ān"))
        assertEquals(listOf("nv3", "er2"), Pinyin.syllables("nǚ'ér"))
        assertEquals(listOf("yi1", "dian3", "r5"), Pinyin.syllables("yīdiǎnr"))
        assertEquals(listOf("dong1", "xi5"), Pinyin.syllables("dōngxi"))
        assertEquals(listOf("xian1"), Pinyin.syllables("xiān"))
        assertEquals(listOf("fan5", "gan5"), Pinyin.syllables("fangan"))
        assertEquals(listOf("zhong1", "guo2"), Pinyin.syllables("zhong1guo2"))
    }

    @Test
    fun normalizesToMarkedKeepingCapitals() {
        assertEquals("xué xí", Pinyin.normalizeToMarked("xue2xi2"))
        assertEquals("Běi jīng", Pinyin.normalizeToMarked("Bei3jing1"))
        assertEquals("Běi jīng", Pinyin.normalizeToMarked("Běijīng"))
        assertEquals("nǚ ér", Pinyin.normalizeToMarked("nu:3 er2"))
    }

    @Test
    fun displayJoinsSyllablesWithApostrophes() {
        assertEquals("xuéxí", Pinyin.display("xué xí"))
        assertEquals("nǚ'ér", Pinyin.display("nǚ ér"))
        assertEquals("Xī'ān", Pinyin.display("Xī ān"))
        assertEquals("yīdiǎnr", Pinyin.display("yī diǎn r"))
    }

    @Test
    fun typedAnswers() {
        assertTrue(Pinyin.typedAnswerMatches("xue2xi2", "xué xí", "学习", true))
        assertTrue(Pinyin.typedAnswerMatches("xuéxí", "xué xí", "学习", true))
        assertTrue(Pinyin.typedAnswerMatches("Xue2 Xi2", "xué xí", "学习", true))
        assertFalse(Pinyin.typedAnswerMatches("xuexi", "xué xí", "学习", true))
        assertTrue(Pinyin.typedAnswerMatches("xuexi", "xué xí", "学习", false))
        assertFalse(Pinyin.typedAnswerMatches("xue2xi4", "xué xí", "学习", true))
        assertFalse(Pinyin.typedAnswerMatches("xue2", "xué xí", "学习", true))
        // Neutral tones must stay unmarked.
        assertTrue(Pinyin.typedAnswerMatches("dong1xi", "dōng xi", "东西", true))
        assertTrue(Pinyin.typedAnswerMatches("dong1xi5", "dōng xi", "东西", true))
        assertFalse(Pinyin.typedAnswerMatches("dong1xi1", "dōng xi", "东西", true))
        // Tone sandhi of 不 / 一 is accepted.
        assertTrue(Pinyin.typedAnswerMatches("bu2ke4qi", "bù kè qi", "不客气", true))
        assertTrue(Pinyin.typedAnswerMatches("bu4ke4qi", "bù kè qi", "不客气", true))
        assertTrue(Pinyin.typedAnswerMatches("yi4dian3r", "yī diǎn r", "一点儿", true))
        // ü spellings.
        assertTrue(Pinyin.typedAnswerMatches("lv4", "lǜ", "绿", true))
        assertTrue(Pinyin.typedAnswerMatches("lu:4", "lǜ", "绿", true))
        assertFalse(Pinyin.typedAnswerMatches("lu4", "lǜ", "绿", true))
    }

    @Test
    fun searchHelpers() {
        assertEquals("xuexi", Pinyin.searchKey("xué xí"))
        assertEquals("xuexi", Pinyin.searchKey("xue2 xi2"))
        assertEquals("lv", Pinyin.searchKey("lǜ"))
        assertTrue(Pinyin.looksLikePinyin("xuexi"))
        assertTrue(Pinyin.looksLikePinyin("xue2 xi2"))
        assertFalse(Pinyin.looksLikePinyin("hello"))
        assertFalse(Pinyin.looksLikePinyin("study"))
    }

    @Test
    fun syllableMatching() {
        assertTrue(Pinyin.syllableMatches("shi4", "shi4"))
        assertFalse(Pinyin.syllableMatches("shi4", "shi2"))
        assertTrue(Pinyin.syllableMatches("xi5", "xi1"))
        assertFalse(Pinyin.syllableMatches("xi5", "xu1"))
    }
}
