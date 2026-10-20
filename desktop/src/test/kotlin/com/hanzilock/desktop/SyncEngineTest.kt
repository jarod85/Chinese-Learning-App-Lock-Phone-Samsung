package com.hanzilock.desktop

import com.hanzilock.AppCore
import com.hanzilock.data.SetFile
import com.hanzilock.data.SetInfo
import com.hanzilock.data.SetWord
import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.data.WordDraft
import com.hanzilock.data.WordScope
import com.hanzilock.platform.Assets
import com.hanzilock.platform.Platform
import com.hanzilock.platform.Prefs
import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import com.hanzilock.stories.StoryBody
import com.hanzilock.stories.StoryDraft
import com.hanzilock.sync.SyncEngine
import com.hanzilock.update.Release
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

/** Two devices (each its own SQLite files and settings) syncing through snapshots, as SyncManager does. */
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class TestPlatform(private val dir: File) : Platform {
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

    private fun device(name: String) = AppCore(TestPlatform(tmp.newFolder(name)))

    /** [to] takes what [from] has, as if [from]'s file was downloaded. */
    private fun sync(from: AppCore, to: AppCore): Boolean = SyncEngine(to).merge(SyncEngine(from).export(from.settings.syncDeviceId, "x"))

    private fun AppCore.addWord(term: String, meaning: String): Long =
        words.insert(WordDraft("zh", term, null, "nǐ hǎo", listOf(meaning), emptyList()), "user", words.myWordsSet("zh"))

    @Test
    fun progressHistoryAndOwnSetsReachTheOtherDevice() {
        val phone = device("phone")
        val mac = device("mac")
        val id = phone.addWord("你好", "hello")
        val session = phone.words.createSession("practice", "zh", 1_000)
        phone.words.recordAttempt(phone.words.get(id)!!, session, true, null, "你好", "hello", null, null, "offline", now = 2_000)

        assertTrue(sync(phone, mac))
        val w = mac.words.find("zh", "你好")
        assertNotNull(w)
        assertEquals(phone.words.get(id)!!.box, w!!.box)
        assertEquals(2_000L, w.lastSeenAt)
        assertEquals(1, mac.words.totals("zh").wins)
        val mine = mac.words.setByKey("zh.mine")
        assertNotNull(mine)
        assertEquals(listOf("你好"), mac.words.list("zh", scope = WordScope.InSet(mine!!.id)).map { it.term })

        // Merging the same data again changes nothing (and the mac's own snapshot doesn't bounce anything back).
        assertFalse(sync(phone, mac))
        assertFalse(sync(mac, phone))
        assertEquals(1, phone.words.totals("zh").wins)
    }

    @Test
    fun newestProgressWinsAndYourEditBeatsABundleUpdate() {
        val phone = device("phone")
        val mac = device("mac")
        phone.addWord("谢谢", "thanks")
        sync(phone, mac)
        // You fix the meaning on the Mac...
        val macWord = mac.words.find("zh", "谢谢")!!
        mac.words.update(macWord.id, WordDraft("zh", "谢谢", null, "xièxie", listOf("thank you"), emptyList()), now = 5_000)
        // ...while the phone gets a newer, unedited update (as from a bundled set) and practises the word.
        val phoneWord = phone.words.find("zh", "谢谢")!!
        phone.words.applySet(
            SetInfo("zh.hsk1", "zh", "HSK 1", rev = "2"),
            SetFile("zh.hsk1", "zh", "HSK 1", "2", listOf(SetWord("谢谢", reading = "xièxie", meanings = listOf("thanks (bundle)")))),
            bundled = true,
        )
        phone.words.recordAttempt(phoneWord, null, false, null, null, null, null, null, "offline", now = 10_000)

        sync(mac, phone)
        sync(phone, mac)
        for (d in listOf(phone, mac)) {
            val w = d.words.find("zh", "谢谢")!!
            assertEquals(listOf("thank you"), w.meanings)
            assertTrue(w.userEdited)
            assertEquals(10_000L, w.lastSeenAt)
            assertEquals(1, w.losses)
        }
    }

    @Test
    fun deletionsWinOverOlderData() {
        val phone = device("phone")
        val mac = device("mac")
        val set = phone.words.createSet("zh", "Travel", now = 1_000)
        phone.words.insert(WordDraft("zh", "机场", null, "jīchǎng", listOf("airport"), emptyList()), "user", set)
        sync(phone, mac)
        val key = phone.words.set(set)!!.key
        assertNotNull(mac.words.setByKey(key))

        phone.words.deleteSet(set)
        sync(phone, mac)
        assertNull(mac.words.setByKey(key))
        assertNull(mac.words.find("zh", "机场"))
        // And the mac's (now older) data doesn't bring it back.
        sync(mac, phone)
        assertNull(phone.words.setByKey(key))
    }

    @Test
    fun aFinishedSessionUnlocksTheOtherDevice() {
        val phone = device("phone")
        val mac = device("mac")
        mac.pin.setPin("1234")
        mac.settings.lockEnabled = true
        mac.settings.lastCompletedAt = 0
        assertTrue(mac.lockEngine.isLockDue())

        phone.lockEngine.markCompleted(System.currentTimeMillis(), shared = true)
        sync(phone, mac)
        assertFalse(mac.lockEngine.isLockDue())
    }

    @Test
    fun aPinSkipIsNotShared() {
        val phone = device("phone")
        val mac = device("mac")
        mac.pin.setPin("1234")
        mac.settings.lockEnabled = true
        mac.settings.lastCompletedAt = 0
        phone.lockEngine.markCompleted(System.currentTimeMillis())
        sync(phone, mac)
        assertTrue(mac.lockEngine.isLockDue())
    }

    @Test
    fun sharedSettingsFollowTheNewestChange() {
        val phone = device("phone")
        val mac = device("mac")
        phone.settings.wordsPerSession = 9
        Thread.sleep(5)
        mac.settings.storyLevel = 3   // newer: the mac's settings win as a whole
        sync(phone, mac)
        assertEquals(5, mac.settings.wordsPerSession)
        assertEquals(3, mac.settings.storyLevel)
        sync(mac, phone)
        assertEquals(5, phone.settings.wordsPerSession)
        assertEquals(3, phone.settings.storyLevel)
        // Device-only settings stay put.
        phone.settings.claudeApiKey = "sk-phone"
        sync(phone, mac)
        assertEquals("", mac.settings.claudeApiKey)
    }

    @Test
    fun storiesAndTheirDeletionSync() {
        val phone = device("phone")
        val mac = device("mac")
        val id = phone.stories.add(StoryDraft("小猫", "The kitten", "claude", null, "HSK 1", StoryBody()))
        sync(phone, mac)
        val onMac = mac.stories.list().single()
        assertEquals("小猫", onMac.title)

        mac.stories.savePage(onMac.id, 2)
        sync(mac, phone)
        assertEquals(2, phone.stories.get(id)!!.page)

        mac.stories.delete(onMac.id)
        sync(mac, phone)
        assertTrue(phone.stories.list().isEmpty())
    }
}
