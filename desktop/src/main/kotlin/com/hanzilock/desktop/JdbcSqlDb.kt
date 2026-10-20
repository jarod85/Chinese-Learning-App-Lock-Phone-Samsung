package com.hanzilock.desktop

import com.hanzilock.data.ContentValues
import com.hanzilock.data.Cursor
import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.data.SqlStatement
import com.hanzilock.data.inTransaction
import com.hanzilock.data.mapRows
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [SqlDb] on SQLite through JDBC (the Mac). One connection, shared by all threads: a transaction holds
 * the lock until it ends, so other threads wait for it, as with Android's SQLiteDatabase.
 */
class JdbcSqlDb private constructor(private val conn: Connection) : SqlDb {
    private val lock = ReentrantLock()
    private val levels = ArrayDeque<Boolean>()   // per open transaction: setTransactionSuccessful() called
    private var failed = false

    private inline fun <T> locked(block: () -> T): T = lock.withLock(block)

    override fun execSQL(sql: String) = locked {
        conn.createStatement().use { it.execute(sql) }
        Unit
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = locked {
        conn.prepareStatement(sql).use { st ->
            bindArgs.forEachIndexed { i, v -> bind(st, i + 1, v) }
            st.execute()
        }
        Unit
    }

    override fun rawQuery(sql: String, selectionArgs: Array<out String?>?): Cursor = locked {
        conn.prepareStatement(sql).use { st ->
            selectionArgs?.forEachIndexed { i, v -> bind(st, i + 1, v) }
            st.executeQuery().use(::Rows)
        }
    }

    override fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        insert("INSERT", table, values).also { if (it < 0) error("Insert into $table failed") }

    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflictAlgorithm: Int): Long =
        runCatching {
            insert(
                when (conflictAlgorithm) {
                    SqlDb.CONFLICT_IGNORE -> "INSERT OR IGNORE"
                    SqlDb.CONFLICT_REPLACE -> "INSERT OR REPLACE"
                    else -> "INSERT"
                },
                table, values,
            )
        }.getOrDefault(-1)

    private fun insert(verb: String, table: String, values: ContentValues): Long = locked {
        val entries = values.entries.toList()
        val sql = "$verb INTO $table (${entries.joinToString(",") { it.key }}) VALUES (${entries.joinToString(",") { "?" }})"
        val changed = conn.prepareStatement(sql).use { st ->
            entries.forEachIndexed { i, e -> bind(st, i + 1, e.value) }
            st.executeUpdate()
        }
        if (changed == 0) -1 else lastRowId()
    }

    override fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<out String?>?): Int = locked {
        val entries = values.entries.toList()
        val sql = "UPDATE $table SET ${entries.joinToString(",") { "${it.key}=?" }}" + (whereClause?.let { " WHERE $it" } ?: "")
        conn.prepareStatement(sql).use { st ->
            entries.forEachIndexed { i, e -> bind(st, i + 1, e.value) }
            whereArgs?.forEachIndexed { i, v -> bind(st, entries.size + i + 1, v) }
            st.executeUpdate()
        }
    }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<out String?>?): Int = locked {
        conn.prepareStatement("DELETE FROM $table" + (whereClause?.let { " WHERE $it" } ?: "")).use { st ->
            whereArgs?.forEachIndexed { i, v -> bind(st, i + 1, v) }
            st.executeUpdate()
        }
    }

    override fun beginTransaction() {
        lock.lock()
        if (levels.isEmpty()) {
            conn.autoCommit = false
            failed = false
        }
        levels.addLast(false)
    }

    override fun setTransactionSuccessful() {
        check(lock.isHeldByCurrentThread && levels.isNotEmpty()) { "No transaction" }
        levels[levels.lastIndex] = true
    }

    override fun endTransaction() {
        check(lock.isHeldByCurrentThread && levels.isNotEmpty()) { "No transaction" }
        try {
            if (!levels.removeLast()) failed = true
            if (levels.isEmpty()) {
                try {
                    if (failed) conn.rollback() else conn.commit()
                } finally {
                    conn.autoCommit = true
                }
            }
        } finally {
            lock.unlock()
        }
    }

    override fun compileStatement(sql: String): SqlStatement {
        val st = locked { conn.prepareStatement(sql) }
        return object : SqlStatement {
            override fun clearBindings() = locked { st.clearParameters() }
            override fun bindString(index: Int, value: String) = locked { st.setString(index, value) }
            override fun bindLong(index: Int, value: Long) = locked { st.setLong(index, value) }
            override fun bindNull(index: Int) = locked { st.setNull(index, Types.NULL) }
            override fun executeInsert(): Long = locked { if (st.executeUpdate() == 0) -1 else lastRowId() }
        }
    }

    private fun lastRowId(): Long = conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }

    private fun bind(st: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> st.setNull(index, Types.NULL)
            is String -> st.setString(index, value)
            is Int -> st.setLong(index, value.toLong())
            is Long -> st.setLong(index, value)
            is Boolean -> st.setLong(index, if (value) 1 else 0)
            is Double -> st.setDouble(index, value)
            is Float -> st.setDouble(index, value.toDouble())
            is ByteArray -> st.setBytes(index, value)
            else -> st.setString(index, value.toString())
        }
    }

    /** A result set read completely, so the connection is free again right away. */
    private class Rows(rs: ResultSet) : Cursor {
        private val columns: List<String>
        private val rows = ArrayList<Array<Any?>>()
        private var position = -1

        init {
            val meta = rs.metaData
            columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            while (rs.next()) rows.add(Array(columns.size) { rs.getObject(it + 1) })
        }

        private fun value(index: Int): Any? = rows[position][index]

        override val count: Int get() = rows.size
        override fun moveToNext(): Boolean = ++position < rows.size
        override fun moveToFirst(): Boolean {
            position = 0
            return rows.isNotEmpty()
        }

        override fun getColumnIndexOrThrow(name: String): Int =
            columns.indexOf(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("No column $name")

        override fun getString(index: Int): String? = when (val v = value(index)) {
            null -> null
            is ByteArray -> String(v, Charsets.UTF_8)
            else -> v.toString()
        }

        override fun getLong(index: Int): Long = when (val v = value(index)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }

        override fun getInt(index: Int): Int = getLong(index).toInt()

        override fun getDouble(index: Int): Double = when (val v = value(index)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        override fun isNull(index: Int): Boolean = value(index) == null
        override fun close() = Unit
    }

    companion object {
        /** Opens [schema]'s file in [dir], creating or upgrading it (PRAGMA user_version is its version). */
        fun open(dir: File, schema: SqlSchema): SqlDb {
            dir.mkdirs()
            val conn = DriverManager.getConnection("jdbc:sqlite:${File(dir, schema.fileName).absolutePath}")
            val db = JdbcSqlDb(conn)
            if (schema.writeAheadLogging) db.execSQL("PRAGMA journal_mode=WAL")
            val current = db.rawQuery("PRAGMA user_version", null).mapRows { it.getInt(0) }.first()
            if (current < schema.version) {
                db.inTransaction {
                    if (current == 0) schema.onCreate(this) else schema.onUpgrade(this, current, schema.version)
                    execSQL("PRAGMA user_version = ${schema.version}")
                }
            }
            return db
        }
    }
}
