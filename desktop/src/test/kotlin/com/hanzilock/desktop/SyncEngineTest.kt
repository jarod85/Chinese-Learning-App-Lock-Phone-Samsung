package com.hanzilock.desktop

import com.hanzilock.AppCore
import com.hanzilock.data.AppDatabase
import com.hanzilock.data.SetFile
import com.hanzilock.data.SetInfo
import com.hanzilock.data.SetWord
import com.hanzilock.data.WordDraft
import com.hanzilock.data.WordScope
import com.hanzilock.data.mapRows
import com.hanzilock.data.str
import com.hanzilock.stories.StoryBody
import com.hanzilock.stories.ComprehensionLevel
import com.hanzilock.stories.StoryDraft
import com.hanzilock.stories.StoryQuestion
import com.hanzilock.sync.SyncEngine
import com.hanzilock.sync.SyncWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Two devices (each its own SQLite files and settings) syncing through snapshots, as SyncManager does. */
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun AppCore.installHsk1() = words.applySet(
        SetInfo("zh.hsk1", "zh", "HSK 1", "Beginner", 1, rev = "1", enabled = true),
        SetFile("zh.hsk1", "zh", "HSK 1", "1", listOf(SetWord("你", reading = "nǐ", meanings = listOf("you")), SetWord("好", reading = "hǎo", meanings = listOf("good")))),
        bundled = true,
    )

    private fun AppCore.setTerms(key: String): List<String> =
        words.list("zh", scope = WordScope.InSet(words.setByKey(key)!!.id)).map { it.term }.sorted()

    @Test
    fun setsYouMakeOnThePhoneReachTheMacAndComeBack() {
        val phone = device("phone")
        val mac = device("mac")
        phone.installHsk1()
        mac.installHsk1()
        // A bundled set's words come with every device, so there's nothing of them to send.
        assertTrue(SyncEngine(mac).export("mac", "Mac").words.isEmpty())

        // An imported list (a new word, and one HSK 1 already has)...
        val travel = phone.words.createSet("zh", "Travel")
        phone.words.insert(WordDraft("zh", "机场", null, "jīchǎng", listOf("airport"), emptyList()), "import", travel)
        phone.words.addToSet(travel, phone.words.find("zh", "你")!!.id)
        // ...a set file...
        phone.words.applySet(
            SetInfo("custom.zh.1.food", "zh", "Food", "Custom", 100, enabled = true, custom = true),
            SetFile("custom.zh.1.food", "zh", "Food", null, listOf(SetWord("米饭", reading = "mǐfàn", meanings = listOf("rice")))),
            bundled = false,
        )
        // ...and both combined with HSK 1.
        val combined = phone.words.combineSets("zh", "Everything", listOf(phone.words.setByKey("zh.hsk1")!!.id, travel), switchOffOriginals = true)
        val travelKey = phone.words.set(travel)!!.key
        val combinedKey = phone.words.set(combined)!!.key

        assertTrue(sync(phone, mac))
        assertEquals("Travel", mac.words.setByKey(travelKey)!!.name)
        assertEquals(listOf("你", "机场"), mac.setTerms(travelKey))
        assertEquals(listOf("米饭"), mac.setTerms("custom.zh.1.food"))
        assertEquals(listOf("你", "好", "机场"), mac.setTerms(combinedKey))
        assertTrue(mac.words.setByKey(combinedKey)!!.enabled)
        assertFalse(mac.words.setByKey(travelKey)!!.enabled)
        assertFalse(mac.words.setByKey("zh.hsk1")!!.enabled)
        assertEquals(listOf("airport"), mac.words.find("zh", "机场")!!.meanings)

        // Changes on the Mac go back to the phone.
        Thread.sleep(5)
        mac.words.renameSet(mac.words.setByKey("custom.zh.1.food")!!.id, "Food & drink")
        mac.words.insert(WordDraft("zh", "护照", null, "hùzhào", listOf("passport"), emptyList()), "user", mac.words.setByKey(travelKey)!!.id)
        assertTrue(sync(mac, phone))
        assertEquals("Food & drink", phone.words.setByKey("custom.zh.1.food")!!.name)
        assertEquals(listOf("你", "护照", "机场"), phone.setTerms(travelKey))

        assertFalse(sync(phone, mac))
        assertFalse(sync(mac, phone))
    }

    @Test
    fun bundledWordsFromAnOlderVersionArentSentOnAgain() {
        val phone = device("phone")
        val mac = device("mac")
        phone.installHsk1()
        mac.installHsk1()
        // An older version sends every word of its bundled sets, with newer timestamps than the Mac's.
        val later = System.currentTimeMillis() + 1_000
        val old = SyncEngine(phone).export("phone", "Phone").copy(
            words = phone.words.list("zh").map { SyncWord("zh", it.term, reading = it.reading, meanings = it.meanings, source = "set", updatedAt = later) },
        )
        assertTrue(SyncEngine(mac).merge(old))
        assertTrue(SyncEngine(mac).export("mac", "Mac").words.isEmpty())
    }

    @Test
    fun upgradingStopsSyncingUntouchedBundledWords() {
        val dir = tmp.newFolder("upgrade")
        val before = JdbcSqlDb.open(dir, AppDatabase)
        fun addWord(term: String, source: String, userEdited: Int = 0) = before.execSQL(
            "INSERT INTO words (lang, term, source, user_edited, touched, created_at, updated_at) VALUES ('zh', ?, ?, ?, 1, 1, 1)",
            arrayOf<Any?>(term, source, userEdited),
        )
        addWord("你", "set")
        addWord("好", "set", userEdited = 1)
        addWord("机场", "import")
        before.execSQL("PRAGMA user_version = 3")

        val after = JdbcSqlDb.open(dir, AppDatabase)
        assertEquals(listOf("好", "机场"), after.rawQuery("SELECT term FROM words WHERE touched=1 ORDER BY term", null).mapRows { it.str("term") })
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

    @Test
    fun comprehensionLevelsAndSelectedPreferenceSyncWithTheStory() {
        val phone = device("phone")
        val mac = device("mac")
        phone.settings.storyComprehensionLevel = ComprehensionLevel.EVALUATIVE
        val literal = listOf(StoryQuestion("谁回家了？"))
        val inferential = listOf(StoryQuestion("小猫为什么想回家？"))
        val evaluative = listOf(StoryQuestion("你觉得小猫应该回家吗？为什么？"))
        val body = StoryBody(questions = literal)
            .withQuestions(ComprehensionLevel.INFERENTIAL, inferential)
            .withQuestions(ComprehensionLevel.EVALUATIVE, evaluative)
        phone.stories.add(StoryDraft("小猫", "The kitten", "Claude", null, "HSK 2", body))

        assertTrue(sync(phone, mac))
        val onMac = mac.stories.get(mac.stories.list().single().id)!!
        assertEquals(ComprehensionLevel.EVALUATIVE, mac.settings.storyComprehensionLevel)
        assertEquals(literal, onMac.body.questionsFor(ComprehensionLevel.LITERAL))
        assertEquals(inferential, onMac.body.questionsFor(ComprehensionLevel.INFERENTIAL))
        assertEquals(evaluative, onMac.body.questionsFor(ComprehensionLevel.EVALUATIVE))
        assertFalse(sync(phone, mac))
    }
}
