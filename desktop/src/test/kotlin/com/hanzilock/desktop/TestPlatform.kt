package com.hanzilock.desktop

import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.platform.Assets
import com.hanzilock.platform.Platform
import com.hanzilock.platform.Prefs
import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import com.hanzilock.update.Release
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.InputStream

/** A device for tests: its own SQLite files and settings in [dir], no bundled content, no speech. */
internal class TestPlatform(private val dir: File) : Platform {
    override val prefs: Prefs = JsonPrefs(File(dir, "settings.json"), secrets = null, secretKeys = emptySet())
    override val assets: Assets = object : Assets {
        override fun open(path: String): InputStream = error("no assets in tests")
        override fun list(dir: String): List<String> = emptyList()
    }
    override val buildNumber = 1
    override val versionName = "test"
    override val deviceName = "Test"
    override fun openDatabase(schema: SqlSchema): SqlDb = JdbcSqlDb.open(dir, schema)
    override fun createSpeaker(): Speaker = NoSpeaker()
    override fun createSpeechInput(): SpeechInput = NoSpeechInput()
    override fun createUpdater(scope: CoroutineScope): Updater = object : Updater("", 1, scope, dir, { false }) {
        override val installingMessage = ""
        override fun installDownloaded(file: File, release: Release) = Unit
    }
    override fun isOnline() = false
}
