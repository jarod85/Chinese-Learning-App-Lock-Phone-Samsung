package com.hanzilock.stories

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hanzilock.data.AppJson
import com.hanzilock.data.long
import com.hanzilock.data.longOrNull
import com.hanzilock.data.int
import com.hanzilock.data.mapRows
import com.hanzilock.data.str
import com.hanzilock.data.strOrNull
import com.hanzilock.quiz.SentenceTiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Your stories, in their own database file (stories.db) next to the words. Each story's text,
 * translations and questions are one JSON column. Call from a background thread.
 */
class StoryRepository(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "stories.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE stories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    title_en TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL,
                    url TEXT,
                    level TEXT,
                    chars INTEGER NOT NULL DEFAULT 0,
                    pages INTEGER NOT NULL DEFAULT 1,
                    page INTEGER NOT NULL DEFAULT 0,
                    body TEXT NOT NULL,
                    finished_at INTEGER,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val _changes = MutableStateFlow(0L)

    /** Increments after every write, so the story list knows to reload. */
    val changes: StateFlow<Long> = _changes

    private fun changed() = _changes.update { it + 1 }

    fun list(): List<StorySummary> = db.rawQuery(
        "SELECT id, title, title_en, source, level, chars, pages, page, finished_at, created_at FROM stories ORDER BY created_at DESC",
        null,
    ).mapRows {
        StorySummary(
            id = it.long("id"),
            title = it.str("title"),
            titleEn = it.str("title_en"),
            source = it.str("source"),
            level = it.strOrNull("level"),
            chars = it.int("chars"),
            page = it.int("page"),
            pages = it.int("pages"),
            finishedAt = it.longOrNull("finished_at"),
            createdAt = it.long("created_at"),
        )
    }

    fun get(id: Long): Story? = db.rawQuery("SELECT * FROM stories WHERE id=?", arrayOf(id.toString())).mapRows {
        Story(
            id = it.long("id"),
            title = it.str("title"),
            titleEn = it.str("title_en"),
            source = it.str("source"),
            url = it.strOrNull("url"),
            level = it.strOrNull("level"),
            body = runCatching { AppJson.decodeFromString<StoryBody>(it.str("body")) }.getOrDefault(StoryBody()),
            page = it.int("page"),
            finishedAt = it.longOrNull("finished_at"),
            createdAt = it.long("created_at"),
        )
    }.firstOrNull()

    fun findByUrl(url: String): Long? =
        db.rawQuery("SELECT id FROM stories WHERE url=? LIMIT 1", arrayOf(url)).mapRows { it.getLong(0) }.firstOrNull()

    /** Links of the stories you already have. */
    fun urls(): Set<String> = db.rawQuery("SELECT url FROM stories WHERE url IS NOT NULL", null).mapRows { it.getString(0) }.toSet()

    /** Titles of the newest stories (so Claude doesn't write the same one twice). */
    fun recentTitles(limit: Int = 30): List<String> =
        db.rawQuery("SELECT title FROM stories ORDER BY created_at DESC LIMIT $limit", null).mapRows { it.getString(0) }

    fun add(draft: StoryDraft): Long {
        val chunks = draft.body.chunks
        val id = db.insertOrThrow("stories", null, ContentValues().apply {
            put("title", draft.title)
            put("title_en", draft.titleEn)
            put("source", draft.source)
            put("url", draft.url)
            put("level", draft.level)
            put("chars", chunks.sumOf { c -> StoryText.join(c.tokens).count(SentenceTiles::isHanChar) })
            put("pages", StoryText.paginate(chunks).size.coerceAtLeast(1))
            put("body", AppJson.encodeToString(StoryBody.serializer(), draft.body))
            put("created_at", System.currentTimeMillis())
        })
        changed()
        return id
    }

    /** Saves new translations or questions. */
    fun saveBody(id: Long, body: StoryBody) {
        db.update("stories", ContentValues().apply { put("body", AppJson.encodeToString(StoryBody.serializer(), body)) }, "id=?", arrayOf(id.toString()))
    }

    fun savePage(id: Long, page: Int) {
        db.update("stories", ContentValues().apply { put("page", page) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun markFinished(id: Long) {
        db.update("stories", ContentValues().apply { put("finished_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun delete(id: Long) {
        db.delete("stories", "id=?", arrayOf(id.toString()))
        changed()
    }
}
