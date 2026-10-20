package com.hanzilock.data

import java.io.Closeable

/**
 * A SQLite database: the phone's (Android's framework SQLite) or the Mac's (JDBC). The methods are the
 * part of Android's SQLiteDatabase the repositories use, with the same meaning, so both behave alike.
 * Selection arguments are bound as text, as on Android.
 */
interface SqlDb {
    fun execSQL(sql: String)
    fun execSQL(sql: String, bindArgs: Array<out Any?>)
    fun rawQuery(sql: String, selectionArgs: Array<out String?>?): Cursor

    /** Row id of the new row; throws if the insert fails. */
    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long

    /** Row id of the new row, or -1 if it was ignored ([CONFLICT_IGNORE]) or failed. */
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflictAlgorithm: Int): Long
    fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<out String?>?): Int
    fun delete(table: String, whereClause: String?, whereArgs: Array<out String?>?): Int

    /** Transactions nest; the outermost commits only if every level called [setTransactionSuccessful]. */
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()

    fun compileStatement(sql: String): SqlStatement

    companion object {
        // Android's SQLiteDatabase constants.
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5
    }
}

/** A prepared INSERT, reused for bulk imports. Indexes start at 1. */
interface SqlStatement {
    fun clearBindings()
    fun bindString(index: Int, value: String)
    fun bindLong(index: Int, value: Long)
    fun bindNull(index: Int)
    fun executeInsert(): Long
}

/** Query results, read row by row as with Android's Cursor. Columns are 0-based. */
interface Cursor : Closeable {
    val count: Int
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    fun getColumnIndexOrThrow(name: String): Int
    fun getString(index: Int): String?
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun getDouble(index: Int): Double
    fun isNull(index: Int): Boolean
}

/** Column values for an insert or update (null, String, Int, Long, Double, Boolean or ByteArray). */
class ContentValues {
    private val map = LinkedHashMap<String, Any?>()

    fun put(key: String, value: Any?) {
        map[key] = value
    }

    fun putNull(key: String) {
        map[key] = null
    }

    val entries: Set<Map.Entry<String, Any?>> get() = map.entries
    fun isEmpty(): Boolean = map.isEmpty()
}

/** A database file's name, schema version and migrations. */
interface SqlSchema {
    val fileName: String
    val version: Int

    /** Write-ahead logging (the words database uses it). */
    val writeAheadLogging: Boolean get() = false
    fun onCreate(db: SqlDb)
    fun onUpgrade(db: SqlDb, oldVersion: Int, newVersion: Int)
}

inline fun <T> SqlDb.inTransaction(block: SqlDb.() -> T): T {
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

fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()
fun Cursor.strOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
