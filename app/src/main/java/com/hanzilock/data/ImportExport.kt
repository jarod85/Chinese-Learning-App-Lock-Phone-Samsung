package com.hanzilock.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hanzilock.core.Languages
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.Pinyin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Imports word lists (each import becomes its own set, so it never mixes into HSK 1 & co.),
 * restores backups, and exports sets and backups through the system file picker.
 * Everything here blocks - call it from a background thread.
 */
class ImportExport(
    private val context: Context,
    private val words: WordRepository,
    private val dictionary: DictionaryRepository,
    private val corpus: ExampleCorpus,
    private val languages: Languages,
    private val grader: () -> ClaudeGrader?,
) {
    data class ImportResult(
        val setName: String?,
        val lang: String,
        val total: Int,
        /** Words that were already known (e.g. in HSK 2) - linked, keeping their progress. */
        val linked: Int,
        /** New words whose meaning came from the dictionary, the file or Claude. */
        val filled: Int,
        /** Words still without a meaning (left out of practice until you add one). */
        val incomplete: List<String>,
        val restoredBackup: Boolean = false,
    )

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** A set name from a file name: "hsk_food-words.csv" -> "Hsk food words". */
    fun suggestedSetName(uri: Uri): String =
        displayName(uri)?.substringBeforeLast('.')?.replace('_', ' ')?.replace('-', ' ')?.trim()
            ?.replaceFirstChar { it.uppercaseChar() }?.takeIf { it.isNotEmpty() } ?: "Imported words"

    fun import(uri: Uri, lang: String, setName: String, progress: (String) -> Unit = {}): ImportResult {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Couldn't open the file")
        val trimmed = text.trimStart(ImportParser.BOM, ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{")) return importJson(trimmed, lang, setName)
        return importWords(ImportParser.parseText(text), lang, setName, progress)
    }

    private fun importJson(json: String, lang: String, setName: String): ImportResult {
        val root = AppJson.parseToJsonElement(json).jsonObject
        if (root["format"]?.jsonPrimitive?.content == "hanzilock-backup") {
            val backup = AppJson.decodeFromString<Backup>(json)
            words.restore(backup)
            return ImportResult(null, lang, backup.words.size, 0, 0, emptyList(), restoredBackup = true)
        }
        // A set file (e.g. exported from another phone) - or an old registry file (the app's first version).
        val file = AppJson.decodeFromString<SetFile>(json)
        val fileLang = if (root.containsKey("lang")) file.lang else lang
        val name = file.name.ifBlank { setName }
        val key = file.key.takeIf { it.isNotBlank() && words.setByKey(it)?.custom != false }
            ?: "custom.$fileLang.${System.currentTimeMillis()}"
        words.applySet(SetInfo(key, fileLang, name, "Custom", 100, custom = true, enabled = true), file, bundled = false)
        val incomplete = file.words.filter { it.meanings.isEmpty() }.map { it.term }
        return ImportResult(name, fileLang, file.words.size, 0, file.words.size - incomplete.size, incomplete)
    }

    /** Adds the words as a new set of [lang], filling in reading / meaning / example sentence. */
    fun importWords(parsed: List<ParsedWord>, lang: String, setName: String, progress: (String) -> Unit = {}): ImportResult {
        require(parsed.isNotEmpty()) { "No words found in the file." }
        val setId = words.createSet(lang, setName)
        var linked = 0
        var filled = 0
        val needHelp = ArrayList<Long>()
        parsed.forEachIndexed { i, p ->
            if (i % 25 == 0) progress("Adding words… ${i + 1}/${parsed.size}")
            val existing = words.find(lang, p.term) ?: words.find(lang, p.term.lowercase())
            if (existing != null) {
                words.addToSet(setId, existing.id)
                linked++
                return@forEachIndexed
            }
            val draft = complete(p, lang)
            val id = words.insert(draft, "import", setId)
            if (draft.meanings.isNotEmpty()) filled++
            if (draft.meanings.isEmpty() || draft.examples.isEmpty() || draft.reading.isEmpty() && languages.get(lang).canTypeReading) {
                needHelp.add(id)
            }
        }
        val claude = grader()
        if (claude != null && needHelp.isNotEmpty()) {
            val languageName = languages.get(lang).name
            needHelp.chunked(20).forEachIndexed { n, chunk ->
                progress("Asking Claude to fill in details… ${n * 20 + 1}-${n * 20 + chunk.size} of ${needHelp.size}")
                val batch = chunk.mapNotNull { words.get(it) }
                val described = runCatching { claude.describeWords(languageName, batch.map { it.term }) }.getOrNull().orEmpty()
                for (w in batch) {
                    val d = described.firstOrNull { it.term == w.term } ?: continue
                    val wasEmpty = w.meanings.isEmpty()
                    words.complete(
                        w.id,
                        reading = d.reading.takeIf { w.reading.isBlank() && it.isNotBlank() }?.let {
                            if (lang == "zh") Pinyin.normalizeToMarked(it).ifEmpty { it } else it
                        },
                        meanings = if (wasEmpty) d.meanings else emptyList(),
                        examples = if (w.examples.isEmpty() && d.example.isNotBlank()) {
                            listOf(Example(d.example, null, d.exampleTranslation.ifBlank { null }))
                        } else {
                            emptyList()
                        },
                    )
                    if (wasEmpty && d.meanings.isNotEmpty()) filled++
                }
            }
        }
        val incomplete = words.list(lang, WordFilter.INCOMPLETE, scope = WordScope.InSet(setId)).map { it.term }
        return ImportResult(setName, lang, parsed.size, linked, filled, incomplete)
    }

    /** Reading / meanings / example from the file, then CC-CEDICT and the sentence corpus (Chinese). */
    fun complete(p: ParsedWord, lang: String): WordDraft {
        if (lang == "zh") {
            val entry = dictionary.lookup(p.term).firstOrNull()
            val reading = p.reading?.let { Pinyin.normalizeToMarked(it).ifEmpty { it } } ?: entry?.pinyinMarked.orEmpty()
            val meanings = p.meaning?.let { listOf(it) } ?: entry?.definitions?.let(::cleanDefinitions).orEmpty()
            return WordDraft(lang, p.term, p.traditional ?: entry?.traditional, reading, meanings, corpus.find(p.term))
        }
        return WordDraft(lang, p.term, p.traditional, p.reading.orEmpty(), p.meaning?.let { listOf(it) }.orEmpty(), emptyList())
    }

    private fun cleanDefinitions(defs: List<String>): List<String> {
        val useful = defs.filterNot { it.startsWith("CL:") || it.startsWith("surname ") || it.contains("variant of") }
            .map { it.replace(Regex("\\s*\\(CL:[^)]*\\)"), "") }
        // Slang and archaic senses only when there's nothing else (机场 is "airport", not a VPN service).
        val main = useful.filterNot { MINOR_SENSE.containsMatchIn(it) }
        return main.ifEmpty { useful }.take(4).ifEmpty { defs.take(4) }
    }

    private val MINOR_SENSE = Regex("^\\((slang|Internet slang|archaic|old|dialect)\\)", RegexOption.IGNORE_CASE)

    /** Writes one set in the repo's set-file format (drop it into content/sets/custom and run tools/sets.py). */
    fun exportSet(uri: Uri, setId: Long) {
        val set = words.set(setId) ?: error("Set not found")
        val list = words.list(set.lang, scope = WordScope.InSet(setId), limit = Int.MAX_VALUE)
        write(uri, SetSync.format(SetFile(set.key, set.lang, set.name, null, list.map { it.toSetWord() })))
    }

    fun exportBackup(uri: Uri) {
        write(uri, ExportJson.encodeToString(Backup.serializer(), words.backup()))
    }

    private fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Couldn't write the file")
    }
}
