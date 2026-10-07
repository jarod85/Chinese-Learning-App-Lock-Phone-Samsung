@file:OptIn(ExperimentalSerializationApi::class)

package com.hanzilock.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.Srs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNames
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WordFilter(val label: String) {
    ALL("All"), DUE("Due"), WEAK("Weak"), NEW("New"), INCOMPLETE("Needs details"), DISABLED("Off")
}

/** Which words a list shows: those in switched-on sets, one set, or everything in the language. */
sealed interface WordScope {
    data object Practising : WordScope
    data class InSet(val setId: Long) : WordScope
    data object Everything : WordScope
}

/** All reads/writes of words, sets, sessions and attempts. Call from a background thread. */
class WordRepository(private val helper: AppDatabase) {
    private val _changes = MutableStateFlow(0L)

    /** Increments after every write, so screens know to reload. */
    val changes: StateFlow<Long> = _changes

    private fun changed() = _changes.update { it + 1 }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---- words --------------------------------------------------------------------------------

    fun list(
        lang: String,
        filter: WordFilter = WordFilter.ALL,
        query: String = "",
        scope: WordScope = WordScope.Everything,
        now: Long = System.currentTimeMillis(),
        limit: Int = 1500,
    ): List<Word> {
        val where = StringBuilder("w.lang=?")
        val args = arrayListOf(lang)
        when (scope) {
            WordScope.Practising -> where.append(" AND EXISTS (SELECT 1 FROM set_words sw JOIN sets s ON s.id=sw.set_id WHERE sw.word_id=w.id AND s.enabled=1)")
            is WordScope.InSet -> { where.append(" AND w.id IN (SELECT word_id FROM set_words WHERE set_id=?)"); args.add(scope.setId.toString()) }
            WordScope.Everything -> Unit
        }
        when (filter) {
            WordFilter.ALL -> Unit
            WordFilter.DUE -> where.append(" AND w.enabled=1 AND w.last_seen_at>0 AND w.due_at<=$now")
            WordFilter.WEAK -> where.append(" AND w.losses>0")
            WordFilter.NEW -> where.append(" AND w.last_seen_at=0")
            WordFilter.INCOMPLETE -> where.append(" AND w.meanings='[]'")
            WordFilter.DISABLED -> where.append(" AND w.enabled=0")
        }
        val q = query.trim().lowercase()
        val pinyinKey = if (lang == "zh" && q.isNotEmpty() && q.all { it.isLetter() && it.code < 0x2E80 || it.isDigit() || it == ' ' }) Pinyin.searchKey(q) else ""
        if (q.isNotEmpty() && pinyinKey.isEmpty()) {
            where.append(" AND (w.term LIKE ? OR w.traditional LIKE ? OR w.reading LIKE ? OR w.meanings LIKE ? OR w.forms LIKE ?)")
            repeat(5) { args.add("%$q%") }
        }
        val order = when (filter) {
            WordFilter.WEAK -> "(w.losses*1.0/(w.wins+w.losses)) DESC, w.losses DESC"
            WordFilter.DUE -> "w.due_at ASC"
            else -> "w.id ASC"
        }
        val sql = "SELECT w.* FROM words w WHERE $where ORDER BY $order" + if (pinyinKey.isEmpty()) " LIMIT $limit" else ""
        val words = db.rawQuery(sql, args.toTypedArray()).mapRows { it.toWord() }
        if (pinyinKey.isEmpty()) return words
        return words.filter { w ->
            Pinyin.searchKey(w.reading).contains(pinyinKey) || w.meanings.any { it.lowercase().contains(q) }
        }.take(limit)
    }

    fun get(id: Long): Word? =
        db.rawQuery("SELECT * FROM words WHERE id=?", arrayOf(id.toString())).mapRows { it.toWord() }.firstOrNull()

    fun find(lang: String, term: String): Word? = find(db, lang, term)

    private fun find(d: SQLiteDatabase, lang: String, term: String): Word? =
        d.rawQuery("SELECT * FROM words WHERE lang=? AND term=?", arrayOf(lang, term)).mapRows { it.toWord() }.firstOrNull()

    /** Words in your own sets (imports, "My words") with an id above [afterId], oldest first. */
    fun customSetWordsAfter(afterId: Long): List<Word> = db.rawQuery(
        "SELECT w.* FROM words w WHERE w.id>? AND " +
            "EXISTS (SELECT 1 FROM set_words sw JOIN sets s ON s.id=sw.set_id WHERE sw.word_id=w.id AND s.custom=1) ORDER BY w.id",
        arrayOf(afterId.toString()),
    ).mapRows { it.toWord() }

    fun countPractising(lang: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM words w WHERE w.lang=? AND w.enabled=1 AND w.meanings<>'[]' AND " +
            "EXISTS (SELECT 1 FROM set_words sw JOIN sets s ON s.id=sw.set_id WHERE sw.word_id=w.id AND s.enabled=1)",
        arrayOf(lang),
    ).mapRows { it.getInt(0) }.first()

    /** Adds a word (or returns the existing one with the same spelling) and puts it in [setId]. */
    fun insert(draft: WordDraft, source: String, setId: Long?, now: Long = System.currentTimeMillis()): Long {
        val id = db.inTransaction {
            val id = find(this, draft.lang, draft.term.trim())?.id ?: insertWord(this, draft, source, now)
            if (setId != null) addMembership(this, setId, id)
            id
        }
        changed()
        return id
    }

    private fun insertWord(d: SQLiteDatabase, draft: WordDraft, source: String, now: Long): Long =
        d.insertOrThrow("words", null, contentOf(draft).apply {
            put("lang", draft.lang)
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

    /** Fills in details found later (e.g. by Claude) without marking the word as edited by you. */
    fun complete(id: Long, reading: String?, meanings: List<String>, examples: List<Example>) {
        db.update("words", ContentValues().apply {
            if (!reading.isNullOrBlank()) put("reading", reading)
            if (meanings.isNotEmpty()) put("meanings", AppJson.encodeToString(meanings))
            if (examples.isNotEmpty()) put("examples", AppJson.encodeToString(examples))
            put("updated_at", System.currentTimeMillis())
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
        db.inTransaction {
            delete("set_words", "word_id=?", arrayOf(id.toString()))
            delete("words", "id=?", arrayOf(id.toString()))
        }
        changed()
    }

    // ---- sets -----------------------------------------------------------------------------------

    fun sets(lang: String): List<WordSet> = db.rawQuery(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM set_words sw WHERE sw.set_id=s.id) AS word_count,
            (SELECT COUNT(*) FROM set_words sw JOIN words w ON w.id=sw.word_id WHERE sw.set_id=s.id AND w.last_seen_at>0) AS seen_count
        FROM sets s WHERE s.lang=? ORDER BY s.custom, s.sort, s.name
        """.trimIndent(),
        arrayOf(lang),
    ).mapRows { it.toSet() }

    fun set(id: Long): WordSet? = setsWhere("s.id=?", id.toString()).firstOrNull()

    fun setByKey(key: String): WordSet? = setsWhere("s.key=?", key).firstOrNull()

    private fun setsWhere(where: String, arg: String): List<WordSet> = db.rawQuery(
        "SELECT s.*, (SELECT COUNT(*) FROM set_words sw WHERE sw.set_id=s.id) AS word_count, 0 AS seen_count FROM sets s WHERE $where",
        arrayOf(arg),
    ).mapRows { it.toSet() }

    fun setsOf(wordId: Long): List<WordSet> = db.rawQuery(
        "SELECT s.*, 0 AS word_count, 0 AS seen_count FROM sets s JOIN set_words sw ON sw.set_id=s.id WHERE sw.word_id=? ORDER BY s.sort",
        arrayOf(wordId.toString()),
    ).mapRows { it.toSet() }

    fun setSetEnabled(setId: Long, enabled: Boolean) {
        db.update("sets", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(setId.toString()))
        changed()
    }

    fun renameSet(setId: Long, name: String) {
        db.update("sets", ContentValues().apply { put("name", name.trim()) }, "id=?", arrayOf(setId.toString()))
        changed()
    }

    /** Removes a set. Its words stay if they belong to another set; otherwise they are deleted too. */
    fun deleteSet(setId: Long) {
        db.inTransaction {
            val members = rawQuery("SELECT word_id FROM set_words WHERE set_id=?", arrayOf(setId.toString())).mapRows { it.getLong(0) }
            delete("set_words", "set_id=?", arrayOf(setId.toString()))
            delete("sets", "id=?", arrayOf(setId.toString()))
            for (id in members) {
                val stillUsed = rawQuery("SELECT 1 FROM set_words WHERE word_id=? LIMIT 1", arrayOf(id.toString())).mapRows { true }.isNotEmpty()
                if (!stillUsed) delete("words", "id=?", arrayOf(id.toString()))
            }
        }
        changed()
    }

    fun createSet(lang: String, name: String, custom: Boolean = true, enabled: Boolean = true, now: Long = System.currentTimeMillis()): Long {
        val id = insertSet(db, "custom.$lang.$now", lang, name.trim().ifEmpty { "My set" }, "Custom", 100, bundled = false, custom = custom, enabled = enabled, now = now)
        changed()
        return id
    }

    /** The "My words" set of a language (created on first use) - words you add by hand go here. */
    fun myWordsSet(lang: String): Long {
        setByKey("$lang.mine")?.let { return it.id }
        val id = insertSet(db, "$lang.mine", lang, "My words", "Custom", 99, bundled = false, custom = true, enabled = true, now = System.currentTimeMillis())
        changed()
        return id
    }

    fun addToSet(setId: Long, wordId: Long) {
        addMembership(db, setId, wordId)
        changed()
    }

    fun removeFromSet(setId: Long, wordId: Long) {
        db.delete("set_words", "set_id=? AND word_id=?", arrayOf(setId.toString(), wordId.toString()))
        changed()
    }

    fun enabledSetCount(lang: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM sets WHERE lang=? AND enabled=1", arrayOf(lang)).mapRows { it.getInt(0) }.first()

    /** When you switch to a language with nothing switched on, its first (easiest) set is turned on. */
    fun enableFirstSetIfNone(lang: String) {
        if (enabledSetCount(lang) > 0) return
        val first = sets(lang).firstOrNull { !it.custom } ?: sets(lang).firstOrNull() ?: return
        setSetEnabled(first.id, true)
    }

    private fun insertSet(
        d: SQLiteDatabase, key: String, lang: String, name: String, level: String?, sort: Int,
        bundled: Boolean, custom: Boolean, enabled: Boolean, now: Long, rev: String? = null,
    ): Long = d.insertOrThrow("sets", null, ContentValues().apply {
        put("key", key); put("lang", lang); put("name", name); put("level", level); put("sort", sort)
        put("bundled", if (bundled) 1 else 0); put("custom", if (custom) 1 else 0); put("enabled", if (enabled) 1 else 0)
        put("rev", rev); put("created_at", now)
    })

    private fun addMembership(d: SQLiteDatabase, setId: Long, wordId: Long) {
        d.insertWithOnConflict("set_words", null, ContentValues().apply {
            put("set_id", setId)
            put("word_id", wordId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Installs or updates a set from a set file: new words are added, words you never edited get the
     * file's updated content, and words dropped from a bundled set leave it. Progress is never touched.
     * A word that already exists in another set is shared, not duplicated.
     */
    fun applySet(info: SetInfo, file: SetFile, bundled: Boolean, now: Long = System.currentTimeMillis()): Int {
        var touched = 0
        db.inTransaction {
            val existingSet = rawQuery("SELECT id FROM sets WHERE key=?", arrayOf(info.key)).mapRows { it.getLong(0) }.firstOrNull()
            val setId = if (existingSet == null) {
                insertSet(this, info.key, info.lang, info.name, info.level, info.sort, bundled, info.custom, info.enabled, now)
            } else {
                update("sets", ContentValues().apply {
                    put("name", info.name); put("level", info.level); put("sort", info.sort)
                }, "id=?", arrayOf(existingSet.toString()))
                existingSet
            }
            val members = HashSet<Long>()
            for (w in file.words) {
                val term = w.term.trim()
                if (term.isEmpty()) continue
                val current = find(this, info.lang, term)
                val id = if (current == null) {
                    insertWord(this, w.toDraft(info.lang), if (info.custom) "import" else "set", now)
                } else {
                    if (!current.userEdited) update("words", mergedContent(current, w), "id=?", arrayOf(current.id.toString()))
                    current.id
                }
                addMembership(this, setId, id)
                members.add(id)
                touched++
            }
            if (bundled) {
                rawQuery("SELECT word_id FROM set_words WHERE set_id=?", arrayOf(setId.toString())).mapRows { it.getLong(0) }
                    .filter { it !in members }
                    .forEach { delete("set_words", "set_id=? AND word_id=?", arrayOf(setId.toString(), it.toString())) }
            }
            update("sets", ContentValues().apply { put("rev", info.rev ?: file.rev) }, "id=?", arrayOf(setId.toString()))
        }
        changed()
        return touched
    }

    /** New content for a word from a set file, keeping whatever the file doesn't provide. */
    private fun mergedContent(current: Word, w: SetWord) = ContentValues().apply {
        w.traditional?.let { put("traditional", it) }
        if (!w.reading.isNullOrBlank()) put("reading", w.reading.trim())
        if (w.meanings.isNotEmpty()) put("meanings", AppJson.encodeToString(w.meanings))
        if (w.examples.isNotEmpty()) {
            // Keep sentences Claude added with their reading if the file has none with a reading.
            val withReading = current.examples.filter { ex -> !ex.reading.isNullOrBlank() && w.examples.none { it.text == ex.text } }
            val keep = if (w.examples.any { !it.reading.isNullOrBlank() }) emptyList() else withReading
            put("examples", AppJson.encodeToString(keep + w.examples))
        }
        if (w.forms.isNotEmpty()) put("forms", AppJson.encodeToString(w.forms))
        put("tags", AppJson.encodeToString((current.tags + w.tags).distinct()))
        put("updated_at", System.currentTimeMillis())
    }

    /**
     * Words for a session from the language's switched-on sets: first those due for review
     * (oldest first), then new words from the easiest set onwards, then the ones due soonest.
     * Words still missing a meaning are left out.
     */
    fun pickForSession(lang: String, now: Long, count: Int, exclude: Set<Long>): List<Word> {
        val picked = LinkedHashMap<Long, Word>()
        val limit = count + exclude.size + 5
        val base = "SELECT w.* FROM words w WHERE w.lang=? AND w.enabled=1 AND w.meanings<>'[]' AND " +
            "EXISTS (SELECT 1 FROM set_words sw JOIN sets s ON s.id=sw.set_id WHERE sw.word_id=w.id AND s.enabled=1)"
        val firstSet = "(SELECT MIN(s.sort) FROM set_words sw JOIN sets s ON s.id=sw.set_id WHERE sw.word_id=w.id AND s.enabled=1)"
        fun take(sql: String) {
            if (picked.size >= count) return
            db.rawQuery("$sql LIMIT $limit", arrayOf(lang)).mapRows { it.toWord() }.forEach { w ->
                if (picked.size < count && w.id !in exclude && w.id !in picked) picked[w.id] = w
            }
        }
        take("$base AND w.last_seen_at>0 AND w.due_at<=$now ORDER BY w.due_at ASC")
        take("$base AND w.last_seen_at=0 ORDER BY $firstSet, w.id ASC")
        take("$base AND w.last_seen_at>0 ORDER BY w.due_at ASC")
        return picked.values.toList()
    }

    // ---- sessions & attempts ----------------------------------------------------------------------

    fun createSession(kind: String, lang: String, now: Long): Long {
        val id = db.insertOrThrow("sessions", null, ContentValues().apply {
            put("kind", kind)
            put("lang", lang)
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
                put("lang", word.lang)
                put("term", word.term)
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

    fun recentLosses(lang: String, limit: Int = 50): List<Attempt> =
        db.rawQuery("SELECT * FROM attempts WHERE win=0 AND lang=? ORDER BY at DESC LIMIT $limit", arrayOf(lang)).mapRows { it.toAttempt() }

    fun weakest(lang: String, limit: Int = 10): List<Word> =
        db.rawQuery(
            "SELECT * FROM words WHERE lang=? AND losses>0 ORDER BY (losses*1.0/(wins+losses)) DESC, losses DESC LIMIT $limit",
            arrayOf(lang),
        ).mapRows { it.toWord() }

    fun dailyStats(lang: String, days: Int, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): List<DayStat> {
        val first = today.minusDays(days - 1L)
        val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets = LinkedHashMap<Long, IntArray>()
        for (i in 0 until days) buckets[first.plusDays(i.toLong()).toEpochDay()] = IntArray(2)
        db.rawQuery("SELECT at, win FROM attempts WHERE lang=? AND at>=?", arrayOf(lang, from.toString()))
            .mapRows { it.getLong(0) to it.getInt(1) }
            .forEach { (at, win) ->
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
                buckets[day]?.let { if (win == 1) it[0]++ else it[1]++ }
            }
        return buckets.map { (day, wl) -> DayStat(day, wl[0], wl[1]) }
    }

    fun totals(lang: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Totals {
        fun one(sql: String): Int = db.rawQuery(sql, arrayOf(lang)).mapRows { it.getInt(0) }.first()
        val days = db.rawQuery("SELECT at FROM attempts WHERE lang=? ORDER BY at DESC", arrayOf(lang))
            .mapRows { Instant.ofEpochMilli(it.getLong(0)).atZone(zone).toLocalDate() }.toSet()
        var streak = 0
        var day = if (today in days) today else today.minusDays(1)
        while (day in days) { streak++; day = day.minusDays(1) }
        return Totals(
            words = countPractising(lang),
            seen = one("SELECT COUNT(*) FROM words WHERE lang=? AND last_seen_at>0"),
            wins = one("SELECT COUNT(*) FROM attempts WHERE lang=? AND win=1"),
            losses = one("SELECT COUNT(*) FROM attempts WHERE lang=? AND win=0"),
            sessionsCompleted = one("SELECT COUNT(*) FROM sessions WHERE lang=? AND outcome='completed'"),
            sessionsSkipped = one("SELECT COUNT(*) FROM sessions WHERE lang=? AND outcome='pin_skip'"),
            streakDays = streak,
        )
    }

    // ---- backup ---------------------------------------------------------------------------------

    fun backup(now: Long = System.currentTimeMillis()): Backup {
        val words = db.rawQuery("SELECT * FROM words ORDER BY id", null).mapRows { it.toWord() }
        val termOf = words.associate { it.id to it.term }
        val sets = db.rawQuery("SELECT s.*, 0 AS word_count, 0 AS seen_count FROM sets s ORDER BY s.id", null).mapRows { it.toSet() }
        return Backup(
            version = 2,
            exportedAt = now,
            words = words.map { w ->
                BackupWord(
                    w.lang, w.term, w.traditional, w.reading, w.meanings, w.altMeanings, w.examples, w.forms, w.tags,
                    w.source, w.userEdited, w.enabled, w.box, w.dueAt, w.wins, w.losses, w.lastSeenAt, w.createdAt,
                )
            },
            sets = sets.map { s ->
                val members = db.rawQuery("SELECT word_id FROM set_words WHERE set_id=?", arrayOf(s.id.toString()))
                    .mapRows { termOf[it.getLong(0)] }.filterNotNull()
                BackupSet(s.key, s.lang, s.name, s.level, s.sort, s.bundled, s.custom, s.enabled, s.rev, members)
            },
            sessions = db.rawQuery("SELECT * FROM sessions ORDER BY id", null).mapRows {
                BackupSession(it.long("id"), it.str("kind"), it.str("lang"), it.long("started_at"), it.longOrNull("finished_at"),
                    it.strOrNull("outcome"), it.int("wins"), it.int("losses"))
            },
            attempts = db.rawQuery("SELECT * FROM attempts ORDER BY id", null).mapRows { it.toAttempt() }.map { a ->
                BackupAttempt(a.sessionId, a.lang, a.term, a.at, a.win, a.failedPart?.name, a.heard, a.meaningAnswer,
                    a.sentenceAnswer, a.feedback, a.gradedBy)
            },
        )
    }

    /** Replaces everything with the backup's contents (version 1 backups become Mandarin words in HSK 1 / My words). */
    fun restore(backup: Backup) {
        db.inTransaction {
            delete("attempts", null, null)
            delete("sessions", null, null)
            delete("set_words", null, null)
            delete("sets", null, null)
            delete("words", null, null)
            val ids = HashMap<Pair<String, String>, Long>()
            for (w in backup.words) {
                ids[w.lang to w.term] = insertOrThrow("words", null, ContentValues().apply {
                    put("lang", w.lang); put("term", w.term); put("traditional", w.traditional); put("reading", w.reading)
                    put("meanings", AppJson.encodeToString(w.meanings))
                    put("alt_meanings", AppJson.encodeToString(w.altMeanings))
                    put("examples", AppJson.encodeToString(w.examples))
                    put("forms", AppJson.encodeToString(w.forms))
                    put("tags", AppJson.encodeToString(w.tags))
                    put("source", w.source); put("user_edited", if (w.userEdited) 1 else 0)
                    put("enabled", if (w.enabled) 1 else 0); put("box", w.box); put("due_at", w.dueAt)
                    put("wins", w.wins); put("losses", w.losses); put("last_seen_at", w.lastSeenAt)
                    put("created_at", w.createdAt); put("updated_at", w.createdAt)
                })
            }
            val sets = backup.sets.ifEmpty {
                listOf(
                    BackupSet("zh.hsk1", "zh", "HSK 1", "Beginner", 1, bundled = true, custom = false, enabled = true, rev = null,
                        members = backup.words.filter { it.source == "registry" }.map { it.term }),
                    BackupSet("zh.mine", "zh", "My words", "Custom", 100, bundled = false, custom = true, enabled = true, rev = null,
                        members = backup.words.filter { it.source != "registry" }.map { it.term }),
                )
            }
            for (s in sets) {
                val setId = insertSet(this, s.key, s.lang, s.name, s.level, s.sort, s.bundled, s.custom, s.enabled, backup.exportedAt, s.rev)
                s.members.mapNotNull { ids[s.lang to it] }.forEach { addMembership(this, setId, it) }
            }
            for (s in backup.sessions) {
                insertOrThrow("sessions", null, ContentValues().apply {
                    put("id", s.id); put("kind", s.kind); put("lang", s.lang); put("started_at", s.startedAt)
                    put("finished_at", s.finishedAt); put("outcome", s.outcome); put("wins", s.wins); put("losses", s.losses)
                })
            }
            for (a in backup.attempts) {
                insertOrThrow("attempts", null, ContentValues().apply {
                    if (a.sessionId != null) put("session_id", a.sessionId)
                    put("word_id", ids[a.lang to a.term] ?: -1L); put("lang", a.lang); put("term", a.term); put("at", a.at)
                    put("win", if (a.win) 1 else 0); put("failed_part", a.failedPart); put("heard", a.heard)
                    put("meaning_answer", a.meaningAnswer); put("sentence_answer", a.sentenceAnswer)
                    put("feedback", a.feedback); put("graded_by", a.gradedBy)
                })
            }
        }
        changed()
    }

    // ---- mapping ----------------------------------------------------------------------------------

    private fun contentOf(d: WordDraft) = ContentValues().apply {
        put("term", d.term.trim())
        put("traditional", d.traditional?.trim()?.takeIf { it.isNotEmpty() && it != d.term.trim() })
        put("reading", d.reading.trim())
        put("meanings", AppJson.encodeToString(d.meanings.map { it.trim() }.filter { it.isNotEmpty() }))
        put("examples", AppJson.encodeToString(d.examples.filter { it.text.isNotBlank() }))
        put("forms", AppJson.encodeToString(d.forms.map { it.trim() }.filter { it.isNotEmpty() }))
        put("tags", AppJson.encodeToString(d.tags.map { it.trim() }.filter { it.isNotEmpty() }))
    }

    private fun Cursor.toWord() = Word(
        id = long("id"),
        lang = str("lang"),
        term = str("term"),
        traditional = strOrNull("traditional"),
        reading = str("reading"),
        meanings = decodeStrings(str("meanings")),
        altMeanings = decodeStrings(str("alt_meanings")),
        examples = runCatching { AppJson.decodeFromString<List<Example>>(str("examples")) }.getOrDefault(emptyList()),
        forms = decodeStrings(str("forms")),
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

    private fun Cursor.toSet() = WordSet(
        id = long("id"),
        key = str("key"),
        lang = str("lang"),
        name = str("name"),
        level = strOrNull("level"),
        sort = int("sort"),
        bundled = int("bundled") != 0,
        custom = int("custom") != 0,
        enabled = int("enabled") != 0,
        rev = strOrNull("rev"),
        wordCount = int("word_count"),
        seenCount = int("seen_count"),
    )

    private fun Cursor.toAttempt() = Attempt(
        id = long("id"),
        sessionId = longOrNull("session_id"),
        wordId = long("word_id"),
        lang = str("lang"),
        term = str("term"),
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

fun SetWord.toDraft(lang: String) = WordDraft(
    lang = lang,
    term = term.trim(),
    traditional = traditional,
    reading = when {
        reading.isNullOrBlank() -> ""
        lang == "zh" -> Pinyin.normalizeToMarked(reading).ifEmpty { reading.trim() }
        else -> reading.trim()
    },
    meanings = meanings,
    examples = examples,
    forms = forms,
    tags = tags,
)

fun Word.toSetWord() = SetWord(term, traditional, reading.ifBlank { null }, meanings, examples, forms, tags)

@Serializable
data class BackupWord(
    val lang: String = "zh",
    @JsonNames("hanzi") val term: String,
    val traditional: String? = null,
    @JsonNames("pinyin") val reading: String = "",
    val meanings: List<String>,
    val altMeanings: List<String> = emptyList(),
    val examples: List<Example> = emptyList(),
    val forms: List<String> = emptyList(),
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
data class BackupSet(
    val key: String,
    val lang: String,
    val name: String,
    val level: String? = null,
    val sort: Int = 0,
    val bundled: Boolean = false,
    val custom: Boolean = true,
    val enabled: Boolean = true,
    val rev: String? = null,
    val members: List<String> = emptyList(),
)

@Serializable
data class BackupSession(
    val id: Long,
    val kind: String,
    val lang: String = "zh",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcome: String? = null,
    val wins: Int = 0,
    val losses: Int = 0,
)

@Serializable
data class BackupAttempt(
    val sessionId: Long? = null,
    val lang: String = "zh",
    @JsonNames("hanzi") val term: String,
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
    val version: Int = 2,
    val exportedAt: Long,
    val words: List<BackupWord>,
    val sets: List<BackupSet> = emptyList(),
    val sessions: List<BackupSession> = emptyList(),
    val attempts: List<BackupAttempt> = emptyList(),
)
