package com.hanzilock.desktop

import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.platform.Assets
import com.hanzilock.platform.Platform
import com.hanzilock.platform.Prefs
import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.InputStream
import java.net.NetworkInterface

/** The Mac's side of [Platform]: files in Application Support, Apple's voices through the helper. */
class DesktopPlatform : Platform {
    /** The Swift helper (null on Windows / Linux, or when run from Gradle without it). */
    val helper: MacHelper? = DesktopPaths.helper?.let(::MacHelper)

    val permissions: MacPermissions? = helper?.let(::MacPermissions)

    override val prefs: Prefs = JsonPrefs(
        File(DesktopPaths.dataDir, "settings.json"),
        secrets = if (DesktopPaths.isMac) MacKeychain() else null,
        secretKeys = setOf("claude_api_key", "sync_token"),
    )

    override val assets: Assets = object : Assets {
        override fun open(path: String): InputStream = File(DesktopPaths.contentDir, path).inputStream()
        override fun list(dir: String): List<String> = File(DesktopPaths.contentDir, dir).list()?.toList().orEmpty()
    }

    override val buildNumber: Int = BuildInfo.BUILD
    override val versionName: String = BuildInfo.VERSION_NAME
    override val deviceName = if (DesktopPaths.isMac) "Mac" else "Computer"

    override fun openDatabase(schema: SqlSchema): SqlDb = JdbcSqlDb.open(DesktopPaths.dataDir, schema)

    override fun createSpeaker(): Speaker = helper?.let(::MacSpeaker) ?: NoSpeaker()
    override fun createSpeechInput(): SpeechInput = helper?.let(::MacSpeechInput) ?: NoSpeechInput()
    override fun createUpdater(scope: CoroutineScope): Updater = MacUpdater(scope)

    /** A network connection is up (Claude is only asked when there is one). */
    override fun isOnline(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { n ->
            n.isUp && !n.isLoopback && !n.isVirtual && n.inetAddresses.asSequence().any { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        }
    }.getOrDefault(true)
}
