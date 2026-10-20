package com.hanzilock.data

import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.Pronunciation
import com.hanzilock.quiz.SpeechMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled word sets (content/sets) are complete and consistent. */
class SetFilesTest {
    private val content = listOf(File("../content"), File("content")).first { File(it, "sets/index.json").exists() }
    private val index: SetIndex = AppJson.decodeFromString(File(content, "sets/index.json").readText(Charsets.UTF_8))

    private fun load(info: SetInfo): SetFile = AppJson.decodeFromString(File(content, info.file).readText(Charsets.UTF_8))

    @Test
    fun indexCoversEveryLanguageAndLevel() {
        val langs = index.languages.map { it.code }.toSet()
        assertTrue(langs.containsAll(listOf("zh", "ja", "ko", "es", "fr", "it")))
        index.sets.forEach { assertTrue("${it.key} has undeclared language", it.lang in langs) }
        assertEquals((1..6).map { "zh.hsk$it" }, index.sets.filter { it.lang == "zh" && !it.custom }.map { it.key })
        assertEquals(listOf("n5", "n4", "n3", "n2", "n1").map { "ja.jlpt-$it" }, index.sets.filter { it.lang == "ja" }.map { it.key })
        assertTrue("only HSK 1 is on by default", index.sets.filter { it.enabled }.map { it.key } == listOf("zh.hsk1"))
    }

    @Test
    fun everySetFileMatchesTheIndex() {
        for (info in index.sets) {
            val file = load(info)
            assertEquals(info.key, file.key)
            assertEquals(info.lang, file.lang)
            assertEquals("${info.key} count", info.count, file.words.size)
            assertEquals("${info.key} rev", info.rev, file.rev)
            assertEquals("${info.key} has duplicate terms", file.words.size, file.words.map { it.term }.toSet().size)
        }
    }

    @Test
    fun wordsAreUsable() {
        val problems = ArrayList<String>()
        for (info in index.sets.filter { !it.custom }) {
            for (w in load(info).words) {
                if (w.meanings.isEmpty()) problems += "${info.key}: ${w.term} has no meaning"
                for (ex in w.examples) {
                    if (!Pronunciation.sentenceUses(info.lang, ex.text, w.term, w.forms, w.reading.orEmpty())) {
                        problems += "${info.key}: example for ${w.term} doesn't use it: ${ex.text}"
                    }
                }
            }
        }
        assertNoProblems(problems)
    }

    @Test
    fun chinesePinyinMatchesTheCharacters() {
        val problems = ArrayList<String>()
        for (info in index.sets.filter { it.lang == "zh" }) {
            for (w in load(info).words) {
                val reading = w.reading
                if (reading.isNullOrBlank()) { problems += "${w.term} has no pinyin"; continue }
                if (Pinyin.normalizeToMarked(reading) != reading) problems += "pinyin not normalized for ${w.term}: $reading"
                for (alt in w.forms) {
                    if (!Pinyin.looksLikePinyin(alt) || Pinyin.syllables(alt).size != Pinyin.syllables(reading).size) {
                        problems += "alternative reading for ${w.term}: $alt"
                    }
                }
                val han = SpeechMatcher.hanOnly(w.term)
                if (han.length == w.term.length && han.codePointCount(0, han.length) != Pinyin.syllables(reading).size) {
                    problems += "syllables for ${w.term}: $reading"
                }
            }
        }
        assertNoProblems(problems)
    }

    private fun assertNoProblems(problems: List<String>) =
        assertTrue("${problems.size} problems, e.g.\n" + problems.take(15).joinToString("\n"), problems.isEmpty())

    @Test
    fun hsk1KeepsTheHandWrittenExamples() {
        val hsk1 = load(index.sets.first { it.key == "zh.hsk1" })
        val study = hsk1.words.first { it.term == "学习" }
        assertEquals("xué xí", study.reading)
        assertEquals("我 每天 都 学习 汉语。", study.examples.first().text)
        assertTrue(hsk1.words.size >= 150)
    }
}
