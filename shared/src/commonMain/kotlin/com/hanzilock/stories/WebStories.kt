package com.hanzilock.stories

import com.hanzilock.data.AppJson
import com.hanzilock.quiz.SentenceTiles
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Stories from the web, fetched on the phone for your own reading: Mandarin Bean's graded
 * stories (HSK 1-6, through the site's WordPress API, which also marks the word boundaries), and
 * the main text of any other page with Chinese on it. Network calls block - use a background thread.
 */
object WebStories {
    class WebException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** A page's story. [paragraphs] have their word boundaries marked with spaces when [spaced]. */
    data class WebStory(
        val title: String,
        val paragraphs: List<String>,
        val spaced: Boolean,
        val level: Int?,
        val url: String,
        val source: String,
    )

    data class Listing(val title: String, val url: String, val level: Int?)
    data class ListingPage(val items: List<Listing>, val totalPages: Int)

    const val MANDARIN_BEAN = "Mandarin Bean"
    private const val MB_API = "https://mandarinbean.com/wp-json/wp/v2"
    private const val MB_STORY_TAG = 7

    /** Mandarin Bean's tag ids for HSK 1-6. */
    private val MB_LEVEL_TAGS = mapOf(1 to 16, 2 to 13, 3 to 2, 4 to 12, 5 to 9, 6 to 20)

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S721B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    @Serializable
    private data class WpText(val rendered: String = "")

    @Serializable
    private data class WpPost(
        val id: Long = 0,
        val title: WpText = WpText(),
        val link: String = "",
        val tags: List<Int> = emptyList(),
        val content: WpText = WpText(),
    )

    /** One page (20 stories) of Mandarin Bean's stories, newest first; [level] null = every level. */
    fun mandarinBeanStories(level: Int?, page: Int): ListingPage {
        val tags = listOfNotNull(MB_STORY_TAG, level?.let { MB_LEVEL_TAGS[it] }).joinToString(",")
        val r = get("$MB_API/posts?tags%5Bterms%5D=$tags&tags%5Boperator%5D=AND&per_page=20&page=$page&_fields=id,title,link,tags")
        val posts = runCatching { AppJson.decodeFromString<List<WpPost>>(r.text()) }
            .getOrElse { throw WebException("Mandarin Bean sent something unexpected.", it) }
        val total = r.header("X-WP-TotalPages")?.toIntOrNull() ?: page
        return ListingPage(posts.map { Listing(decodeEntities(it.title.rendered).trim(), it.link, levelOf(it.tags)) }, total)
    }

    /** The story on the page at [url]. */
    fun fetch(url: String): WebStory {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host == "mandarinbean.com" || host.endsWith(".mandarinbean.com")) return mandarinBeanStory(url)
        val r = get(url)
        val type = r.contentType.orEmpty().lowercase()
        if (type.isNotEmpty() && !type.contains("html") && !type.startsWith("text/")) {
            throw WebException("That link isn't a web page ($type).")
        }
        val html = String(r.body, charsetOf(r.contentType, r.body))
        val (title, paragraphs) = if (type.startsWith("text/plain")) "" to html.lines().filter(::isContentLine) else mainText(html)
        return WebStory(title.ifBlank { siteName(r.url) }, paragraphs, spaced = false, level = null, url = url, source = siteName(r.url))
    }

    private fun mandarinBeanStory(url: String): WebStory {
        val slug = runCatching { URI(url).path.orEmpty() }.getOrDefault("").trim('/').substringAfterLast('/')
        if (slug.isEmpty()) throw WebException("That Mandarin Bean link isn't a story.")
        val r = get("$MB_API/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=id,title,link,tags,content")
        val post = runCatching { AppJson.decodeFromString<List<WpPost>>(r.text()) }.getOrNull()?.firstOrNull()
            ?: throw WebException("Mandarin Bean has no story at that link.")
        return WebStory(
            title = decodeEntities(post.title.rendered).trim(),
            paragraphs = mandarinBeanParagraphs(post.content.rendered),
            spaced = true,
            level = levelOf(post.tags),
            url = post.link.ifEmpty { url },
            source = MANDARIN_BEAN,
        )
    }

    private fun levelOf(tags: List<Int>): Int? = MB_LEVEL_TAGS.entries.firstOrNull { it.value in tags }?.key

    // ---- extraction (no network) ----------------------------------------------------------------

    private val RUBY = Regex("<ruby\\b[^>]*>(.*?)</ruby>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SIMPLIFIED_SPAN = Regex("<span[^>]*class=\"si\"[^>]*>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RT = Regex("<(rt|rp)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")

    /**
     * Mandarin Bean writes every word as <ruby> with the simplified form, the traditional form and
     * the pinyin. Keeps the simplified words, one paragraph per <p>, words separated by spaces.
     */
    fun mandarinBeanParagraphs(html: String): List<String> = html.split(Regex("</p>", RegexOption.IGNORE_CASE)).mapNotNull { p ->
        val words = ArrayList<String>()
        var last = 0
        for (m in RUBY.findAll(p)) {
            words += plainWords(p.substring(last, m.range.first))
            val inner = m.groupValues[1]
            val simplified = SIMPLIFIED_SPAN.find(inner)?.groupValues?.get(1) ?: inner.replace(RT, "")
            val w = decodeEntities(simplified.replace(TAG, "")).replace(Regex("\\s+"), "")
            if (w.isNotEmpty()) words += w
            last = m.range.last + 1
        }
        words += plainWords(p.substring(last))
        words.takeIf { it.any(StoryText::hasHan) }?.joinToString(" ")
    }

    private fun plainWords(html: String): List<String> = StoryText.segment(decodeEntities(html.replace(TAG, ""))) { null }

    private val DROPPED_ELEMENTS = listOf(
        "script", "style", "noscript", "svg", "head", "nav", "footer", "aside", "form", "select", "button",
        "iframe", "template", "rt", "rp",
    )
    private val BLOCK_TAGS = Regex(
        "</?(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article|blockquote|pre|td|dd|dt|hr)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** Title and Chinese paragraphs of an ordinary web page (menus, footers and the like left out). */
    fun mainText(html: String): Pair<String, List<String>> {
        val title = pageTitle(html)
        var s = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        for (tag in DROPPED_ELEMENTS) {
            s = s.replace(Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n")
        }
        // A page with an <article> usually has the story in it.
        val article = Regex("<article\\b[^>]*>(.*?)</article>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(s).map { it.groupValues[1] }.maxByOrNull { hanCount(it.replace(TAG, "")) }
        if (article != null && hanCount(article.replace(TAG, "")) >= 50) s = article
        s = decodeEntities(s.replace(BLOCK_TAGS, "\n").replace(TAG, ""))
        val lines = s.lines()
            .map { it.replace(Regex("\\[\\d+]"), "").replace(Regex("[\\s\\u00A0\\u3000]+"), " ").trim() }
            .filter(::isContentLine)
        val paragraphs = lines.filterIndexed { i, line -> i == 0 || line != lines[i - 1] }
            .let { if (it.firstOrNull() == title) it.drop(1) else it }
        return title to paragraphs
    }

    private fun hanCount(s: String) = s.count(SentenceTiles::isHanChar)

    private val BOILERPLATE = listOf(
        "版权", "Copyright", "copyright", "ICP", "上一篇", "下一篇", "上一章", "下一章", "责任编辑", "转载", "免责声明",
        "关于我们", "联系我们", "扫码", "二维码", "备案", "登录", "注册", "首页", "阅读全文", "点击", "分享到",
    )

    /** A line of story text rather than a menu item, a caption or a footer. */
    private fun isContentLine(line: String): Boolean {
        val han = hanCount(line)
        if (han < 6) return false
        val visible = line.count { !it.isWhitespace() }
        if (han * 2 < visible) return false
        if (han < 40 && BOILERPLATE.any { it in line }) return false
        if (line.count { it == '·' || it == '|' } >= 3) return false   // a list of links
        return han >= 15 || line.last() in "。！？”…」』!?"
    }

    private fun pageTitle(html: String): String {
        val og = Regex("<meta[^>]+property=[\"']og:title[\"'][^>]*content=[\"']([^\"']*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val tag = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
        val h1 = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
        val raw = listOfNotNull(og, tag, h1).map { decodeEntities(it.replace(TAG, "")).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return cleanTitle(raw)
    }

    /** "小猫钓鱼_儿童故事网" -> "小猫钓鱼": the site name after a separator is dropped. */
    fun cleanTitle(title: String): String {
        val first = title.split(Regex("\\s+[-|–—]\\s+|[_|｜]")).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return title.trim()
        return if (!StoryText.hasHan(first) && StoryText.hasHan(title)) title.trim() else first
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "middot" to "·",
        "laquo" to "«", "raquo" to "»", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwj" to "", "zwnj" to "",
    )

    fun decodeEntities(s: String): String = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
            else -> NAMED_ENTITIES[e]
        } ?: m.value
    }

    /** The first web link in shared text ("Title https://…"). */
    fun findUrl(text: String): String? =
        Regex("https?://[^\\s<>\"'“”，。]+").find(text)?.value?.trimEnd('.', ',', ')', '!', '?')

    /** "https://www.example.com/a/b" -> "example.com". */
    fun siteName(url: String): String =
        runCatching { URI(url).host.orEmpty().removePrefix("www.").removePrefix("m.") }.getOrDefault("").ifEmpty { "Web page" }

    /** Charset from the Content-Type header or a <meta> tag; GB2312/GBK pages are read as GB18030 (a superset). */
    fun charsetOf(contentType: String?, body: ByteArray): Charset {
        val head = String(body, 0, minOf(body.size, 4096), Charsets.ISO_8859_1)
        val name = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType.orEmpty())?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return when (name?.lowercase()) {
            null -> Charsets.UTF_8
            "gb2312", "gbk", "x-gbk", "gb18030", "cp936" -> Charset.forName("GB18030")
            else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
    }

    // ---- network ----------------------------------------------------------------------------------

    private class Response(val body: ByteArray, val contentType: String?, val headers: Map<String?, List<String>>, val url: String) {
        fun text() = String(body, Charsets.UTF_8)
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private const val MAX_BYTES = 4 * 1024 * 1024

    /** GET with redirects followed (also from http to https). */
    private fun get(url: String): Response {
        var current = url
        repeat(6) {
            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw WebException("That isn't a web link.", e)
            }
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw WebException("The site redirected nowhere.")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw WebException("The site answered with error $code.")
                val body = conn.inputStream.use { readLimited(it) }
                return Response(body, conn.contentType, conn.headerFields, current)
            } catch (e: WebException) {
                throw e
            } catch (e: Exception) {
                throw WebException("Couldn't load the page (${e.message ?: e.javaClass.simpleName}).", e)
            } finally {
                conn.disconnect()
            }
        }
        throw WebException("The site redirected too many times.")
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
