package com.hanzilock.core

import android.app.PendingIntent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** A notification from a priority email, shown on the lock screen. */
data class PriorityEmail(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val contentIntent: PendingIntent?,
)

/**
 * Short-lived passes that let an email app open while locked, granted when you open a priority
 * email (from the notification shade or from the lock screen). Kept in memory only.
 */
class EmailPasses {
    private val passes = ConcurrentHashMap<String, Long>()
    private val _inbox = MutableStateFlow<List<PriorityEmail>>(emptyList())

    /** Current priority-email notifications (kept up to date by the notification listener). */
    val inbox: StateFlow<List<PriorityEmail>> = _inbox

    fun grant(pkg: String, minutes: Int) {
        passes[pkg] = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun has(pkg: String): Boolean {
        val until = passes[pkg] ?: return false
        if (until < SystemClock.elapsedRealtime()) {
            passes.remove(pkg)
            return false
        }
        return true
    }

    fun clear() = passes.clear()

    fun setInbox(items: List<PriorityEmail>) {
        _inbox.value = items.sortedByDescending { it.postedAt }
    }
}
