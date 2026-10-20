package com.hanzilock.platform

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hanzilock.BuildConfig
import com.hanzilock.data.SqlDb
import com.hanzilock.data.SqlSchema
import com.hanzilock.speech.AndroidSpeaker
import com.hanzilock.speech.AndroidSpeechInput
import com.hanzilock.speech.Speaker
import com.hanzilock.speech.SpeechInput
import com.hanzilock.update.AppUpdater
import com.hanzilock.update.Updater
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream

/** The phone's side of [Platform]: the same preferences, databases and assets the app has always used. */
class AndroidPlatform(private val context: Context) : Platform {
    override val prefs: Prefs = AndroidPrefs(context.getSharedPreferences("hanzilock", Context.MODE_PRIVATE))

    override val assets: Assets = object : Assets {
        override fun open(path: String): InputStream = context.assets.open(path)
        override fun list(dir: String): List<String> = context.assets.list(dir)?.toList().orEmpty()
    }

    override val buildNumber: Int = BuildConfig.VERSION_CODE
    override val versionName: String = BuildConfig.VERSION_NAME
    override val deviceName = "Phone"

    override fun openDatabase(schema: SqlSchema): SqlDb = AndroidSqlDb.open(context, schema)
    override fun createSpeaker(): Speaker = AndroidSpeaker(context)
    override fun createSpeechInput(): SpeechInput = AndroidSpeechInput(context)
    override fun createUpdater(scope: CoroutineScope): Updater = AppUpdater(context, scope)

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

private class AndroidPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = prefs.getStringSet(key, default)

    override fun edit(block: Prefs.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : Prefs.Editor {
            override fun putString(key: String, value: String?) { editor.putString(key, value) }
            override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun putStringSet(key: String, value: Set<String>?) { editor.putStringSet(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.block()
        editor.apply()
    }
}
