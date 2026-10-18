package com.hanzilock.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebStoriesTest {
    @Test
    fun mandarinBeanKeepsSimplifiedWordsOnly() {
        val html = """
            <p><ruby class="mb-tooltip" data-mb-pinyin="wǒ"><span class="si">我</span><span class="tr">我</span><rt>wǒ</rt></ruby><ruby class="mb-tooltip"><span class="si">学习</span><span class="tr">學習</span><rt>xué xí</rt></ruby><ruby><span class="si">汉语</span><span class="tr">漢語</span><rt>Hàn yǔ</rt></ruby>。</p>
            <p><ruby><span class="si">现在</span><span class="tr">現在</span><rt>xiàn zài</rt></ruby>2780<ruby><span class="si">万</span><span class="tr">萬</span><rt>wàn</rt></ruby>&#8220;好&#8221;</p>
            <p><img src="a.png"></p>
        """.trimIndent()
        assertEquals(listOf("我 学习 汉语 。", "现在 2780 万 “ 好 ”"), WebStories.mandarinBeanParagraphs(html))
    }

    @Test
    fun mainTextFindsTheStoryAndDropsTheRest() {
        val html = """
            <html><head><title>小猫钓鱼_儿童故事网</title><style>p { color: red }</style></head>
            <body>
              <nav><a href="/">首页</a> <a href="/a">童话故事大全</a></nav>
              <div class="menu">寓言故事 成语故事 睡前故事</div>
              <div class="content">
                <h1>小猫钓鱼</h1>
                <p>　　猫妈妈带着小猫在河边钓鱼。一只蜻蜓飞来了，小猫放下鱼竿就去捉蜻蜓。</p>
                <p>蜻蜓飞走了，小猫没捉着，空着手回到河边来。&nbsp;猫妈妈钓着了一条大鱼。[1]</p>
                <div>长篇小说 · 中篇小说 · 短篇小说 · 微型小说 · 戏剧 · 寓言 · 童话</div>
                <script>var ad = "广告：点击这里下载我们的应用程序";</script>
              </div>
              <footer>版权所有 Copyright © 2024 儿童故事网 京ICP备12345678号</footer>
            </body></html>
        """.trimIndent()
        val (title, paragraphs) = WebStories.mainText(html)
        assertEquals("小猫钓鱼", title)
        assertEquals(
            listOf(
                "猫妈妈带着小猫在河边钓鱼。一只蜻蜓飞来了，小猫放下鱼竿就去捉蜻蜓。",
                "蜻蜓飞走了，小猫没捉着，空着手回到河边来。 猫妈妈钓着了一条大鱼。",
            ),
            paragraphs,
        )
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("“好” & <ok> 汉", WebStories.decodeEntities("&ldquo;好&rdquo; &amp; &lt;ok&gt; &#x6C49;"))
        assertEquals("&unknown;", WebStories.decodeEntities("&unknown;"))
    }

    @Test
    fun linksAreFoundInSharedText() {
        assertEquals("https://example.com/story/1", WebStories.findUrl("小猫钓鱼 https://example.com/story/1"))
        assertEquals("https://example.com/a?b=1", WebStories.findUrl("看看这个：https://example.com/a?b=1。"))
        assertNull(WebStories.findUrl("从前有一只小猫"))
        assertEquals("example.com", WebStories.siteName("https://www.example.com/a/b"))
    }

    @Test
    fun charsetComesFromTheHeaderOrTheMetaTag() {
        assertEquals("GB18030", WebStories.charsetOf("text/html; charset=GBK", ByteArray(0)).name())
        assertEquals("GB18030", WebStories.charsetOf("text/html", "<meta charset=\"gb2312\">".toByteArray()).name())
        assertEquals("UTF-8", WebStories.charsetOf(null, "<html>".toByteArray()).name())
    }

    @Test
    fun siteNamesAreDroppedFromTitles() {
        assertEquals("小猫钓鱼", WebStories.cleanTitle("小猫钓鱼_儿童故事网"))
        assertEquals("小猫钓鱼", WebStories.cleanTitle("小猫钓鱼 - 故事大全"))
        assertEquals("A Happy Day", WebStories.cleanTitle("A Happy Day"))
    }
}
