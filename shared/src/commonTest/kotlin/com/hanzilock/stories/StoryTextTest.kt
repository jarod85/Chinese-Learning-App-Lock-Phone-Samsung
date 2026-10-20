package com.hanzilock.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryTextTest {
    /** A tiny dictionary: simplified words map to themselves, traditional forms to simplified. */
    private val dict = mapOf(
        "我" to "我", "们" to "们", "我们" to "我们", "是" to "是", "学生" to "学生", "学" to "学", "生" to "生",
        "研究" to "研究", "研究生" to "研究生", "生命" to "生命", "命" to "命", "起源" to "起源", "的" to "的",
        "們" to "们", "我們" to "我们", "學生" to "学生", "學" to "学", "今天" to "今天", "天气" to "天气", "很" to "很", "好" to "好",
    )
    private val lookup: (String) -> String? = { dict[it] }

    @Test
    fun cutsWordsAndKeepsPunctuationSeparate() {
        assertEquals(listOf("我们", "是", "学生", "。"), StoryText.segment("我们是学生。", lookup = lookup))
        assertEquals(listOf("今天", "天气", "很", "好", "！"), StoryText.segment("今天天气很好！", lookup = lookup))
    }

    @Test
    fun backwardMatchingFixesGreedyCuts() {
        // Forwards gives 研究生 / 命 / 起源; backwards gives 研究 / 生命 / 起源 (fewer single characters).
        assertEquals(listOf("研究", "生命", "的", "起源"), StoryText.segment("研究生命的起源", lookup = lookup))
    }

    @Test
    fun traditionalBecomesSimplified() {
        assertEquals(listOf("我们", "是", "学生"), StoryText.segment("我們是學生", lookup = lookup))
    }

    @Test
    fun otherTextIsSplitAtSpacesAndPunctuation() {
        assertEquals(listOf("我", "用", "iPhone", "15", "……"), StoryText.segment("我用 iPhone 15……", lookup = lookup))
        assertEquals(listOf("2780", "万"), StoryText.segment("2780万") { null })
    }

    @Test
    fun candidatesCoverEveryRun() {
        val c = StoryText.candidates("我们，学生", maxLen = 2)
        assertEquals(setOf("我", "们", "我们", "学", "生", "学生"), c)
    }

    @Test
    fun joinedTextHasNoSpacesExceptBetweenLatinWords() {
        val joined = StoryText.layout(listOf("我", "用", "iPhone", "15", "。"))
        assertEquals("我用iPhone 15。", joined.text)
        assertEquals(listOf(0, 1, 2, 9, 11), joined.starts.toList())
        assertEquals(2, joined.wordAt(5))
        assertEquals(3, joined.wordAt(10))
        assertEquals(-1, joined.wordAt(99))
    }

    @Test
    fun spacedSentencesKeepTheirWords() {
        assertEquals(listOf("我", "每天", "学习", "汉语", "。"), StoryText.tokensOfSpaced("我 每天 学习 汉语。"))
        assertEquals(listOf("“", "你好", "！", "”"), StoryText.tokensOfSpaced("“你好！”"))
        assertTrue(StoryText.isSpaced("我 每天 学习 汉语。"))
        assertFalse(StoryText.isSpaced("我每天学习汉语。"))
        assertFalse(StoryText.isSpaced("I use iPhone"))
    }

    @Test
    fun paragraphsAreSplitIntoSentences() {
        val p1 = listOf("他", "说", "：", "“", "你好", "！", "”", "我", "笑", "了", "。")
        val p2 = listOf("Hello", ".")
        val p3 = listOf("第二", "段", "没有", "句号")
        val chunks = StoryText.chunks(listOf(p1, p2, p3))
        assertEquals(
            listOf("他 说 ： “ 你好 ！ ”", "我 笑 了 。", "第二 段 没有 句号"),
            chunks.map { it.text },
        )
        assertEquals(listOf(true, false, true), chunks.map { it.p })
    }

    @Test
    fun pagesHoldAboutAPageOfText() {
        val sentence = StoryChunk((1..9).joinToString(" ") { "学生" } + " 。")   // 19 characters
        val chunks = List(20) { i -> sentence.copy(p = i % 5 == 0) }
        val pages = StoryText.paginate(chunks, maxChars = 100)
        assertEquals(0, pages.first().first)
        assertEquals(chunks.lastIndex, pages.last().last)
        // Every sentence is on exactly one page, in order.
        assertEquals((0 until 20).toList(), pages.flatMap { it.toList() })
        pages.forEach { r -> assertTrue(r.count() * 19 <= 100 + 19) }
        // Mostly full pages end where a paragraph does.
        assertTrue(pages.drop(1).all { chunks[it.first].p })
    }

    @Test
    fun titleFromText() {
        assertEquals("小猫钓鱼", StoryText.titleFrom("小猫钓鱼。从前有一只小猫"))
        assertEquals("从前有一只小猫", StoryText.titleFrom("从前有一只小猫，它很喜欢钓鱼"))
        assertEquals("一二三四五六七八九十一二三四五六…", StoryText.titleFrom("一二三四五六七八九十一二三四五六七八"))
    }
}
