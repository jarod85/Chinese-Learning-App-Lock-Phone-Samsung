package com.hanzilock.data

/**
 * Words (per language) and the sets they belong to, practice sessions and every graded attempt.
 * Version 2 added languages and word sets, version 3 what syncing between your devices needs; older
 * installs are migrated in place.
 */
object AppDatabase : SqlSchema {
    override val fileName = "hanzilock.db"
    override val version = 3
    override val writeAheadLogging = true

    override fun onCreate(db: SqlDb) {
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
        migrateToV3(db)
    }

    private fun createWords(db: SqlDb, table: String) {
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

    private fun createSets(db: SqlDb) {
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

    private fun createIndexes(db: SqlDb) {
        db.execSQL("CREATE INDEX IF NOT EXISTS attempts_word ON attempts(word_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS attempts_at ON attempts(lang, at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS words_due ON words(lang, enabled, last_seen_at, due_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS set_words_word ON set_words(word_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS sets_lang ON sets(lang, sort)")
    }

    override fun onUpgrade(db: SqlDb, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
        if (oldVersion < 3) migrateToV3(db)
    }

    /** v1 had Chinese-only words keyed by hanzi and no sets. */
    private fun migrateToV2(db: SqlDb) {
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

    /**
     * v3: ids that are the same on every device (sessions, attempts), when sets and memberships changed,
     * which words are worth syncing, and what was deleted.
     */
    private fun migrateToV3(db: SqlDb) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN uid TEXT")
        db.execSQL("ALTER TABLE attempts ADD COLUMN uid TEXT")
        db.execSQL("UPDATE sessions SET uid=lower(hex(randomblob(16))) WHERE uid IS NULL")
        db.execSQL("UPDATE attempts SET uid=lower(hex(randomblob(16))) WHERE uid IS NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS sessions_uid ON sessions(uid)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS attempts_uid ON attempts(uid)")
        db.execSQL("ALTER TABLE sets ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE set_words ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0")
        // Words to sync besides practised ones: edited, switched off, added by you, or in your own sets.
        db.execSQL("ALTER TABLE words ADD COLUMN touched INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE words SET touched=1 WHERE user_edited=1 OR enabled=0 OR alt_meanings<>'[]' OR source NOT IN ('set', 'registry') " +
                "OR id IN (SELECT sw.word_id FROM set_words sw JOIN sets s ON s.id=sw.set_id WHERE s.custom=1)",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS deletions (kind TEXT NOT NULL, key TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (kind, key))")
    }
}
