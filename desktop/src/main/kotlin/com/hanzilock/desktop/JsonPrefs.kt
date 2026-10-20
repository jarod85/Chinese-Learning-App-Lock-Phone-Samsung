package com.hanzilock.desktop

import com.hanzilock.platform.Prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Preferences in a JSON file (settings.json in the data folder), kept in memory and saved after every
 * change. Keys in [secretKeys] (the Claude API key, the sync token) go to [secrets] instead.
 */
class JsonPrefs(
    private val file: File,
    private val secrets: SecretStore?,
    private val secretKeys: Set<String>,
) : Prefs {
    private val values: MutableMap<String, JsonElement> = runCatching {
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
    }.getOrDefault(JsonObject(emptyMap())).toMutableMap()

    private val secretCache = HashMap<String, String?>()

    private fun isSecret(key: String) = secrets != null && key in secretKeys

    @Synchronized
    override fun getString(key: String, default: String?): String? {
        if (isSecret(key)) {
            if (key !in secretCache) secretCache[key] = secrets!!.read(key)   // also remembers "not set"
            return secretCache[key] ?: default
        }
        return (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default
    }

    @Synchronized
    override fun getInt(key: String, default: Int): Int = (values[key] as? JsonPrimitive)?.intOrNull ?: default

    @Synchronized
    override fun getLong(key: String, default: Long): Long = (values[key] as? JsonPrimitive)?.longOrNull ?: default

    @Synchronized
    override fun getBoolean(key: String, default: Boolean): Boolean = (values[key] as? JsonPrimitive)?.booleanOrNull ?: default

    @Synchronized
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        (values[key] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: default

    @Synchronized
    override fun edit(block: Prefs.Editor.() -> Unit) {
        object : Prefs.Editor {
            override fun putString(key: String, value: String?) {
                if (isSecret(key)) {
                    if (value.isNullOrEmpty()) secrets!!.delete(key) else secrets!!.write(key, value)
                    secretCache[key] = value?.takeIf { it.isNotEmpty() }
                } else {
                    set(key, value?.let(::JsonPrimitive))
                }
            }

            override fun putInt(key: String, value: Int) = set(key, JsonPrimitive(value))
            override fun putLong(key: String, value: Long) = set(key, JsonPrimitive(value))
            override fun putBoolean(key: String, value: Boolean) = set(key, JsonPrimitive(value))
            override fun putStringSet(key: String, value: Set<String>?) = set(key, value?.let { s -> JsonArray(s.sorted().map(::JsonPrimitive)) })
            override fun remove(key: String) = set(key, null)
        }.block()
        save()
    }

    private fun set(key: String, value: JsonElement?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    /** Writes a new file and swaps it in, so a crash never leaves half a file. */
    private fun save() {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(values.toSortedMap())), Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Secrets kept outside the settings file. */
interface SecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}

/**
 * The macOS login keychain, through /usr/bin/security. Values are passed on its standard input (its
 * interactive mode), never on a command line other processes could see, and stored base64-encoded so
 * no character can break that command.
 */
class MacKeychain(private val service: String = "Lingo Lock") : SecretStore {
    override fun read(key: String): String? = runCatching {
        val p = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-a", key, "-w")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() != 0 || !out.startsWith(PREFIX)) return@runCatching null
        String(Base64.getDecoder().decode(out.removePrefix(PREFIX)), Charsets.UTF_8)
    }.getOrNull()

    override fun write(key: String, value: String) {
        val encoded = PREFIX + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        interactive("add-generic-password -U -s \"$service\" -a \"$key\" -w \"$encoded\"")
    }

    override fun delete(key: String) = interactive("delete-generic-password -s \"$service\" -a \"$key\"")

    private fun interactive(command: String) {
        val p = ProcessBuilder("/usr/bin/security", "-i")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.bufferedWriter().use { it.write(command + "\n") }
        p.waitFor()
    }

    private companion object {
        const val PREFIX = "b64:"
    }
}
