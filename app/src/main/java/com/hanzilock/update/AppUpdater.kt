package com.hanzilock.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.hanzilock.BuildConfig
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Installs updates downloaded from GitHub (see [Updater]) with Android's package installer. The APK is
 * signed with your own key, so Android installs it over this app and keeps all your progress.
 */
class AppUpdater(private val context: Context, scope: CoroutineScope) : Updater(
    repo = BuildConfig.UPDATE_REPO,
    currentBuild = BuildConfig.VERSION_CODE,
    scope = scope,
    downloadDir = File(context.cacheDir, "updates"),
    assetName = { it.endsWith(".apk") },
) {
    override val installingMessage = "Installing - confirm with Update when Android asks."
    override val permissionMessage =
        "One-time step: allow Lingo Lock to install updates (turn on \"Allow from this source\"), come back, then tap Install."

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings screen where you allow Lingo Lock to install apps ("Install unknown apps"). */
    override fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun verify(file: File) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != context.packageName) {
            file.delete()
            error("The download isn't a Lingo Lock update.")
        }
    }

    /** Hands the APK to the package installer; Android then asks you to confirm. */
    override fun installDownloaded(file: File, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("lingo-lock.apk", 0, file.length()).use { out ->
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

    /** Called by [UpdateReceiver] with the package installer's answer. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> mutableState.value = UpdateState.UpToDate(release?.build ?: currentBuild)
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutableState.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> mutableState.value = UpdateState.Failed("The update wasn't installed${message?.let { ": $it" } ?: "."}", release)
        }
    }
}
