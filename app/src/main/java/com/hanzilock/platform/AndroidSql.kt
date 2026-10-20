package com.hanzilock.platform

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hanzilock.data.ContentValues
import com.hanzilock.data.Cursor
import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.data.SqlStatement
import android.content.ContentValues as AndroidValues
import android.database.Cursor as AndroidCursor

/** [SqlDb] on Android's framework SQLite: the same database files the app has always used. */
class AndroidSqlDb(private val database: () -> SQLiteDatabase) : SqlDb {
    private val db: SQLiteDatabase get() = database()

    override fun execSQL(sql: String) = db.execSQL(sql)
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = db.execSQL(sql, arrayOf(*bindArgs))

    @Suppress("UNCHECKED_CAST")
    override fun rawQuery(sql: String, selectionArgs: Array<out String?>?): Cursor =
        Rows(db.rawQuery(sql, selectionArgs as Array<String>?))

    override fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        db.insertOrThrow(table, nullColumnHack, values.toAndroid())

    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflictAlgorithm: Int): Long =
        db.insertWithOnConflict(table, nullColumnHack, values.toAndroid(), conflictAlgorithm)

    @Suppress("UNCHECKED_CAST")
    override fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<out String?>?): Int =
        db.update(table, values.toAndroid(), whereClause, whereArgs as Array<String>?)

    @Suppress("UNCHECKED_CAST")
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out String?>?): Int =
        db.delete(table, whereClause, whereArgs as Array<String>?)

    override fun beginTransaction() = db.beginTransaction()
    override fun setTransactionSuccessful() = db.setTransactionSuccessful()
    override fun endTransaction() = db.endTransaction()

    override fun compileStatement(sql: String): SqlStatement {
        val statement = db.compileStatement(sql)
        return object : SqlStatement {
            override fun clearBindings() = statement.clearBindings()
            override fun bindString(index: Int, value: String) = statement.bindString(index, value)
            override fun bindLong(index: Int, value: Long) = statement.bindLong(index, value)
            override fun bindNull(index: Int) = statement.bindNull(index)
            override fun executeInsert(): Long = statement.executeInsert()
        }
    }

    private class Rows(private val c: AndroidCursor) : Cursor {
        override val count: Int get() = c.count
        override fun moveToNext() = c.moveToNext()
        override fun moveToFirst() = c.moveToFirst()
        override fun getColumnIndexOrThrow(name: String) = c.getColumnIndexOrThrow(name)
        override fun getString(index: Int): String? = c.getString(index)
        override fun getLong(index: Int) = c.getLong(index)
        override fun getInt(index: Int) = c.getInt(index)
        override fun getDouble(index: Int) = c.getDouble(index)
        override fun isNull(index: Int) = c.isNull(index)
        override fun close() = c.close()
    }

    companion object {
        /** Opens [schema]'s file in the app's database folder, creating or upgrading it as needed. */
        fun open(context: Context, schema: SqlSchema): SqlDb {
            val helper = object : SQLiteOpenHelper(context, schema.fileName, null, schema.version) {
                override fun onCreate(db: SQLiteDatabase) = schema.onCreate(AndroidSqlDb { db })
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    schema.onUpgrade(AndroidSqlDb { db }, oldVersion, newVersion)
            }
            if (schema.writeAheadLogging) helper.setWriteAheadLoggingEnabled(true)
            return AndroidSqlDb { helper.writableDatabase }
        }

        private fun ContentValues.toAndroid() = AndroidValues().also { cv ->
            for ((key, value) in entries) {
                when (value) {
                    null -> cv.putNull(key)
                    is String -> cv.put(key, value)
                    is Int -> cv.put(key, value)
                    is Long -> cv.put(key, value)
                    is Boolean -> cv.put(key, value)
                    is Double -> cv.put(key, value)
                    is Float -> cv.put(key, value)
                    is ByteArray -> cv.put(key, value)
                    else -> cv.put(key, value.toString())
                }
            }
        }
    }
}
