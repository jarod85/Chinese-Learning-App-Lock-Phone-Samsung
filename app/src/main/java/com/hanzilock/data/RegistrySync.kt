package com.hanzilock.data

import android.content.Context
import com.hanzilock.core.Settings

/**
 * Applies content/registry/words.json (bundled as an asset) whenever its "version" is higher
 * than the last one applied - that's how edits to the registry in the repo reach the phone.
 */
class RegistrySync(
    private val context: Context,
    private val words: WordRepository,
    private val settings: Settings,
) {
    fun bundled(): RegistryFile =
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { AppJson.decodeFromString<RegistryFile>(it.readText()) }

    /** Returns the number of words added/updated (0 if the bundled registry was already applied). */
    fun syncIfNeeded(): Int {
        val file = bundled()
        if (file.version <= settings.registryVersion) return 0
        val n = words.upsertFromRegistry(file.words)
        settings.registryVersion = file.version
        return n
    }

    companion object {
        const val ASSET = "registry/words.json"

        /**
         * Same layout as the repo's registry file - one word per line - so an export can be
         * dropped into content/registry/words.json and diffs stay readable.
         */
        fun format(file: RegistryFile): String = buildString {
            append("{\n  \"version\": ").append(file.version).append(",\n  \"words\": [\n")
            file.words.forEachIndexed { i, w ->
                append("    ").append(AppJson.encodeToString(RegistryWord.serializer(), w))
                append(if (i < file.words.lastIndex) ",\n" else "\n")
            }
            append("  ]\n}\n")
        }
    }
}
