package com.hanzilock.data

import com.hanzilock.core.CatalogLanguage
import com.hanzilock.core.Languages
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Adds a language from the phone: downloads its open A1-B1 word pack (Bannerless Studio, CC BY-SA
 * 4.0 - 2,000 words with English meanings and example sentences) and installs it as level sets.
 * Languages without a pack are added empty, ready for your own imported lists.
 */
class LanguagePacks(private val words: WordRepository, private val languages: Languages) {
    @Serializable
    private data class PackWord(
        val id: String,
        val w: String,
        val lemma: String? = null,
        val en: String = "",
        val lv: String = "",
        val rank: Int = Int.MAX_VALUE,
        val forms: List<String> = emptyList(),
        val alt: List<String> = emptyList(),
    )

    @Serializable
    private data class PackSentence(
        val id: String,
        val t: String,
        val en: String? = null,
        val lv: String? = null,
        val words: List<String> = emptyList(),
        /** [start, end, word id]: where each word appears in the sentence. */
        val spans: List<JsonArray> = emptyList(),
    )

    /** Returns the number of words installed (0 for a language without a downloadable pack). */
    fun install(entry: CatalogLanguage, progress: (String) -> Unit): Int {
        val profile = entry.profile
        val repo = entry.packRepo
        if (repo == null) {
            languages.add(profile)
            return 0
        }
        val base = "https://raw.githubusercontent.com/Bannerless-Studio/$repo/main/pack"
        progress("Downloading ${profile.name} word list…")
        val packWords = AppJson.decodeFromString<List<PackWord>>(get("$base/words.json"))
        progress("Downloading ${profile.name} example sentences…")
        val sentences = runCatching { AppJson.decodeFromString<List<PackSentence>>(get("$base/sentences.json")) }.getOrDefault(emptyList())
        val sets = buildSets(profile.code, packWords, sentences)
        languages.add(profile)
        var total = 0
        for ((info, file) in sets) {
            progress("Installing ${profile.name} ${info.name}…")
            total += words.applySet(info, file, bundled = false)
        }
        words.enableFirstSetIfNone(profile.code)
        return total
    }

    private val order = mapOf("A1" to 1, "A2" to 2, "B1" to 3, "B2" to 4)

    /** Same conversion as tools/sets.py: homographs merged, easiest example sentences first. */
    private fun buildSets(lang: String, packWords: List<PackWord>, sentences: List<PackSentence>): List<Pair<SetInfo, SetFile>> {
        val byWord = HashMap<String, MutableList<PackSentence>>()
        for (s in sentences) for (id in s.words.toSet()) byWord.getOrPut(id) { ArrayList() }.add(s)
        data class Merged(val level: String, val meanings: MutableList<String>, val forms: MutableList<String>, val ids: MutableList<String>)
        val merged = LinkedHashMap<String, Merged>()
        for (w in packWords.filter { it.lv in order }.sortedWith(compareBy({ order[it.lv] }, { it.rank }))) {
            val term = w.w.trim()
            if (term.isEmpty()) continue
            val m = merged.getOrPut(term) { Merged(w.lv, ArrayList(), ArrayList(), ArrayList()) }
            m.meanings += splitGloss(w.en)
            m.forms += listOf(w.lemma ?: term) + w.alt + w.forms
            m.ids += w.id
        }
        val lemmaOf = packWords.associate { it.id to (it.lemma ?: it.w).trim() }
        return listOf("A1", "A2", "B1").mapIndexed { i, level ->
            val list = merged.filter { it.value.level == level }.mapNotNull { (term, m) ->
                val meanings = m.meanings.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(5)
                if (meanings.isEmpty()) return@mapNotNull null
                val sentencesFor = m.ids.flatMap { byWord[it].orEmpty() }.distinctBy { it.id }
                    .sortedWith(compareBy({ (order[it.lv] ?: 9) > (order[level] ?: 0) }, { it.t.length }))
                val shown = sentencesFor.take(2)
                fun writtenIn(list: List<PackSentence>) = list.flatMap { s -> m.ids.flatMap { written(s, it, lemmaOf) } }
                SetWord(
                    term = term,
                    meanings = meanings,
                    examples = shown.map { Example(it.t, null, it.en) },
                    forms = pickForms(term, m.forms, writtenIn(shown), writtenIn(sentencesFor.drop(2))),
                    tags = listOf(level),
                )
            }
            val key = "dl.$lang.${level.lowercase()}"
            SetInfo(key, lang, level, if (level == "B1") "Intermediate" else "Beginner", i + 1, count = list.size) to
                SetFile(key, lang, level, null, list)
        }
    }

    /** How a word is written in a sentence ("es" for ser); the capital at the start of a sentence is dropped. */
    private fun written(s: PackSentence, id: String, lemmaOf: Map<String, String>): List<String> = s.spans.mapNotNull { span ->
        val start = span.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val end = span.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        if (span.getOrNull(2)?.jsonPrimitive?.contentOrNull != id || start < 0 || end > s.t.length || start >= end) return@mapNotNull null
        val form = s.t.substring(start, end).trim()
        val initial = s.t.substring(0, start).none { it.isLetter() }
        if (initial && form.firstOrNull()?.isUpperCase() == true && lemmaOf[id]?.firstOrNull()?.isLowerCase() == true) {
            form.replaceFirstChar { it.lowercaseChar() }
        } else {
            form.ifEmpty { null }
        }
    }

    /** Up to 24 forms: the pack's own, then those seen in sentences - always including the ones in the examples shown. */
    private fun pickForms(term: String, own: List<String>, inExamples: List<String>, elsewhere: List<String>): List<String> {
        val must = inExamples.map { it.lowercase() }.toSet()
        val ordered = (own + inExamples + elsewhere).map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(term, ignoreCase = true) }.distinctBy { it.lowercase() }
        var room = 24 - ordered.count { it.lowercase() in must }
        return buildList {
            for (f in ordered) {
                if (f.lowercase() in must) add(f) else if (room > 0) { add(f); room-- }
            }
        }
    }

    private fun splitGloss(gloss: String): List<String> {
        val out = ArrayList<String>()
        for (chunk in gloss.split(';', '/')) {
            var depth = 0
            val cur = StringBuilder()
            for (c in chunk) {
                if (c == '(' || c == '[') depth++
                if (c == ')' || c == ']') depth = maxOf(0, depth - 1)
                if (c == ',' && depth == 0) { out.add(cur.toString()); cur.clear() } else cur.append(c)
            }
            out.add(cur.toString())
        }
        return out
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "LingoLock")
        try {
            if (conn.responseCode !in 200..299) error("Download failed (${conn.responseCode}) for $url")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
