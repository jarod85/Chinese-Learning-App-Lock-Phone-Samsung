package com.hanzilock.core

import com.hanzilock.data.CedictFormat
import com.hanzilock.data.ImportParser
import com.hanzilock.data.ParsedWord
import com.hanzilock.quiz.Pinyin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleMathTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val times = listOf(8 * 60, 13 * 60, 19 * 60)
    private fun at(h: Int, m: Int = 0, day: Int = 29) = ZonedDateTime.of(2026, 9, day, h, m, 0, 0, zone)

    @Test
    fun mostRecentReset() {
        assertEquals(at(8), ScheduleMath.mostRecentReset(times, at(10)))
        assertEquals(at(13), ScheduleMath.mostRecentReset(times, at(13)))
        assertEquals(at(19, day = 28), ScheduleMath.mostRecentReset(times, at(7, 59)))
        assertNull(ScheduleMath.mostRecentReset(emptyList(), at(10)))
    }

    @Test
    fun nextReset() {
        assertEquals(at(13), ScheduleMath.nextReset(times, at(10)))
        assertEquals(at(8, day = 30), ScheduleMath.nextReset(times, at(20)))
    }

    @Test
    fun evenlySpaced() {
        assertEquals(listOf(8 * 60, 14 * 60, 20 * 60), ScheduleMath.evenlySpaced(3, 8 * 60, 20 * 60))
        assertEquals(listOf(9 * 60), ScheduleMath.evenlySpaced(1, 9 * 60, 20 * 60))
        assertEquals("08:05", ScheduleMath.format(8 * 60 + 5))
    }
}

class EmailRulesTest {
    private val notification = "Jane Doe\nBudget review tomorrow\nme@work.com"

    @Test
    fun matchesCaseInsensitively() {
        assertTrue(EmailRules.matches(notification, listOf("ME@work.com")))
        assertTrue(EmailRules.matches(notification, listOf("nope", "jane doe")))
        assertTrue(EmailRules.matches(notification, listOf("*")))
        assertFalse(EmailRules.matches(notification, listOf("boss@work.com")))
        assertFalse(EmailRules.matches(notification, emptyList()))
        assertEquals(listOf("a", "b"), EmailRules.parse(" a \n\n b\n"))
    }
}

class ImportParserTest {
    @Test
    fun plecoExport() {
        val text = "// HSK 1\n学习[學習]\txue2xi2\tto study; to learn\n朋友\tpéngyou\tfriend\n"
        assertEquals(
            listOf(
                ParsedWord("学习", "學習", "xue2xi2", "to study; to learn"),
                ParsedWord("朋友", null, "péngyou", "friend"),
            ),
            ImportParser.parseText(text),
        )
    }

    @Test
    fun csvAndPlainLists() {
        assertEquals(
            listOf(ParsedWord("学习", null, "xué xí", "to study, to learn")),
            ImportParser.parseText("学习,xué xí,\"to study, to learn\""),
        )
        assertEquals(
            listOf("学习", "喜欢", "朋友"),
            ImportParser.parseText("学习 喜欢，朋友\n学习").map { it.term },
        )
    }
}

/** The bundled CC-CEDICT file parses with the app's line parser, and pinyin converts cleanly. */
class CedictFileTest {
    @Test
    fun bundledDictionaryParses() {
        val dir = listOf(File("../content/dictionary"), File("content/dictionary")).first { it.isDirectory }
        val gz = dir.listFiles { f -> f.name.endsWith(".gz") }!!.single()
        var entries = 0
        var rejected = 0
        var study: CedictFormat.Line? = null
        java.util.zip.GZIPInputStream(gz.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#") || line.isBlank()) continue
                val e = CedictFormat.parse(line)
                if (e == null) { rejected++; continue }
                entries++
                if (e.simplified == "学习") study = e
            }
        }
        assertTrue("only $entries entries", entries > 100_000)
        assertEquals("unparsable lines", 0, rejected)
        val s = study!!
        assertEquals("xue2 xi2", s.pinyin)
        assertEquals("xué xí", Pinyin.numberedToMarked(s.pinyin))
        assertEquals("學習", s.traditional)
    }
}
