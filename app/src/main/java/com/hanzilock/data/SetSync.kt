package com.hanzilock.data

import android.content.Context
import com.hanzilock.core.LanguageProfile
import com.hanzilock.core.Languages
import com.hanzilock.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

@Serializable
data class SetIndex(
    val languages: List<LanguageProfile> = emptyList(),
    val sets: List<SetInfo> = emptyList(),
)

/**
 * Installs the word sets bundled from content/sets (index.json + one file per set) and keeps them
 * up to date: a set is re-applied whenever its "rev" changes, which is how edits to the repo reach
 * the phone. Your progress and your own edits are never overwritten.
 */
class SetSync(
    private val context: Context,
    private val words: WordRepository,
    private val languages: Languages,
    private val settings: Settings,
) {
    private val _progress = MutableStateFlow<String?>(null)

    /** Non-null while sets are being installed ("Installing HSK 3…"). */
    val progress: StateFlow<String?> = _progress

    fun index(): SetIndex = runCatching {
        context.assets.open(INDEX).bufferedReader(Charsets.UTF_8).use { AppJson.decodeFromString<SetIndex>(it.readText()) }
    }.getOrElse { SetIndex() }

    fun readSet(info: SetInfo): SetFile =
        context.assets.open(info.file).bufferedReader(Charsets.UTF_8).use { AppJson.decodeFromString<SetFile>(it.readText()) }

    /**
     * Applies every changed set. The active language goes first so practice can start while the
     * rest installs; [onActiveReady] is called once that's done.
     */
    fun syncAll(onActiveReady: () -> Unit) {
        val index = index()
        languages.setBundled(index.languages)
        val active = settings.activeLanguage
        val (first, rest) = index.sets.partition { it.lang == active }
        try {
            first.forEach(::applyIfChanged)
            words.enableFirstSetIfNone(active)
            onActiveReady()
            rest.forEach(::applyIfChanged)
        } finally {
            _progress.value = null
        }
    }

    private fun applyIfChanged(info: SetInfo) {
        val installed = words.setByKey(info.key)
        if (installed != null && installed.rev == info.rev) return
        _progress.value = "Installing ${languages.get(info.lang).name} ${info.name}…"
        runCatching { words.applySet(info, readSet(info), bundled = true) }
    }

    companion object {
        const val INDEX = "sets/index.json"

        /** Same layout as the repo's set files - one word per line - so exports can go straight into content/sets. */
        fun format(file: SetFile): String = buildString {
            append("{\n")
            append("  \"key\": ").append(AppJson.encodeToString(String.serializer(), file.key)).append(",\n")
            append("  \"lang\": \"").append(file.lang).append("\",\n")
            append("  \"name\": ").append(AppJson.encodeToString(String.serializer(), file.name)).append(",\n")
            append("  \"words\": [\n")
            file.words.forEachIndexed { i, w ->
                append("    ").append(AppJson.encodeToString(SetWord.serializer(), w))
                append(if (i < file.words.lastIndex) ",\n" else "\n")
            }
            append("  ]\n}\n")
        }
    }
}

/**
 * Tatoeba Chinese-English sentence pairs (content/corpus/zh-en.tsv), used to give imported
 * Chinese words an example sentence without going online.
 */
class ExampleCorpus(private val context: Context) {
    private val pairs: List<Pair<String, String>> by lazy {
        runCatching {
            context.assets.open("corpus/zh-en.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Up to [count] short sentences containing [term] (the file is sorted shortest first). */
    fun find(term: String, count: Int = 2): List<Example> {
        if (term.isBlank()) return emptyList()
        val out = ArrayList<Example>()
        for ((zh, en) in pairs) {
            if (zh.length >= term.length + 3 && zh.contains(term)) {
                out.add(Example(zh, null, en))
                if (out.size >= count) break
            }
        }
        return out
    }
}
