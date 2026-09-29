package com.hanzilock.lock

import android.app.ActivityOptions
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hanzilock.HanziLockApp
import com.hanzilock.core.EmailRules
import com.hanzilock.core.PriorityEmail

/**
 * Lets emails you care about through while locked. Notifications are never hidden; this only
 * (1) grants the email app a short pass when you open a notification that matches your rules
 * from the notification shade, and (2) lists matching emails on the practice screen.
 */
class PriorityEmailListener : NotificationListenerService() {
    companion object {
        @Volatile
        var connected = false
            private set

        /** Opens a priority email from the lock screen, granting its app a pass first. */
        fun open(context: Context, email: PriorityEmail) {
            val app = HanziLockApp.get(context)
            app.emailPasses.grant(email.packageName, app.settings.emailPassMinutes)
            val sent = email.contentIntent?.let { pi ->
                runCatching {
                    val options = ActivityOptions.makeBasic()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Android 14+: we're in front, so let the email app's PendingIntent open its screen.
                        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }
                    pi.send(context, 0, null, null, null, null, options.toBundle())
                }.isSuccess
            } ?: false
            if (!sent) {
                context.packageManager.getLaunchIntentForPackage(email.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { runCatching { context.startActivity(it) } }
            }
        }
    }

    private val app: HanziLockApp get() = HanziLockApp.get(this)

    override fun onListenerConnected() {
        connected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        connected = false
        app.emailPasses.setInbox(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in app.allowList.emailApps()) refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (reason == REASON_CLICK && isPriority(sbn) && app.lockEngine.isLockDue()) {
            app.emailPasses.grant(sbn.packageName, app.settings.emailPassMinutes)
        }
        if (sbn.packageName in app.allowList.emailApps()) refresh()
    }

    private fun isPriority(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in app.allowList.emailApps()) return false
        val rules = app.settings.emailRules
        return rules.isNotEmpty() && EmailRules.matches(textOf(sbn), rules)
    }

    private fun refresh() {
        val matching = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty().filter(::isPriority)
        val children = matching.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        val items = children.ifEmpty { matching }.map { sbn ->
            val extras = sbn.notification.extras
            PriorityEmail(
                key = sbn.key,
                packageName = sbn.packageName,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                    ?.toString().orEmpty(),
                postedAt = sbn.postTime,
                contentIntent = sbn.notification.contentIntent,
            )
        }
        app.emailPasses.setInbox(items)
    }

    private fun textOf(sbn: StatusBarNotification): String {
        val e = sbn.notification.extras
        val keys = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_CONVERSATION_TITLE,
        )
        val parts = keys.mapNotNull { e.getCharSequence(it)?.toString() } +
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        return parts.joinToString("\n")
    }
}
