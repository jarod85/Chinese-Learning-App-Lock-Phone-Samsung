package com.hanzilock.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.Srs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WordFilter(val label: String) { ALL("All"), DUE("Due"), WEAK("Weak"), NEW("New"), DISABLED("Off") }

/** All reads/writes of words, sessions and attempts. Call from a background thread. */
class WordRepository(private val helper: AppDatabase) {
    private val _changes = MutableStateFlow(0L)

    /** Increments after every write, so screens know to reload. */
    val changes: StateFlow<Long> = _changes

    private fun changed() = _changes.update { it + 1 }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---- words --------------------------------------------------------------------------

    fun list(filter: WordFilter = WordFilter.ALL, query: String = "", now: Long = System.currentTimeMillis()): List<Word> {
        val where = when (filter) {
            WordFilter.ALL -> "1=1"
            WordFilter.DUE -> "enabled=1 AND last_seen_at>0 AND due_at<=$now"
            WordFilter.WEAK -> "losses>0"
            WordFilter.NEW -> "last_seen_at=0"
            WordFilter.DISABLED -> "enabled=0"
        }
        val order = if (filter == WordFilter.WEAK) "(losses*1.0/(wins+losses)) DESC, losses DESC" else "id ASC"
        val words = db.rawQuery("SELECT * FROM words WHERE $where ORDER BY $order", null).mapRows { it.toWord() }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return words
        val key = Pinyin.searchKey(q)
        return words.filter { w ->
            w.hanzi.contains(q) || w.traditional?.contains(q) == true ||
                w.meanings.any { it.lowercase().contains(q) } ||
                (key.isNotEmpty() && Pinyin.searchKey(w.pinyin).contains(key))
        }
    }

    fun get(id: Long): Word? =
        db.rawQuery("SELECT * FROM words WHERE id=?", arrayOf(id.toString())).mapRows { it.toWord() }.firstOrNull()

    fun byHanzi(hanzi: String): Word? = byHanzi(db, hanzi)

    private fun byHanzi(d: SQLiteDatabase, hanzi: String): Word? =
        d.rawQuery("SELECT * FROM words WHERE hanzi=?", arrayOf(hanzi)).mapRows { it.toWord() }.firstOrNull()

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM words", null).mapRows { it.getInt(0) }.first()

    fun enabledCount(): Int = db.rawQuery("SELECT COUNT(*) FROM words WHERE enabled=1", null).mapRows { it.getInt(0) }.first()

    fun insert(draft: WordDraft, source: String, now: Long = System.currentTimeMillis()): Long {
        val id = insertWord(db, draft, source, now)
        changed()
        return id
    }

    private fun insertWord(d: SQLiteDatabase, draft: WordDraft, source: String, now: Long): Long =
        d.insertOrThrow("words", null, contentOf(draft).apply {
            put("source", source)
            put("created_at", now)
            put("updated_at", now)
        })

    fun update(id: Long, draft: WordDraft, now: Long = System.currentTimeMillis()) {
        db.update("words", contentOf(draft).apply {
            put("user_edited", 1)
            put("updated_at", now)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun addAltMeaning(id: Long, meaning: String) {
        val word = get(id) ?: return
        val clean = meaning.trim()
        if (clean.isEmpty() || word.acceptedMeanings.any { it.equals(clean, ignoreCase = true) }) return
        db.update("words", ContentValues().apply {
            put("alt_meanings", AppJson.encodeToString(word.altMeanings + clean))
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        db.update("words", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun delete(id: Long) {
        db.delete("words", "id=?", arrayOf(id.toString()))
        changed()
    }

    /**
     * Applies the registry bundled with the app: new words are added; words that came from the
     * registry and that you never edited in the app get the registry's updated content.
     * Progress is never touched. Returns how many words were added or updated.
     */
    fun upsertFromRegistry(entries: List<RegistryWord>, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        db.inTransaction {
            for (e in entries) {
                val draft = e.toDraft()
                val existing = byHanzi(this, draft.hanzi)
                if (existing == null) {
                    insertWord(this, draft, "registry", now); n++
                } else if (existing.source == "registry" && !existing.userEdited) {
                    update("words", contentOf(draft).apply { put("updated_at", now) }, "id=?", arrayOf(existing.id.toString())); n++
                }
            }
        }
        changed()
        return n
    }

    /** Adds words that aren't in the registry yet. Returns how many were added. */
    fun addMissing(drafts: List<WordDraft>, source: String, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        db.inTransaction {
            for (d in drafts) if (byHanzi(this, d.hanzi) == null) { insertWord(this, d, source, now); n++ }
        }
        changed()
        return n
    }

    /**
     * Words for a session: first those due for review (oldest first), then never-tested words
     * in registry order, then the ones due soonest.
     */
    fun pickForSession(now: Long, count: Int, exclude: Set<Long>): List<Word> {
        val picked = LinkedHashMap<Long, Word>()
        val limit = count + exclude.size + 5
        fun take(sql: String) {
            if (picked.size >= count) return
            db.rawQuery("$sql LIMIT $limit", null).mapRows { it.toWord() }.forEach { w ->
                if (picked.size < count && w.id !in exclude && w.id !in picked) picked[w.id] = w
            }
        }
        take("SELECT * FROM words WHERE enabled=1 AND last_seen_at>0 AND due_at<=$now ORDER BY due_at ASC")
        take("SELECT * FROM words WHERE enabled=1 AND last_seen_at=0 ORDER BY id ASC")
        take("SELECT * FROM words WHERE enabled=1 AND last_seen_at>0 ORDER BY due_at ASC")
        return picked.values.toList()
    }

    // ---- sessions & attempts ----------------------------------------------------------------

    fun createSession(kind: String, now: Long): Long {
        val id = db.insertOrThrow("sessions", null, ContentValues().apply {
            put("kind", kind)
            put("started_at", now)
        })
        changed()
        return id
    }

    fun finishSession(id: Long, outcome: String, now: Long) {
        db.update("sessions", ContentValues().apply {
            put("finished_at", now)
            put("outcome", outcome)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Records the outcome of one word: updates its review schedule and logs the attempt. */
    fun recordAttempt(
        word: Word,
        sessionId: Long?,
        win: Boolean,
        failedPart: QuizPart?,
        heard: String?,
        meaningAnswer: String?,
        sentenceAnswer: String?,
        feedback: String?,
        gradedBy: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val (box, due) = Srs.schedule(word.box, win, now)
        db.inTransaction {
            execSQL(
                "UPDATE words SET box=?, due_at=?, wins=wins+?, losses=losses+?, last_seen_at=? WHERE id=?",
                arrayOf<Any>(box, due, if (win) 1 else 0, if (win) 0 else 1, now, word.id),
            )
            insertOrThrow("attempts", null, ContentValues().apply {
                if (sessionId != null) put("session_id", sessionId)
                put("word_id", word.id)
                put("hanzi", word.hanzi)
                put("at", now)
                put("win", if (win) 1 else 0)
                put("failed_part", failedPart?.name)
                put("heard", heard)
                put("meaning_answer", meaningAnswer)
                put("sentence_answer", sentenceAnswer)
                put("feedback", feedback)
                put("graded_by", gradedBy)
            })
            if (sessionId != null) {
                execSQL(
                    "UPDATE sessions SET wins=wins+?, losses=losses+? WHERE id=?",
                    arrayOf<Any>(if (win) 1 else 0, if (win) 0 else 1, sessionId),
                )
            }
        }
        changed()
    }

    fun attemptsFor(wordId: Long, limit: Int = 30): List<Attempt> =
        db.rawQuery("SELECT * FROM attempts WHERE word_id=? ORDER BY at DESC LIMIT $limit", arrayOf(wordId.toString()))
            .mapRows { it.toAttempt() }

    fun recentLosses(limit: Int = 50): List<Attempt> =
        db.rawQuery("SELECT * FROM attempts WHERE win=0 ORDER BY at DESC LIMIT $limit", null).mapRows { it.toAttempt() }

    fun weakest(limit: Int = 10): List<Word> =
        db.rawQuery(
            "SELECT * FROM words WHERE losses>0 ORDER BY (losses*1.0/(wins+losses)) DESC, losses DESC LIMIT $limit",
            null,
        ).mapRows { it.toWord() }

    fun dailyStats(days: Int, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): List<DayStat> {
        val first = today.minusDays(days - 1L)
        val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets = LinkedHashMap<Long, IntArray>()
        for (i in 0 until days) buckets[first.plusDays(i.toLong()).toEpochDay()] = IntArray(2)
        db.rawQuery("SELECT at, win FROM attempts WHERE at>=?", arrayOf(from.toString())).mapRows { it.getLong(0) to it.getInt(1) }
            .forEach { (at, win) ->
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
                buckets[day]?.let { if (win == 1) it[0]++ else it[1]++ }
            }
        return buckets.map { (day, wl) -> DayStat(day, wl[0], wl[1]) }
    }

    fun totals(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Totals {
        fun one(sql: String): Int = db.rawQuery(sql, null).mapRows { it.getInt(0) }.first()
        val days = db.rawQuery("SELECT at FROM attempts ORDER BY at DESC", null)
            .mapRows { Instant.ofEpochMilli(it.getLong(0)).atZone(zone).toLocalDate() }.toSet()
        var streak = 0
        var day = if (today in days) today else today.minusDays(1)
        while (day in days) { streak++; day = day.minusDays(1) }
        return Totals(
            words = one("SELECT COUNT(*) FROM words"),
            seen = one("SELECT COUNT(*) FROM words WHERE last_seen_at>0"),
            wins = one("SELECT COUNT(*) FROM attempts WHERE win=1"),
            losses = one("SELECT COUNT(*) FROM attempts WHERE win=0"),
            sessionsCompleted = one("SELECT COUNT(*) FROM sessions WHERE outcome='completed'"),
            sessionsSkipped = one("SELECT COUNT(*) FROM sessions WHERE outcome='pin_skip'"),
            streakDays = streak,
        )
    }

    // ---- backup ------------------------------------------------------------------------------

    fun backup(registryVersion: Int, now: Long = System.currentTimeMillis()): Backup = Backup(
        exportedAt = now,
        registryVersion = registryVersion,
        words = db.rawQuery("SELECT * FROM words ORDER BY id", null).mapRows { it.toWord() }.map { w ->
            BackupWord(
                w.hanzi, w.traditional, w.pinyin, w.meanings, w.altMeanings, w.examples, w.tags, w.source,
                w.userEdited, w.enabled, w.box, w.dueAt, w.wins, w.losses, w.lastSeenAt, w.createdAt,
            )
        },
        sessions = db.rawQuery("SELECT * FROM sessions ORDER BY id", null).mapRows {
            BackupSession(it.long("id"), it.str("kind"), it.long("started_at"), it.longOrNull("finished_at"),
                it.strOrNull("outcome"), it.int("wins"), it.int("losses"))
        },
        attempts = db.rawQuery("SELECT * FROM attempts ORDER BY id", null).mapRows { it.toAttempt() }.map { a ->
            BackupAttempt(a.sessionId, a.hanzi, a.at, a.win, a.failedPart?.name, a.heard, a.meaningAnswer,
                a.sentenceAnswer, a.feedback, a.gradedBy)
        },
    )

    /** Replaces everything with the backup's contents. */
    fun restore(backup: Backup) {
        db.inTransaction {
            delete("attempts", null, null)
            delete("sessions", null, null)
            delete("words", null, null)
            val ids = HashMap<String, Long>()
            for (w in backup.words) {
                ids[w.hanzi] = insertOrThrow("words", null, ContentValues().apply {
                    put("hanzi", w.hanzi); put("traditional", w.traditional); put("pinyin", w.pinyin)
                    put("meanings", AppJson.encodeToString(w.meanings))
                    put("alt_meanings", AppJson.encodeToString(w.altMeanings))
                    put("examples", AppJson.encodeToString(w.examples))
                    put("tags", AppJson.encodeToString(w.tags))
                    put("source", w.source); put("user_edited", if (w.userEdited) 1 else 0)
                    put("enabled", if (w.enabled) 1 else 0); put("box", w.box); put("due_at", w.dueAt)
                    put("wins", w.wins); put("losses", w.losses); put("last_seen_at", w.lastSeenAt)
                    put("created_at", w.createdAt); put("updated_at", w.createdAt)
                })
            }
            for (s in backup.sessions) {
                insertOrThrow("sessions", null, ContentValues().apply {
                    put("id", s.id); put("kind", s.kind); put("started_at", s.startedAt)
                    put("finished_at", s.finishedAt); put("outcome", s.outcome); put("wins", s.wins); put("losses", s.losses)
                })
            }
            for (a in backup.attempts) {
                insertOrThrow("attempts", null, ContentValues().apply {
                    if (a.sessionId != null) put("session_id", a.sessionId)
                    put("word_id", ids[a.hanzi] ?: -1L); put("hanzi", a.hanzi); put("at", a.at)
                    put("win", if (a.win) 1 else 0); put("failed_part", a.failedPart); put("heard", a.heard)
                    put("meaning_answer", a.meaningAnswer); put("sentence_answer", a.sentenceAnswer)
                    put("feedback", a.feedback); put("graded_by", a.gradedBy)
                })
            }
        }
        changed()
    }

    // ---- mapping -------------------------------------------------------------------------------

    private fun contentOf(d: WordDraft) = ContentValues().apply {
        put("hanzi", d.hanzi.trim())
        put("traditional", d.traditional?.trim()?.takeIf { it.isNotEmpty() && it != d.hanzi.trim() })
        put("pinyin", d.pinyin.trim())
        put("meanings", AppJson.encodeToString(d.meanings.map { it.trim() }.filter { it.isNotEmpty() }))
        put("examples", AppJson.encodeToString(d.examples.filter { it.zh.isNotBlank() }))
        put("tags", AppJson.encodeToString(d.tags.map { it.trim() }.filter { it.isNotEmpty() }))
    }

    private fun Cursor.toWord() = Word(
        id = long("id"),
        hanzi = str("hanzi"),
        traditional = strOrNull("traditional"),
        pinyin = str("pinyin"),
        meanings = decodeStrings(str("meanings")),
        altMeanings = decodeStrings(str("alt_meanings")),
        examples = runCatching { AppJson.decodeFromString<List<Example>>(str("examples")) }.getOrDefault(emptyList()),
        tags = decodeStrings(str("tags")),
        source = str("source"),
        userEdited = int("user_edited") != 0,
        enabled = int("enabled") != 0,
        box = int("box"),
        dueAt = long("due_at"),
        wins = int("wins"),
        losses = int("losses"),
        lastSeenAt = long("last_seen_at"),
        createdAt = long("created_at"),
    )

    private fun Cursor.toAttempt() = Attempt(
        id = long("id"),
        sessionId = longOrNull("session_id"),
        wordId = long("word_id"),
        hanzi = str("hanzi"),
        at = long("at"),
        win = int("win") != 0,
        failedPart = strOrNull("failed_part")?.let { name -> QuizPart.entries.firstOrNull { it.name == name } },
        heard = strOrNull("heard"),
        meaningAnswer = strOrNull("meaning_answer"),
        sentenceAnswer = strOrNull("sentence_answer"),
        feedback = strOrNull("feedback"),
        gradedBy = strOrNull("graded_by"),
    )

    private fun decodeStrings(s: String): List<String> =
        runCatching { AppJson.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
}

fun RegistryWord.toDraft() = WordDraft(
    hanzi = hanzi.trim(),
    traditional = traditional,
    pinyin = Pinyin.normalizeToMarked(pinyin).ifEmpty { pinyin.trim() },
    meanings = meanings,
    examples = examples,
    tags = tags,
)

fun Word.toRegistryWord() = RegistryWord(hanzi, traditional, pinyin, meanings, examples, tags)

@Serializable
data class BackupWord(
    val hanzi: String,
    val traditional: String? = null,
    val pinyin: String,
    val meanings: List<String>,
    val altMeanings: List<String> = emptyList(),
    val examples: List<Example> = emptyList(),
    val tags: List<String> = emptyList(),
    val source: String = "user",
    val userEdited: Boolean = false,
    val enabled: Boolean = true,
    val box: Int = 0,
    val dueAt: Long = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val lastSeenAt: Long = 0,
    val createdAt: Long = 0,
)

@Serializable
data class BackupSession(
    val id: Long,
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcome: String? = null,
    val wins: Int = 0,
    val losses: Int = 0,
)

@Serializable
data class BackupAttempt(
    val sessionId: Long? = null,
    val hanzi: String,
    val at: Long,
    val win: Boolean,
    val failedPart: String? = null,
    val heard: String? = null,
    val meaningAnswer: String? = null,
    val sentenceAnswer: String? = null,
    val feedback: String? = null,
    val gradedBy: String? = null,
)

@Serializable
data class Backup(
    val format: String = "hanzilock-backup",
    val version: Int = 1,
    val exportedAt: Long,
    val registryVersion: Int = 0,
    val words: List<BackupWord>,
    val sessions: List<BackupSession> = emptyList(),
    val attempts: List<BackupAttempt> = emptyList(),
)
