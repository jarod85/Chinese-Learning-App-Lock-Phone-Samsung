package com.hanzilock.lock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.hanzilock.HanziLockApp
import com.hanzilock.MainActivity

/**
 * The practice gate. While a session is due, any app that isn't allowed gets covered by the
 * practice screen the moment it comes to the front (and again after the screen turns on, and at
 * each reset time for an app that was already open).
 *
 * It only looks at package names of the windows in front - never at their content.
 */
class LockAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var running = false
            private set
    }

    private lateinit var app: HanziLockApp
    private val handler = Handler(Looper.getMainLooper())
    private var lastEnforceAt = 0L
    private var lastWindowsCheckAt = 0L
    private var frontPackage: String? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.postDelayed({ checkFront() }, 400)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            checkFront()
            scheduleTick()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        app = HanziLockApp.get(this)
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scheduleTick()
        handler.post { checkFront() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running || event == null) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    onWindowInFront(pkg, event.className?.toString())
                }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastWindowsCheckAt > 700) {
                        lastWindowsCheckAt = now
                        checkWindows()
                    }
                }
                else -> Unit
            }
        } catch (_: Exception) {
            // Never let a surprise crash the service - Android would switch it off.
        }
    }

    private fun onWindowInFront(pkg: String, className: String?) {
        frontPackage = pkg
        if (!app.lockEngine.isLockDue()) return
        if (pkg == packageName) {
            // The practice screen is fine; the rest of HanziLock (word list, settings) is not.
            if (className == MainActivity::class.java.name) enforce()
            return
        }
        if (app.allowList.isAllowed(pkg)) return
        if (pkg in app.allowList.emailApps() && app.settings.emailRules.isNotEmpty()) {
            // Tapping a priority email in the notification shade: the pass is granted by the
            // notification listener a moment after the email app appears, so check again shortly.
            handler.postDelayed({
                if (frontPackage == pkg && !app.allowList.isAllowed(pkg) && app.lockEngine.isLockDue()) enforce()
            }, 450)
            return
        }
        enforce()
    }

    /** Screen on / reset time reached: is something that should be gated already in front? */
    private fun checkFront() {
        if (!running || !app.lockEngine.isLockDue()) return
        val front = visibleAppPackages()
        if (front.isEmpty()) return
        if (front.any { it != packageName && !app.allowList.isAllowed(it) }) {
            enforce()
        } else if (front.all { it == packageName } && !LockActivity.visible) {
            enforce()
        }
    }

    /** Catches split screen / pop-up windows with a blocked app next to an allowed one. */
    private fun checkWindows() {
        if (!app.lockEngine.isLockDue()) return
        if (visibleAppPackages().any { it != packageName && !app.allowList.isAllowed(it) }) enforce()
    }

    private fun visibleAppPackages(): Set<String> {
        val result = HashSet<String>()
        runCatching {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.root?.packageName?.toString()?.let(result::add)
            }
        }
        if (result.isEmpty()) runCatching { rootInActiveWindow?.packageName?.toString()?.let(result::add) }
        return result
    }

    private fun enforce() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEnforceAt < 800) return
        lastEnforceAt = now
        LockActivity.launch(this)
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val now = System.currentTimeMillis()
        val next = app.lockEngine.nextResetAt(now)
        val delay = if (next != null) (next - now + 1_000).coerceIn(5_000, 60_000) else 60_000
        handler.postDelayed(tick, delay)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }
}
