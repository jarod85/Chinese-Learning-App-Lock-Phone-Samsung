package com.hanzilock.sync

import com.hanzilock.AppCore
import com.hanzilock.data.AppJson
import com.hanzilock.data.ContentValues
import com.hanzilock.data.Cursor
import com.hanzilock.data.Example
import com.hanzilock.data.SqlDb
import com.hanzilock.data.WordRepository.Companion.DELETED_MEMBER
import com.hanzilock.data.WordRepository.Companion.DELETED_SET
import com.hanzilock.data.WordRepository.Companion.DELETED_WORD
import com.hanzilock.data.WordRepository.Companion.memberKey
import com.hanzilock.data.WordRepository.Companion.wordKey
import com.hanzilock.data.inTransaction
import com.hanzilock.data.int
import com.hanzilock.data.long
import com.hanzilock.data.longOrNull
import com.hanzilock.data.mapRows
import com.hanzilock.data.str
import com.hanzilock.data.strOrNull
import com.hanzilock.stories.StoryRepository
import kotlinx.serialization.encodeToString

/**
 * Turns this device's data into a [SyncSnapshot] and merges other devices' snapshots into it:
 *
 * - a word's content: the newer edit wins, and your own edit beats an update from a bundled set;
 * - a word's progress (review box, due date, score): the most recently practised wins;
 * - sets and stories: the newer change wins; a set's words are combined;
 * - sessions and attempts: every device's are kept, so stats cover all of them;
 * - deletions win over anything older;
 * - a finished session unlocks every device until its next reset time.
 *
 * Merging the same snapshot twice changes nothing.
 */
class SyncEngine(private val core: AppCore) {
    private val words get() = core.words.db
    private val stories get() = core.stories.db

    // ---- export -----------------------------------------------------------------------------------

    fun export(device: String, deviceName: String, now: Long = System.currentTimeMillis()): SyncSnapshot = SyncSnapshot(
        device = device,
        deviceName = deviceName,
        exportedAt = now,
        completedAt = core.settings.sharedCompletedAt,
        settings = core.settings.shared(),
        words = words.rawQuery("SELECT * FROM words WHERE touched=1 OR last_seen_at>0 ORDER BY lang, term", null).mapRows { it.toSyncWord() },
        sets = exportSets(),
        sessions = words.rawQuery("SELECT * FROM sessions WHERE uid IS NOT NULL ORDER BY uid", null).mapRows {
            SyncSession(
                it.str("uid"), it.str("kind"), it.str("lang"), it.long("started_at"), it.longOrNull("finished_at"),
                it.strOrNull("outcome"), it.int("wins"), it.int("losses"),
            )
        },
        attempts = words.rawQuery(
            "SELECT a.*, s.uid AS session_uid FROM attempts a LEFT JOIN sessions s ON s.id=a.session_id WHERE a.uid IS NOT NULL ORDER BY a.uid",
            null,
        ).mapRows {
            SyncAttempt(
                it.str("uid"), it.strOrNull("session_uid"), it.str("lang"), it.str("term"), it.long("at"), it.int("win") != 0,
                it.strOrNull("failed_part"), it.strOrNull("heard"), it.strOrNull("meaning_answer"), it.strOrNull("sentence_answer"),
                it.strOrNull("feedback"), it.strOrNull("graded_by"),
            )
        },
        stories = stories.rawQuery("SELECT * FROM stories WHERE uid IS NOT NULL ORDER BY uid", null).mapRows {
            SyncStory(
                it.str("uid"), it.str("title"), it.str("title_en"), it.str("source"), it.strOrNull("url"), it.strOrNull("level"),
                it.int("chars"), it.int("pages"), it.int("page"), it.str("body"), it.longOrNull("finished_at"),
                it.long("created_at"), it.long("updated_at"),
            )
        },
        deletions = (deletions(words) + deletions(stories)).sortedWith(compareBy({ it.kind }, { it.key })),
    )

    private fun exportSets(): List<SyncSet> =
        words.rawQuery("SELECT * FROM sets WHERE custom=1 OR updated_at>0 ORDER BY key", null).mapRows {
            SyncSet(
                it.str("key"), it.str("lang"), it.str("name"), it.strOrNull("level"), it.int("sort"), it.int("custom") != 0,
                it.int("enabled") != 0, it.long("created_at"), it.long("updated_at"),
                members = if (it.int("custom") != 0) members(it.long("id")) else emptyList(),
            )
        }

    private fun members(setId: Long): List<SyncMember> = words.rawQuery(
        "SELECT w.term, sw.added_at FROM set_words sw JOIN words w ON w.id=sw.word_id WHERE sw.set_id=? ORDER BY w.term",
        arrayOf(setId.toString()),
    ).mapRows { SyncMember(it.str("term"), it.long("added_at")) }

    private fun deletions(db: SqlDb): List<SyncDeletion> =
        db.rawQuery("SELECT kind, key, at FROM deletions", null).mapRows { SyncDeletion(it.str("kind"), it.str("key"), it.long("at")) }

    private fun Cursor.toSyncWord() = SyncWord(
        lang = str("lang"),
        term = str("term"),
        traditional = strOrNull("traditional"),
        reading = str("reading"),
        meanings = strings(str("meanings")),
        altMeanings = strings(str("alt_meanings")),
        examples = runCatching { AppJson.decodeFromString<List<Example>>(str("examples")) }.getOrDefault(emptyList()),
        forms = strings(str("forms")),
        tags = strings(str("tags")),
        source = str("source"),
        userEdited = int("user_edited") != 0,
        enabled = int("enabled") != 0,
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        box = int("box"),
        dueAt = long("due_at"),
        wins = int("wins"),
        losses = int("losses"),
        lastSeenAt = long("last_seen_at"),
    )

    // ---- merge ------------------------------------------------------------------------------------

    /** Merges [s] (another device's snapshot) into this device's data. Returns true if anything changed. */
    fun merge(s: SyncSnapshot): Boolean {
        var changed = mergeSettings(s)
        val wordsChanged = words.inTransaction {
            val tombs = localDeletions(this)
            var c = false
            for (w in s.words) c = mergeWord(this, w, tombs) or c
            for (set in s.sets) c = mergeSet(this, set, tombs) or c
            c = applyDeletions(this, s.deletions) or c
            c = mergeHistory(this, s) or c
            c
        }
        val storiesChanged = stories.inTransaction {
            val tombs = localDeletions(this)
            var c = false
            for (story in s.stories) c = mergeStory(this, story, tombs) or c
            for (d in s.deletions.filter { it.kind == DELETED_STORY }) c = deleteStory(this, d) or c
            c
        }
        if (s.completedAt > core.settings.sharedCompletedAt) {
            core.settings.sharedCompletedAt = s.completedAt
            changed = true
        }
        if (wordsChanged) core.words.changed()
        if (storiesChanged) core.stories.changed()
        return changed || wordsChanged || storiesChanged
    }

    private fun mergeSettings(s: SyncSnapshot): Boolean {
        val remote = s.settings ?: return false
        val local = core.settings.shared()
        if (remote.at <= local.at || remote.copy(at = local.at) == local) return false
        // Like changing the schedule here: a reset time that already passed today doesn't lock you right away.
        val wasDue = core.lockEngine.isLockDue()
        core.settings.applyShared(remote)
        if (!wasDue && remote.resetTimes != local.resetTimes) {
            core.settings.lastCompletedAt = maxOf(core.settings.lastCompletedAt, System.currentTimeMillis())
        }
        return true
    }

    private fun localDeletions(db: SqlDb): Map<Pair<String, String>, Long> =
        db.rawQuery("SELECT kind, key, at FROM deletions", null).mapRows { (it.str("kind") to it.str("key")) to it.long("at") }.toMap()

    private fun mergeWord(db: SqlDb, w: SyncWord, tombs: Map<Pair<String, String>, Long>): Boolean {
        val local = db.rawQuery(
            "SELECT id, user_edited, updated_at, last_seen_at, alt_meanings FROM words WHERE lang=? AND term=?",
            arrayOf(w.lang, w.term),
        ).mapRows { LocalWord(it.long("id"), it.int("user_edited") != 0, it.long("updated_at"), it.long("last_seen_at"), strings(it.str("alt_meanings"))) }
            .firstOrNull()
        if (local == null) {
            val deletedAt = tombs[DELETED_WORD to wordKey(w.lang, w.term)]
            if (deletedAt != null && deletedAt >= maxOf(w.updatedAt, w.lastSeenAt)) return false
            db.insertOrThrow("words", null, content(w).apply {
                put("lang", w.lang); put("term", w.term); put("source", w.source); put("alt_meanings", json(w.altMeanings))
                put("created_at", w.createdAt); put("touched", 1)
                putProgress(w)
            })
            return true
        }
        val cv = ContentValues()
        val remoteContentWins = (w.userEdited && !local.userEdited) || (w.userEdited == local.userEdited && w.updatedAt > local.updatedAt)
        if (remoteContentWins) {
            for ((k, v) in content(w).entries) cv.put(k, v)
        }
        val alt = (local.altMeanings + w.altMeanings).distinctBy { it.lowercase() }
        if (alt != local.altMeanings) cv.put("alt_meanings", json(alt))
        if (w.lastSeenAt > local.lastSeenAt) cv.putProgress(w)
        if (cv.isEmpty()) return false
        cv.put("touched", 1)
        db.update("words", cv, "id=?", arrayOf(local.id.toString()))
        return true
    }

    private class LocalWord(val id: Long, val userEdited: Boolean, val updatedAt: Long, val lastSeenAt: Long, val altMeanings: List<String>)

    private fun content(w: SyncWord) = ContentValues().apply {
        put("traditional", w.traditional)
        put("reading", w.reading)
        put("meanings", json(w.meanings))
        put("examples", AppJson.encodeToString(w.examples))
        put("forms", json(w.forms))
        put("tags", json(w.tags))
        put("user_edited", if (w.userEdited) 1 else 0)
        put("enabled", if (w.enabled) 1 else 0)
        put("updated_at", w.updatedAt)
    }

    private fun ContentValues.putProgress(w: SyncWord) {
        put("box", w.box); put("due_at", w.dueAt); put("wins", w.wins); put("losses", w.losses); put("last_seen_at", w.lastSeenAt)
    }

    private fun mergeSet(db: SqlDb, s: SyncSet, tombs: Map<Pair<String, String>, Long>): Boolean {
        var changed = false
        var local = db.rawQuery("SELECT id, updated_at FROM sets WHERE key=?", arrayOf(s.key)).mapRows { it.long("id") to it.long("updated_at") }.firstOrNull()
        if (local == null) {
            // A bundled set this device doesn't have (e.g. a language pack not downloaded here) is left out.
            if (!s.custom) return false
            val deletedAt = tombs[DELETED_SET to s.key]
            if (deletedAt != null && deletedAt >= s.updatedAt) return false
            val id = db.insertOrThrow("sets", null, ContentValues().apply {
                put("key", s.key); put("lang", s.lang); put("name", s.name); put("level", s.level); put("sort", s.sort)
                put("bundled", 0); put("custom", 1); put("enabled", if (s.enabled) 1 else 0)
                put("created_at", s.createdAt); put("updated_at", s.updatedAt)
            })
            local = id to s.updatedAt
            changed = true
        } else if (s.updatedAt > local.second) {
            db.update("sets", ContentValues().apply {
                if (s.custom) put("name", s.name)
                put("enabled", if (s.enabled) 1 else 0)
                put("updated_at", s.updatedAt)
            }, "id=?", arrayOf(local.first.toString()))
            changed = true
        }
        if (!s.custom) return changed
        val present = db.rawQuery("SELECT w.term FROM set_words sw JOIN words w ON w.id=sw.word_id WHERE sw.set_id=?", arrayOf(local.first.toString()))
            .mapRows { it.str("term") }.toHashSet()
        for (m in s.members) {
            if (m.term in present) continue
            val removedAt = tombs[DELETED_MEMBER to memberKey(s.key, s.lang, m.term)]
            if (removedAt != null && removedAt >= m.addedAt) continue
            val wordId = db.rawQuery("SELECT id FROM words WHERE lang=? AND term=?", arrayOf(s.lang, m.term)).mapRows { it.getLong(0) }.firstOrNull()
                ?: continue
            core.words.addMembership(db, local.first, wordId, m.addedAt, custom = true)
            changed = true
        }
        return changed
    }

    /** Deletions another device made, of things not changed here since. */
    private fun applyDeletions(db: SqlDb, deletions: List<SyncDeletion>): Boolean {
        var changed = false
        for (d in deletions) {
            when (d.kind) {
                DELETED_WORD -> {
                    val (lang, term) = d.key.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue
                    val id = db.rawQuery(
                        "SELECT id FROM words WHERE lang=? AND term=? AND updated_at<? AND last_seen_at<? AND created_at<?",
                        arrayOf(lang, term, d.at.toString(), d.at.toString(), d.at.toString()),
                    ).mapRows { it.getLong(0) }.firstOrNull() ?: continue
                    core.words.recordDeletion(db, d.kind, d.key, d.at)
                    db.delete("set_words", "word_id=?", arrayOf(id.toString()))
                    db.delete("words", "id=?", arrayOf(id.toString()))
                    changed = true
                }
                DELETED_SET -> {
                    val id = db.rawQuery(
                        "SELECT id FROM sets WHERE key=? AND updated_at<? AND created_at<?",
                        arrayOf(d.key, d.at.toString(), d.at.toString()),
                    ).mapRows { it.getLong(0) }.firstOrNull() ?: continue
                    core.words.recordDeletion(db, d.kind, d.key, d.at)
                    val members = db.rawQuery("SELECT word_id FROM set_words WHERE set_id=?", arrayOf(id.toString())).mapRows { it.getLong(0) }
                    db.delete("set_words", "set_id=?", arrayOf(id.toString()))
                    db.delete("sets", "id=?", arrayOf(id.toString()))
                    // As when you delete a set here: its words go too unless another set has them.
                    for (wordId in members) {
                        val stillUsed = db.rawQuery("SELECT 1 FROM set_words WHERE word_id=? LIMIT 1", arrayOf(wordId.toString())).mapRows { true }.isNotEmpty()
                        if (!stillUsed) db.delete("words", "id=?", arrayOf(wordId.toString()))
                    }
                    changed = true
                }
                DELETED_MEMBER -> {
                    val parts = d.key.split('\t', limit = 3).takeIf { it.size == 3 } ?: continue
                    val (setKey, lang, term) = parts
                    val removed = db.rawQuery(
                        "SELECT sw.set_id, sw.word_id FROM set_words sw JOIN sets s ON s.id=sw.set_id JOIN words w ON w.id=sw.word_id " +
                            "WHERE s.key=? AND w.lang=? AND w.term=? AND sw.added_at<?",
                        arrayOf(setKey, lang, term, d.at.toString()),
                    ).mapRows { it.getLong(0) to it.getLong(1) }.firstOrNull() ?: continue
                    core.words.recordDeletion(db, d.kind, d.key, d.at)
                    db.delete("set_words", "set_id=? AND word_id=?", arrayOf(removed.first.toString(), removed.second.toString()))
                    changed = true
                }
            }
        }
        return changed
    }

    /** Sessions and attempts from the other device that aren't here yet (each has the same id everywhere). */
    private fun mergeHistory(db: SqlDb, s: SyncSnapshot): Boolean {
        var changed = false
        val sessions = db.rawQuery("SELECT id, uid, finished_at, wins, losses FROM sessions WHERE uid IS NOT NULL", null)
            .mapRows { it.str("uid") to LocalSession(it.long("id"), it.longOrNull("finished_at"), it.int("wins") + it.int("losses")) }
            .toMap(HashMap())
        for (r in s.sessions) {
            val local = sessions[r.uid]
            if (local == null) {
                val id = db.insertOrThrow("sessions", null, ContentValues().apply {
                    put("uid", r.uid); put("kind", r.kind); put("lang", r.lang); put("started_at", r.startedAt)
                    put("finished_at", r.finishedAt); put("outcome", r.outcome); put("wins", r.wins); put("losses", r.losses)
                })
                sessions[r.uid] = LocalSession(id, r.finishedAt, r.wins + r.losses)
                changed = true
            } else if ((local.finishedAt == null && r.finishedAt != null) || r.wins + r.losses > local.answered) {
                db.update("sessions", ContentValues().apply {
                    put("finished_at", r.finishedAt); put("outcome", r.outcome); put("wins", r.wins); put("losses", r.losses)
                }, "id=?", arrayOf(local.id.toString()))
                changed = true
            }
        }
        val attempts = db.rawQuery("SELECT uid FROM attempts WHERE uid IS NOT NULL", null).mapRows { it.str("uid") }.toHashSet()
        val wordIds = HashMap<Pair<String, String>, Long>()
        for (a in s.attempts) {
            if (a.uid in attempts) continue
            val wordId = wordIds.getOrPut(a.lang to a.term) {
                db.rawQuery("SELECT id FROM words WHERE lang=? AND term=?", arrayOf(a.lang, a.term)).mapRows { it.getLong(0) }.firstOrNull() ?: -1L
            }
            db.insertOrThrow("attempts", null, ContentValues().apply {
                put("uid", a.uid)
                a.sessionUid?.let { sessions[it]?.id }?.let { put("session_id", it) }
                put("word_id", wordId); put("lang", a.lang); put("term", a.term); put("at", a.at)
                put("win", if (a.win) 1 else 0); put("failed_part", a.failedPart); put("heard", a.heard)
                put("meaning_answer", a.meaningAnswer); put("sentence_answer", a.sentenceAnswer)
                put("feedback", a.feedback); put("graded_by", a.gradedBy)
            })
            attempts += a.uid
            changed = true
        }
        return changed
    }

    private class LocalSession(val id: Long, val finishedAt: Long?, val answered: Int)

    private fun mergeStory(db: SqlDb, r: SyncStory, tombs: Map<Pair<String, String>, Long>): Boolean {
        val local = db.rawQuery("SELECT id, updated_at FROM stories WHERE uid=?", arrayOf(r.uid)).mapRows { it.long("id") to it.long("updated_at") }.firstOrNull()
        val cv = ContentValues().apply {
            put("title", r.title); put("title_en", r.titleEn); put("source", r.source); put("url", r.url); put("level", r.level)
            put("chars", r.chars); put("pages", r.pages); put("page", r.page); put("body", r.body)
            put("finished_at", r.finishedAt); put("created_at", r.createdAt); put("updated_at", r.updatedAt)
        }
        if (local == null) {
            val deletedAt = tombs[DELETED_STORY to r.uid]
            if (deletedAt != null && deletedAt >= r.updatedAt) return false
            db.insertOrThrow("stories", null, cv.apply { put("uid", r.uid) })
            return true
        }
        if (r.updatedAt <= local.second) return false
        db.update("stories", cv, "id=?", arrayOf(local.first.toString()))
        return true
    }

    private fun deleteStory(db: SqlDb, d: SyncDeletion): Boolean {
        val id = db.rawQuery("SELECT id FROM stories WHERE uid=? AND updated_at<?", arrayOf(d.key, d.at.toString())).mapRows { it.getLong(0) }.firstOrNull()
            ?: return false
        db.insertWithOnConflict("deletions", null, ContentValues().apply {
            put("kind", d.kind); put("key", d.key); put("at", d.at)
        }, SqlDb.CONFLICT_REPLACE)
        db.delete("stories", "id=?", arrayOf(id.toString()))
        return true
    }

    private fun strings(s: String): List<String> = runCatching { AppJson.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())

    private fun json(list: List<String>) = AppJson.encodeToString(list)

    private companion object {
        const val DELETED_STORY = StoryRepository.DELETED_STORY
    }
}
