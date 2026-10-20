package com.hanzilock.platform

import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope

/** What the phone and the Mac each provide; everything else is shared. */
interface Platform {
    val prefs: Prefs
    val assets: Assets

    /** Build number (the commit count) and version name, shown in Settings > About. */
    val buildNumber: Int
    val versionName: String

    /** What this device is called in the sync repository until you name it ("Phone", "Mac"). */
    val deviceName: String

    /** Opens (creating or migrating) the database file [schema] describes. */
    fun openDatabase(schema: SqlSchema): SqlDb

    fun createSpeaker(): Speaker
    fun createSpeechInput(): SpeechInput
    fun createUpdater(scope: CoroutineScope): Updater

    fun isOnline(): Boolean
}

/** A file you picked, dropped or shared to the app. */
data class PickedFile(val name: String?, val text: String)
