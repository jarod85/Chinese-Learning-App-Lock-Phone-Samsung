package com.hanzilock.desktop

import com.hanzilock.update.Release
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Updates the Mac app from GitHub (see [Updater]): unpacks the new Lingo Lock.app next to the download,
 * then a small script waits for this app to quit, swaps the new one in at the same place and opens it.
 * Your data lives in Application Support, outside the app, so nothing is lost.
 */
class MacUpdater(scope: CoroutineScope) : Updater(
    // Only an installed app can replace itself (not a run from Gradle, not Windows).
    repo = if (DesktopPaths.isMac && DesktopPaths.installedApp != null) BuildInfo.UPDATE_REPO else "",
    currentBuild = BuildInfo.BUILD,
    scope = scope,
    downloadDir = File(DesktopPaths.dataDir, "updates"),
    assetName = { it == ASSET },
) {
    override val installingMessage = "Installing - Lingo Lock restarts in a moment."

    override fun installDownloaded(file: File, release: Release) {
        val target = DesktopPaths.installedApp ?: error("This copy of Lingo Lock can't update itself.")
        val staging = File(file.parentFile, "new").apply {
            deleteRecursively()
            mkdirs()
        }
        run("/usr/bin/ditto", "-x", "-k", file.path, staging.path)
        val app = staging.listFiles()?.firstOrNull { it.name.endsWith(".app") } ?: error("The download has no app in it.")
        val id = run("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier", File(app, "Contents/Info.plist").path).trim()
        if (id != BUNDLE_ID) error("The download isn't a Lingo Lock update.")

        val script = File(file.parentFile, "install.sh")
        script.writeText(
            """
            |#!/bin/sh
            |# Written by Lingo Lock to install build ${release.build}: waits for the app to quit, then swaps it.
            |while kill -0 ${ProcessHandle.current().pid()} 2>/dev/null; do sleep 0.3; done
            |rm -rf "${target.path}.old"
            |mv "${target.path}" "${target.path}.old" && mv "${app.path}" "${target.path}" && rm -rf "${target.path}.old"
            |xattr -cr "${target.path}"
            |open "${target.path}"
            |""".trimMargin(),
        )
        ProcessBuilder("/bin/sh", script.path)
            .redirectOutput(File(file.parentFile, "install.log"))
            .redirectErrorStream(true)
            .start()
        exitProcess(0)
    }

    private fun run(vararg command: String): String {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(2, TimeUnit.MINUTES) || p.exitValue() != 0) error("${command.first().substringAfterLast('/')} failed: ${out.take(200)}")
        return out
    }

    companion object {
        /** The release file the workflow builds for the Mac. */
        const val ASSET = "lingo-lock-mac.zip"
        const val BUNDLE_ID = "com.hanzilock.mac"
    }
}
