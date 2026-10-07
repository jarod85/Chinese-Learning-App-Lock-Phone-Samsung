package com.hanzilock.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.hanzilock.BuildConfig
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

/**
 * Updates the app over the internet, no cable needed: every build pushed to GitHub is published
 * as a release of [BuildConfig.UPDATE_REPO] (see .github/workflows/release.yml or
 * tools/publish.ps1); this checks the latest one, downloads its APK and hands it to Android's
 * package installer. The APK is signed with your own key, so Android installs it over this app and
 * keeps all your progress.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    data class Release(val build: Int, val title: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String?)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val build: Int) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Android needs your OK to let Lingo Lock install updates (once). */
        data class NeedsPermission(val release: Release) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** False in builds that don't know their GitHub repository (built outside git). */
    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    private var lastCheck = 0L

    /** Checks in the background, at most every few hours (called whenever the app comes to the front). */
    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!enabled || now - lastCheck < CHECK_INTERVAL_MS) return
        if (_state.value !is State.Idle && _state.value !is State.UpToDate && _state.value !is State.Failed) return
        lastCheck = now
        scope.launch { check(quiet = true) }
    }

    /** Looks for a newer build. [quiet] checks don't report network errors. */
    suspend fun check(quiet: Boolean = false): State {
        if (!enabled) return _state.value
        lastCheck = System.currentTimeMillis()
        _state.value = State.Checking
        val result = withContext(Dispatchers.IO) { runCatching { latestRelease() } }
        _state.value = result.fold(
            { r -> if (r != null && r.build > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate(BuildConfig.VERSION_CODE) },
            { e -> if (quiet) State.Idle else State.Failed("Couldn't check for updates: ${e.message ?: "network problem"}", null) },
        )
        return _state.value
    }

    /** Downloads [release] and starts installing it; Android then asks you to confirm. */
    fun install(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = download(release)
                    _state.value = State.Installing(release)
                    commit(apk)
                }
            }
            result.onFailure { _state.value = State.Failed(it.message ?: "Update failed.", release) }
        }
    }

    /** Settings screen where you allow Lingo Lock to install apps ("Install unknown apps"). */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Called by [UpdateReceiver] with the package installer's answer. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate(release?.build ?: BuildConfig.VERSION_CODE)
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { State.Available(it) } ?: State.Idle
            else -> _state.value = State.Failed("The update wasn't installed${message?.let { ": $it" } ?: "."}", release)
        }
    }

    private fun latestRelease(): Release? {
        val conn = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LingoLock/${BuildConfig.VERSION_CODE}")
        }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null   // nothing published yet
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("GitHub answered ${conn.responseCode}")
            val gh = AppJson.decodeFromString<GhRelease>(conn.inputStream.bufferedReader().use { it.readText() })
            val build = BUILD_TAG.find(gh.tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val asset = gh.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            return Release(
                build = build,
                title = gh.name?.takeIf { it.isNotBlank() } ?: "Build $build",
                notes = gh.body.orEmpty().trim(),
                apkUrl = asset.url,
                size = asset.size,
                sha256 = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "lingo-lock-${release.build}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "LingoLock/${BuildConfig.VERSION_CODE}")
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
                            _state.value = State.Downloading(release, percent)
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
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != context.packageName) {
            file.delete()
            error("The download isn't a Lingo Lock update.")
        }
        return file
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("lingo-lock.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
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
