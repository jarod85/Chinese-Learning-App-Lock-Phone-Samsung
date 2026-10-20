package com.hanzilock.platform

/**
 * Key-value preferences: SharedPreferences on the phone, a JSON file on the Mac. Reads are in-memory
 * and cheap; [edit] saves.
 */
interface Prefs {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun putBoolean(key: String, value: Boolean)
        fun putStringSet(key: String, value: Set<String>?)
        fun remove(key: String)
    }
}
