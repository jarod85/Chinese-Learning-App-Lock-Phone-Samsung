package com.hanzilock.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hanzilock.quiz.MeaningMatcher
import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.SentenceTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

sealed interface DictState {
    data object Checking : DictState
    data object Missing : DictState
    data class Importing(val entries: Int) : DictState
    data class Ready(val entries: Int) : DictState
    data class Failed(val message: String) : DictState
}

/**
 * The bundled CC-CEDICT dictionary (the .gz file in content/dictionary), imported once into its own
 * SQLite file and re-imported whenever the bundled file's "#! date=" header changes.
 */
class DictionaryRepository(private val context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "cedict.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE dict (id INTEGER PRIMARY KEY, trad TEXT NOT NULL, simp TEXT NOT NULL, " +
                    "pinyin TEXT NOT NULL, pinyin_key TEXT NOT NULL, defs TEXT NOT NULL)",
            )
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val _state = MutableStateFlow<DictState>(DictState.Checking)
    val state: StateFlow<DictState> = _state

    val isReady: Boolean get() = _state.value is DictState.Ready

    private val readingCache = ConcurrentHashMap<String, Set<String>>()
    private val wordCache = ConcurrentHashMap<String, Boolean>()

    suspend fun ensureImported() = withContext(Dispatchers.IO) {
        try {
            val asset = findAsset()
            if (asset == null) {
                _state.value = DictState.Missing
                return@withContext
            }
            val version = headerVersion(asset)
            val db = helper.writableDatabase
            val count = meta(db, "count")?.toIntOrNull()
            if (meta(db, "version") == version && count != null) {
                _state.value = DictState.Ready(count)
                return@withContext
            }
            import(db, asset, version)
        } catch (e: Exception) {
            _state.value = DictState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // Note: the Android build unpacks .gz assets and drops the extension, so the APK actually
    // contains dictionary/cedict_….txt. Both forms are handled.
    private fun findAsset(): String? =
        context.assets.list("dictionary")?.sorted()?.firstOrNull { it.endsWith(".gz") || it.endsWith(".u8") || it.endsWith(".txt") }
            ?.let { "dictionary/$it" }

    private fun open(asset: String): InputStream {
        val raw = BufferedInputStream(context.assets.open(asset), 64 * 1024)
        return if (asset.endsWith(".gz")) GZIPInputStream(raw, 64 * 1024) else raw
    }

    private fun headerVersion(asset: String): String = open(asset).bufferedReader(Charsets.UTF_8).use { r ->
        generateSequence { r.readLine() }.take(40).firstOrNull { it.startsWith("#! date=") }?.substringAfter("=")?.trim()
    } ?: asset

    private fun import(db: SQLiteDatabase, asset: String, version: String) {
        _state.value = DictState.Importing(0)
        var n = 0
        db.inTransaction {
            execSQL("DROP INDEX IF EXISTS dict_simp")
            execSQL("DROP INDEX IF EXISTS dict_trad")
            execSQL("DROP INDEX IF EXISTS dict_key")
            delete("dict", null, null)
            val insert = compileStatement("INSERT INTO dict (trad, simp, pinyin, pinyin_key, defs) VALUES (?,?,?,?,?)")
            open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (text in lines) {
                    val entry = CedictFormat.parse(text) ?: continue
                    insert.clearBindings()
                    insert.bindString(1, entry.traditional)
                    insert.bindString(2, entry.simplified)
                    insert.bindString(3, entry.pinyin)
                    insert.bindString(4, Pinyin.searchKey(entry.pinyin))
                    insert.bindString(5, entry.definitions)
                    insert.executeInsert()
                    n++
                    if (n % 5000 == 0) _state.value = DictState.Importing(n)
                }
            }
            execSQL("CREATE INDEX dict_simp ON dict(simp)")
            execSQL("CREATE INDEX dict_trad ON dict(trad)")
            execSQL("CREATE INDEX dict_key ON dict(pinyin_key)")
            setMeta(this, "version", version)
            setMeta(this, "count", n.toString())
        }
        readingCache.clear()
        wordCache.clear()
        _state.value = DictState.Ready(n)
    }

    private fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).mapRows { it.getString(0) }.firstOrNull()

    private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun query(sql: String, vararg args: String): List<DictEntry> =
        helper.readableDatabase.rawQuery(sql, arrayOf(*args)).mapRows {
            DictEntry(it.long("id"), it.str("simp"), it.str("trad"), it.str("pinyin"), it.str("defs").split('/').filter { d -> d.isNotBlank() })
        }

    /** Entries whose simplified or traditional form is exactly [hanzi]. */
    fun lookup(hanzi: String): List<DictEntry> {
        if (!isReady) return emptyList()
        return query("SELECT * FROM dict WHERE simp=? OR trad=? ORDER BY id", hanzi, hanzi)
            .sortedBy { e -> e.definitions.count { it.startsWith("surname ") || it.startsWith("variant of") || it.startsWith("old variant") } }
    }

    fun isWord(s: String): Boolean {
        if (!isReady) return false
        return wordCache.getOrPut(s) {
            helper.readableDatabase.rawQuery("SELECT 1 FROM dict WHERE simp=? LIMIT 1", arrayOf(s)).mapRows { true }.isNotEmpty()
        }
    }

    /** Numbered, lower-case readings of one character: "行" -> {xing2, hang2, ...}. */
    fun readings(ch: String): Set<String> {
        if (!isReady) return emptySet()
        return readingCache.getOrPut(ch) {
            query("SELECT * FROM dict WHERE simp=? OR trad=?", ch, ch)
                .mapNotNull { Pinyin.syllables(it.pinyinNumbered).singleOrNull() }
                .toSet()
        }
    }

    /** Pleco-style search: Chinese characters, pinyin (with or without tones) or English. */
    fun search(query: String, limit: Int = 60): List<DictEntry> {
        val q = query.trim()
        if (q.isEmpty() || !isReady) return emptyList()
        if (q.any { SentenceTiles.isHanChar(it) }) {
            val exact = query("SELECT * FROM dict WHERE simp=? OR trad=?", q, q)
            val prefix = query(
                "SELECT * FROM dict WHERE simp LIKE ? OR trad LIKE ? ORDER BY length(simp), id LIMIT $limit", "$q%", "$q%",
            )
            val contains = if (exact.size + prefix.size < limit) {
                query("SELECT * FROM dict WHERE simp LIKE ? ORDER BY length(simp), id LIMIT $limit", "%$q%")
            } else emptyList()
            return (exact + prefix + contains).distinctBy { it.id }.take(limit)
        }
        val results = ArrayList<DictEntry>()
        if (Pinyin.looksLikePinyin(q)) {
            val key = Pinyin.searchKey(q)
            val typed = Pinyin.canonical(q)
            val hits = query("SELECT * FROM dict WHERE pinyin_key=? ORDER BY length(simp), id LIMIT 200", key) +
                query("SELECT * FROM dict WHERE pinyin_key LIKE ? ORDER BY length(pinyin_key), id LIMIT 60", "$key%")
            results += hits.filter { e ->
                typed.tones.isEmpty() || Pinyin.canonical(e.pinyinNumbered).tones.take(typed.tones.size) == typed.tones
            }.sortedBy { e -> e.definitions.count { it.startsWith("surname") || it.contains("variant of") } }
        }
        results += searchEnglish(q, limit)
        return results.distinctBy { it.id }.take(limit)
    }

    private fun searchEnglish(q: String, limit: Int): List<DictEntry> {
        val needle = MeaningMatcher.normalize(q)
        if (needle.length < 2) return emptyList()
        val rows = query("SELECT * FROM dict WHERE defs LIKE ? LIMIT 600", "%$needle%")
        val boundary = Regex("\\b${Regex.escape(needle)}\\b")
        fun score(e: DictEntry): Int {
            var best = 0
            for (d in e.definitions) {
                val n = MeaningMatcher.normalize(d)
                val s = when {
                    n == needle || n == "to $needle" -> 100
                    n.startsWith("$needle ") || n.startsWith("to $needle ") -> 60
                    boundary.containsMatchIn(n) -> 30
                    else -> 5
                }
                if (s > best) best = s
            }
            if (e.definitions.any { it.contains("variant of") || it.startsWith("surname") }) best -= 40
            return best - e.simplified.length * 2 - e.definitions.size
        }
        return rows.sortedByDescending(::score).take(limit)
    }
}
