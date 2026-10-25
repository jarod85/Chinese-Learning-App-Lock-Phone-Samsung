package com.hanzilock.desktop

import com.hanzilock.AppCore
import com.hanzilock.data.WordDraft
import com.hanzilock.quiz.SessionKind
import com.hanzilock.quiz.SessionManager
import com.hanzilock.quiz.WordOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Practising one chosen set without end, and switching between sets. */
class EndlessPracticeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app by lazy { AppCore(TestPlatform(tmp.newFolder("device"))) }

    private fun set(name: String, enabled: Boolean, vararg terms: String): Pair<Long, Set<Long>> {
        val setId = app.words.createSet("zh", name, enabled = enabled)
        val ids = terms.map { app.words.insert(WordDraft("zh", it, null, "x", listOf("meaning of $it"), emptyList()), "user", setId) }
        return setId to ids.toSet()
    }

    private fun started(start: SessionManager.Start) = (start as SessionManager.Start.Running).session

    @Test
    fun endlessSessionNeverCompletesAndOnlyUsesItsSet() {
        val (food, foodWords) = set("Food", enabled = false, "饭", "面", "茶")
        set("Travel", enabled = true, "车", "路")
        var s = started(app.sessions.startOrResume(SessionKind.PRACTICE, setId = food))
        assertTrue(s.endless)
        repeat(40) { i ->
            val word = s.currentWordId!!
            assertTrue("word $word isn't in the chosen set", word in foodWords)
            s = if (i % 3 == 0) app.sessions.skipWord()!! else app.sessions.record(WordOutcome(word, i % 2 == 0, null, null, null, null, null, "offline"))!!
            assertFalse(s.isComplete)
        }
        assertTrue("the queue is trimmed", s.queue.size < 20)
    }

    @Test
    fun switchingSetsEndsTheOldSessionAndPlainPracticeEndsEndless() {
        val (food, foodWords) = set("Food", enabled = true, "饭", "面")
        val (travel, travelWords) = set("Travel", enabled = false, "车", "路")
        val first = started(app.sessions.startOrResume(SessionKind.PRACTICE, setId = food))
        assertEquals(first.id, started(app.sessions.startOrResume(SessionKind.PRACTICE, setId = food)).id)

        val switched = started(app.sessions.startOrResume(SessionKind.PRACTICE, setId = travel))
        assertNotEquals(first.id, switched.id)
        assertTrue(switched.currentWordId in travelWords)

        val plain = started(app.sessions.startOrResume(SessionKind.PRACTICE))
        assertFalse(plain.endless)
        assertTrue(plain.currentWordId in foodWords)
    }

    @Test
    fun aDueLockReplacesEndlessPracticeButEndlessCantSkipALock() {
        val (food, _) = set("Food", enabled = true, "饭", "面")
        app.pin.setPin("1234")
        val endless = started(app.sessions.startOrResume(SessionKind.PRACTICE, setId = food))

        // Nothing due: the lock leaves endless practice alone.
        assertEquals(SessionManager.Start.NotNeeded, app.sessions.startOrResume(SessionKind.LOCK))
        assertEquals(endless.id, app.sessions.active.value?.id)

        app.lockEngine.lockNow()
        val lock = started(app.sessions.startOrResume(SessionKind.LOCK))
        assertFalse(lock.endless)
        assertNotEquals(endless.id, lock.id)
        // Asking for endless practice now resumes the lock session instead.
        assertEquals(lock.id, started(app.sessions.startOrResume(SessionKind.PRACTICE, setId = food)).id)
    }

    @Test
    fun anEmptySetHasNothingToPractise() {
        val (empty, _) = set("Empty", enabled = true)
        assertEquals(SessionManager.Start.NoWords, app.sessions.startOrResume(SessionKind.PRACTICE, setId = empty))
        assertNull(app.sessions.active.value)
    }
}
