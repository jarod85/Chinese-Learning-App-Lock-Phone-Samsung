package com.hanzilock.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Files in a private GitHub repository ([repo] = "owner/name"), through GitHub's contents API with a
 * token that may read and write it. Blocking: call it from a background thread.
 */
class GitHubStore(private val repo: String, private val token: String) {
    data class Entry(val name: String, val path: String, val sha: String)

    /** A write lost against a newer version of the file (re-read its sha and try again). */
    class Conflict : IOException("The file changed meanwhile")

    /** The files in [dir] (none if it doesn't exist yet). */
    fun list(dir: String): List<Entry> {
        val (code, body) = request("GET", "contents/$dir")
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            request("GET", "").let { (repoCode, _) -> if (repoCode != HttpURLConnection.HTTP_OK) fail(repoCode) }
            return emptyList()
        }
        if (code != HttpURLConnection.HTTP_OK) fail(code)
        return SyncJson.decodeFromString<List<GhEntry>>(body.decodeToString()).filter { it.type == "file" }.map { Entry(it.name, it.path, it.sha) }
    }

    fun download(path: String): ByteArray {
        val (code, body) = request("GET", "contents/$path", accept = "application/vnd.github.raw+json")
        if (code != HttpURLConnection.HTTP_OK) fail(code)
        return body
    }

    /** Writes [bytes] to [path] ([sha] = the version being replaced, null for a new file). Returns the new sha. */
    fun upload(path: String, bytes: ByteArray, sha: String?, message: String): String {
        val json = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("content", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
            if (sha != null) put("sha", JsonPrimitive(sha))
        }
        val (code, body) = request("PUT", "contents/$path", json.toString().encodeToByteArray())
        if (code == HttpURLConnection.HTTP_CONFLICT || code == 422) throw Conflict()
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_CREATED) fail(code)
        return SyncJson.parseToJsonElement(body.decodeToString()).jsonObject["content"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
            ?: error("GitHub didn't say what it saved")
    }

    /** The current version of [path], or null if there's no such file. */
    fun sha(path: String): String? {
        val (code, body) = request("GET", "contents/$path")
        if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (code != HttpURLConnection.HTTP_OK) fail(code)
        return SyncJson.parseToJsonElement(body.decodeToString()).jsonObject["sha"]?.jsonPrimitive?.content
    }

    private fun request(method: String, path: String, body: ByteArray? = null, accept: String = "application/vnd.github+json"): Pair<Int, ByteArray> {
        val url = "https://api.github.com/repos/$repo" + if (path.isEmpty()) "" else "/$path"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "LingoLock-sync")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            return code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(code: Int): Nothing = throw IOException(
        when (code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> "GitHub didn't accept the sync token (wrong, or expired)."
            HttpURLConnection.HTTP_FORBIDDEN -> "The sync token may not use $repo: it needs \"Contents: Read and write\" for that repository."
            HttpURLConnection.HTTP_NOT_FOUND -> "Repository $repo not found (or the token can't see it)."
            else -> "GitHub answered $code."
        },
    )

    @Serializable
    private data class GhEntry(val name: String, val path: String, val sha: String, val type: String)
}
