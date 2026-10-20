package com.hanzilock.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.Writer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Swift helper bundled with the Mac app (macos/lingo-helper): Apple's voices, speech recognition and
 * which app is in front. It runs for as long as the app does and talks in JSON lines: commands on its
 * standard input, events on its standard output. Started on first use; restarted if it stops.
 */
class MacHelper(private val executable: File) {
    fun interface Listener {
        fun onEvent(event: JsonObject)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var process: Process? = null
    private var input: Writer? = null

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** The apps in the Applications folders, sorted by name (empty if the helper doesn't answer). */
    suspend fun installedApps(): List<InstalledApp> {
        val answer = CompletableDeferred<JsonObject>()
        val listener = Listener { e -> if (e.eventType == "apps") answer.complete(e) }
        addListener(listener)
        return try {
            send("apps")
            val apps = withTimeoutOrNull(10_000) { answer.await() }?.get("apps") as? JsonArray
            apps.orEmpty().mapNotNull { a ->
                val o = a.jsonObject
                InstalledApp(o.string("id") ?: return@mapNotNull null, o.string("name").orEmpty(), o.string("path").orEmpty())
            }
        } finally {
            removeListener(listener)
        }
    }

    data class InstalledApp(val bundleId: String, val name: String, val path: String)

    /** Sends [command] ("cmd" plus its fields), starting the helper if needed. */
    @Synchronized
    fun send(cmd: String, vararg fields: Pair<String, Any?>) {
        val line = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("cmd", JsonPrimitive(cmd))
                for ((key, value) in fields) {
                    when (value) {
                        null -> Unit
                        is String -> put(key, JsonPrimitive(value))
                        is Number -> put(key, JsonPrimitive(value))
                        is Boolean -> put(key, JsonPrimitive(value))
                        else -> put(key, JsonPrimitive(value.toString()))
                    }
                }
            },
        )
        val writer = ensureRunning() ?: return
        runCatching {
            writer.write(line + "\n")
            writer.flush()
        }.onFailure { stopProcess() }
    }

    /** Starts the helper now (so the front-app events and voices are there early). */
    fun start() {
        synchronized(this) { ensureRunning() }
    }

    private fun ensureRunning(): Writer? {
        process?.takeIf { it.isAlive }?.let { return input }
        val p = runCatching {
            ProcessBuilder(executable.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        }.getOrNull() ?: return null
        process = p
        input = p.outputStream.bufferedWriter(Charsets.UTF_8)
        Thread({ readEvents(p) }, "lingo-helper").apply { isDaemon = true }.start()
        return input
    }

    private fun readEvents(p: Process) {
        p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                listeners.forEach { runCatching { it.onEvent(event) } }
            }
        }
        listeners.forEach { runCatching { it.onEvent(EXITED) } }
    }

    @Synchronized
    private fun stopProcess() {
        process?.destroy()
        process = null
        input = null
    }

    companion object {
        /** Sent to the listeners when the helper has stopped. */
        val EXITED = buildJsonObject { put("event", JsonPrimitive("exited")) }
    }
}

/** The "event" field of a helper message. */
val JsonObject.eventType: String? get() = this["event"]?.jsonPrimitive?.content

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
