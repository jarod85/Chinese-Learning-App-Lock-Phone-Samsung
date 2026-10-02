package com.hanzilock.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Words (per language) and the sets they belong to, practice sessions and every graded attempt.
 * Version 2 added languages and word sets; installs from version 1 are migrated in place.
 */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, "hanzilock.db", null, 2) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createWords(db, "words")
        createSets(db)
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                lang TEXT NOT NULL DEFAULT 'zh',
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
                lang TEXT NOT NULL DEFAULT 'zh',
                term TEXT NOT NULL,
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
        createIndexes(db)
    }

    private fun createWords(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """
            CREATE TABLE $table (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lang TEXT NOT NULL,
                term TEXT NOT NULL,
                traditional TEXT,
                reading TEXT NOT NULL DEFAULT '',
                meanings TEXT NOT NULL DEFAULT '[]',
                alt_meanings TEXT NOT NULL DEFAULT '[]',
                examples TEXT NOT NULL DEFAULT '[]',
                forms TEXT NOT NULL DEFAULT '[]',
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
                updated_at INTEGER NOT NULL,
                UNIQUE (lang, term)
            )
            """.trimIndent(),
        )
    }

    private fun createSets(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                lang TEXT NOT NULL,
                name TEXT NOT NULL,
                level TEXT,
                sort INTEGER NOT NULL DEFAULT 0,
                bundled INTEGER NOT NULL DEFAULT 0,
                custom INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 0,
                rev TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE set_words (
                set_id INTEGER NOT NULL,
                word_id INTEGER NOT NULL,
                PRIMARY KEY (set_id, word_id)
            )
            """.trimIndent(),
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS attempts_word ON attempts(word_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS attempts_at ON attempts(lang, at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS words_due ON words(lang, enabled, last_seen_at, due_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS set_words_word ON set_words(word_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS sets_lang ON sets(lang, sort)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
    }

    /** v1 had Chinese-only words keyed by hanzi and no sets. */
    private fun migrateToV2(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        createWords(db, "words_v2")
        db.execSQL(
            """
            INSERT INTO words_v2 (id, lang, term, traditional, reading, meanings, alt_meanings, examples, tags, source,
                user_edited, enabled, box, due_at, wins, losses, last_seen_at, created_at, updated_at)
            SELECT id, 'zh', hanzi, traditional, pinyin, meanings, alt_meanings, examples, tags, source,
                user_edited, enabled, box, due_at, wins, losses, last_seen_at, created_at, updated_at FROM words
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE words")
        db.execSQL("ALTER TABLE words_v2 RENAME TO words")
        createSets(db)
        // The old bundled registry was HSK 1; words you added yourself go into "My words".
        db.execSQL(
            "INSERT INTO sets (key, lang, name, level, sort, bundled, custom, enabled, rev, created_at) " +
                "VALUES ('zh.hsk1', 'zh', 'HSK 1', 'Beginner', 1, 1, 0, 1, NULL, $now)",
        )
        db.execSQL("INSERT INTO set_words (set_id, word_id) SELECT (SELECT id FROM sets WHERE key='zh.hsk1'), id FROM words WHERE source='registry'")
        db.execSQL(
            "INSERT INTO sets (key, lang, name, level, sort, bundled, custom, enabled, rev, created_at) " +
                "VALUES ('zh.mine', 'zh', 'My words', 'Custom', 100, 0, 1, 1, NULL, $now)",
        )
        db.execSQL("INSERT INTO set_words (set_id, word_id) SELECT (SELECT id FROM sets WHERE key='zh.mine'), id FROM words WHERE source<>'registry'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN lang TEXT NOT NULL DEFAULT 'zh'")
        db.execSQL("ALTER TABLE attempts RENAME COLUMN hanzi TO term")
        db.execSQL("ALTER TABLE attempts ADD COLUMN lang TEXT NOT NULL DEFAULT 'zh'")
        db.execSQL("DROP INDEX IF EXISTS attempts_at")
        db.execSQL("DROP INDEX IF EXISTS words_due")
        createIndexes(db)
    }
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
