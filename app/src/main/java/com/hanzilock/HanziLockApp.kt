package com.hanzilock

import android.app.Application
import android.content.Context
import com.hanzilock.core.AllowList
import com.hanzilock.core.EmailPasses
import com.hanzilock.core.Languages
import com.hanzilock.core.LockEngine
import com.hanzilock.core.PinManager
import com.hanzilock.core.Settings
import com.hanzilock.platform.AndroidPlatform
import com.hanzilock.speech.Speaker
import com.hanzilock.update.AppUpdater

/**
 * The phone app: the shared [AppCore] plus what only Android has, shared by the UI, the accessibility
 * service and the notification listener.
 */
class HanziLockApp : Application() {
    lateinit var core: AppCore private set
    lateinit var emailPasses: EmailPasses private set
    lateinit var allowList: AllowList private set

    val settings: Settings get() = core.settings
    val languages: Languages get() = core.languages
    val pin: PinManager get() = core.pin
    val lockEngine: LockEngine get() = core.lockEngine
    val speaker: Speaker get() = core.speaker
    val updater: AppUpdater get() = core.updater as AppUpdater

    override fun onCreate() {
        super.onCreate()
        core = AppCore(AndroidPlatform(this))
        emailPasses = EmailPasses()
        allowList = AllowList(this, settings, emailPasses)
        core.start()
    }

    companion object {
        fun get(context: Context): HanziLockApp = context.applicationContext as HanziLockApp
    }
}
