package com.hanzilock.data

import android.content.Context
import android.net.Uri
import com.hanzilock.core.Settings
import com.hanzilock.quiz.Pinyin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Import word lists / backups and export the registry or a full backup (via the system file picker). */
class ImportExport(
    private val context: Context,
    private val words: WordRepository,
    private val dictionary: DictionaryRepository,
    private val settings: Settings,
) {
    data class ImportResult(val added: Int, val skipped: Int, val notFound: List<String>, val restoredBackup: Boolean = false)

    /** Reads a Pleco export, CSV, plain word list, registry JSON or HanziLock backup. */
    fun import(uri: Uri): ImportResult {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Couldn't open the file")
        val trimmed = text.trimStart(ImportParser.BOM, ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{")) {
            val root = AppJson.parseToJsonElement(trimmed).jsonObject
            if (root["format"]?.jsonPrimitive?.content == "hanzilock-backup") {
                val backup = AppJson.decodeFromString<Backup>(trimmed)
                words.restore(backup)
                return ImportResult(backup.words.size, 0, emptyList(), restoredBackup = true)
            }
            val file = AppJson.decodeFromString<RegistryFile>(trimmed)
            val drafts = file.words.map { it.toDraft() }
            val added = words.addMissing(drafts, "import")
            return ImportResult(added, drafts.size - added, emptyList())
        }
        val parsed = ImportParser.parseText(text)
        val drafts = ArrayList<WordDraft>()
        val notFound = ArrayList<String>()
        for (p in parsed) {
            val draft = complete(p)
            if (draft == null) notFound.add(p.hanzi) else drafts.add(draft)
        }
        val added = words.addMissing(drafts, "import")
        return ImportResult(added, drafts.size - added, notFound)
    }

    /** Fills pinyin/meaning from CC-CEDICT when the import didn't include them. */
    fun complete(p: ParsedWord): WordDraft? {
        val entry = dictionary.lookup(p.hanzi).firstOrNull()
        val pinyin = p.pinyin?.let { Pinyin.normalizeToMarked(it).ifEmpty { it } } ?: entry?.pinyinMarked
        val meanings = p.meaning?.let { listOf(it) } ?: entry?.definitions?.take(4)
        if (pinyin.isNullOrBlank() || meanings.isNullOrEmpty()) return null
        return WordDraft(p.hanzi, p.traditional ?: entry?.traditional, pinyin, meanings, emptyList(), emptyList())
    }

    fun exportRegistry(uri: Uri) {
        val all = words.list(WordFilter.ALL)
        val file = RegistryFile(version = maxOf(settings.registryVersion, 1) + 1, words = all.map { it.toRegistryWord() })
        write(uri, RegistrySync.format(file))
    }

    fun exportBackup(uri: Uri) {
        write(uri, ExportJson.encodeToString(Backup.serializer(), words.backup(settings.registryVersion)))
    }

    private fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Couldn't write the file")
    }
}
