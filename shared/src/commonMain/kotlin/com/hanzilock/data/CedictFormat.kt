package com.hanzilock.data

/** One line of CC-CEDICT: `Traditional Simplified [pin1 yin1] /definition 1/definition 2/`. */
object CedictFormat {
    data class Line(val traditional: String, val simplified: String, val pinyin: String, val definitions: String)

    private val LINE = Regex("""^(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$""")

    /** Parses a dictionary line; null for comments and anything malformed. */
    fun parse(text: String): Line? {
        if (text.startsWith("#")) return null
        val m = LINE.matchEntire(text) ?: return null
        val (trad, simp, pinyin, defs) = m.destructured
        return Line(trad, simp, pinyin, defs)
    }
}
