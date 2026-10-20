package com.hanzilock.update

import com.hanzilock.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A build published as a GitHub release, with the file for this device (APK on the phone, zip on the Mac). */
data class Release(val build: Int, val title: String, val notes: String, val url: String, val size: Long, val sha256: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val build: Int) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    /** The system needs your OK to let Lingo Lock install updates (once). */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Updates the app over the internet, no cable needed: every build pushed to GitHub is published as a
 * release of [repo] (see .github/workflows/release.yml); this checks the latest one and downloads its
 * file for this device ([assetName] picks it), which the platform then installs.
 */
abstract class Updater(
    /** The GitHub repository ("owner/name") the builds are published in. */
    val repo: String,
    val currentBuild: Int,
    private val scope: CoroutineScope,
    private val downloadDir: File,
    private val assetName: (String) -> Boolean,
) {
    protected val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState

    /** False in builds that don't know their GitHub repository (built outside git). */
    val enabled: Boolean get() = repo.isNotBlank()

    /** What to tell you while the platform installs the update. */
    abstract val installingMessage: String

    /** What to tell you when the system must allow installing updates first. */
    open val permissionMessage: String = ""

    private var lastCheck = 0L

    /** Checks in the background, at most every few hours (called whenever the app comes to the front). */
    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!enabled || now - lastCheck < CHECK_INTERVAL_MS) return
        val s = mutableState.value
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && s !is UpdateState.Failed) return
        lastCheck = now
        scope.launch { check(quiet = true) }
    }

    /** Looks for a newer build. [quiet] checks don't report network errors. */
    suspend fun check(quiet: Boolean = false): UpdateState {
        if (!enabled) return mutableState.value
        lastCheck = System.currentTimeMillis()
        mutableState.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { latestRelease() } }
        mutableState.value = result.fold(
            { r -> if (r != null && r.build > currentBuild) UpdateState.Available(r) else UpdateState.UpToDate(currentBuild) },
            { e -> if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't check for updates: ${e.message ?: "network problem"}", null) },
        )
        return mutableState.value
    }

    /** Downloads [release] and installs it. */
    fun install(release: Release) {
        if (!canInstall()) {
            mutableState.value = UpdateState.NeedsPermission(release)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = download(release)
                    verify(file)
                    mutableState.value = UpdateState.Installing(release)
                    installDownloaded(file, release)
                }
            }
            result.onFailure { mutableState.value = UpdateState.Failed(it.message ?: "Update failed.", release) }
        }
    }

    /** Whether the system lets the app install updates right now. */
    protected open fun canInstall(): Boolean = true

    /** Opens the system screen where you allow Lingo Lock to install updates. */
    open fun openInstallPermission() = Unit

    /** Throws if [file] isn't an update for this app. */
    protected open fun verify(file: File) = Unit

    /** Installs the verified download (on another thread); sets the final state when it knows it. */
    protected abstract fun installDownloaded(file: File, release: Release)

    private fun latestRelease(): Release? {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LingoLock/$currentBuild")
        }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null   // nothing published yet
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("GitHub answered ${conn.responseCode}")
            val gh = AppJson.decodeFromString<GhRelease>(conn.inputStream.bufferedReader().use { it.readText() })
            val build = BUILD_TAG.find(gh.tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            // A release whose file for this device isn't there (yet) is no update.
            val asset = gh.assets.firstOrNull { assetName(it.name) } ?: return null
            return Release(
                build = build,
                title = gh.name?.takeIf { it.isNotBlank() } ?: "Build $build",
                notes = gh.body.orEmpty().trim(),
                url = asset.url,
                size = asset.size,
                sha256 = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release): File {
        val dir = downloadDir.apply { mkdirs() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val file = File(dir, "lingo-lock-${release.build}.${release.url.substringAfterLast('.')}")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "LingoLock/$currentBuild")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mutableState.value = UpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !release.sha256.equals(actual, ignoreCase = true)) {
            file.delete()
            error("The download is damaged (checksum mismatch). Try again.")
        }
        return file
    }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        val digest: String? = null,
    )

    companion object {
        private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L
        private val BUILD_TAG = Regex("""build-(\d+)$""")
    }
}
