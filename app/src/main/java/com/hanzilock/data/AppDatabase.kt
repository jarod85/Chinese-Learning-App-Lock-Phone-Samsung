package com.hanzilock.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Words (your registry + progress), practice sessions and every graded attempt. */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, "hanzilock.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hanzi TEXT NOT NULL UNIQUE,
                traditional TEXT,
                pinyin TEXT NOT NULL,
                meanings TEXT NOT NULL,
                alt_meanings TEXT NOT NULL DEFAULT '[]',
                examples TEXT NOT NULL DEFAULT '[]',
                tags TEXT NOT NULL DEFAULT '[]',
                source TEXT NOT NULL DEFAULT 'user',
                user_edited INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                box INTEGER NOT NULL DEFAULT 0,
                due_at INTEGER NOT NULL DEFAULT 0,
                wins INTEGER NOT NULL DEFAULT 0,
                losses INTEGER NOT NULL DEFAULT 0,
                last_seen_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                outcome TEXT,
                wins INTEGER NOT NULL DEFAULT 0,
                losses INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER,
                word_id INTEGER NOT NULL,
                hanzi TEXT NOT NULL,
                at INTEGER NOT NULL,
                win INTEGER NOT NULL,
                failed_part TEXT,
                heard TEXT,
                meaning_answer TEXT,
                sentence_answer TEXT,
                feedback TEXT,
                graded_by TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX attempts_word ON attempts(word_id)")
        db.execSQL("CREATE INDEX attempts_at ON attempts(at)")
        db.execSQL("CREATE INDEX words_due ON words(enabled, last_seen_at, due_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

inline fun <T> Cursor.mapRows(transform: (Cursor) -> T): List<T> = use {
    val out = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) out.add(transform(this))
    out
}

fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column))
fun Cursor.strOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
