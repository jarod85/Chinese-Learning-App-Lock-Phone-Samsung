package com.hanzilock.desktop

import com.hanzilock.AppCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * The practice gate on the Mac (off until you switch the lock on): when a session is due, a full-screen
 * window with the words covers the screen and stays in front of other apps, except the ones you allow,
 * until you finish them or use the master PIN. Like the phone's, it's a self-discipline tool, not a
 * kiosk: Force Quit still works.
 */
class MacGate(private val core: AppCore, private val helper: MacHelper?) {
    /** A session is on the gate (its window exists until the words are done). */
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** The gate is on screen (it steps aside while an allowed app is in front). */
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    private val _toFront = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Asks the gate window to come back in front of the app you switched to. */
    val toFront: SharedFlow<Unit> = _toFront

    /** Bundle id of the app in front ("" until the helper said). */
    @Volatile
    private var front = ""

    val allowList = MacAllowList(core)

    fun start() {
        helper?.addListener { e ->
            if (e.eventType == "front") {
                front = e.string("id").orEmpty()
                refresh(reassert = true)
            }
        }
        core.scope.launch {
            while (true) {
                refresh()
                delay(15_000)
            }
        }
        // Starting at login keeps the gate working all day; only while the lock is on.
        core.scope.launch {
            core.settings.changes.map { core.settings.lockEnabled }.distinctUntilChanged().collectLatest { on ->
                if (on) LaunchAgent.install() else LaunchAgent.remove()
            }
        }
    }

    /** Re-checks whether the gate should be up, e.g. after "Lock now (test)" or waking up. */
    @Synchronized
    fun refresh(reassert: Boolean = false) {
        if (!_open.value && core.lockEngine.isLockDue()) _open.value = true
        val allowedInFront = front.isNotEmpty() && allowList.isAllowed(front)
        val show = _open.value && !allowedInFront
        val cameBack = show && !_visible.value
        _visible.value = show
        if (show && (cameBack || reassert && front != MacUpdater.BUNDLE_ID)) _toFront.tryEmit(Unit)
    }

    /** The words are done (or the PIN was used): the gate goes away. */
    @Synchronized
    fun close() {
        _open.value = false
        _visible.value = false
    }
}

/** Apps that stay usable while the gate is up: your choice (Settings > Allowed apps) plus what macOS needs. */
class MacAllowList(private val core: AppCore) {
    fun allowedApps(): Set<String> = core.settings.allowedPackages ?: DEFAULTS

    fun isAllowed(bundleId: String): Boolean = bundleId in ALWAYS || bundleId in allowedApps()

    companion object {
        /** Lingo Lock itself and the system's login, password and permission windows. */
        val ALWAYS = setOf(
            MacUpdater.BUNDLE_ID,
            "com.apple.loginwindow",
            "com.apple.ScreenSaver.Engine",
            "com.apple.SecurityAgent",
            "com.apple.UserNotificationCenter",
            "com.apple.coreservices.uiagent",
            "com.apple.CoreLocationAgent",
            "com.apple.universalcontrol",
        )

        /** Calendar, Clock, FaceTime, Messages, Phone, Zoom, Teams and Webex, until you change the list. */
        val DEFAULTS = setOf(
            "com.apple.iCal",
            "com.apple.clock",
            "com.apple.FaceTime",
            "com.apple.MobileSMS",
            "com.apple.mobilephone",
            "us.zoom.xos",
            "com.microsoft.teams2",
            "com.microsoft.teams",
            "Cisco-Systems.Spark",
        )
    }
}

/**
 * ~/Library/LaunchAgents/com.hanzilock.mac.plist: macOS starts Lingo Lock (in the menu bar, without its
 * window) when you log in, and again if it's force-quit. It's there only while the lock is on.
 */
object LaunchAgent {
    private val file = File(System.getProperty("user.home"), "Library/LaunchAgents/com.hanzilock.mac.plist")

    fun install() {
        val launcher = System.getProperty("jpackage.app-path") ?: return   // not an installed app
        if (!DesktopPaths.isMac) return
        file.parentFile.mkdirs()
        file.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>com.hanzilock.mac</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>${launcher.escapeXml()}</string>
            |        <string>$BACKGROUND</string>
            |    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |    <key>KeepAlive</key>
            |    <dict>
            |        <key>SuccessfulExit</key>
            |        <false/>
            |    </dict>
            |    <key>ProcessType</key>
            |    <string>Interactive</string>
            |</dict>
            |</plist>
            |""".trimMargin(),
        )
    }

    fun remove() {
        if (file.exists()) file.delete()
    }

    /** Started at login: menu bar only, no window. */
    const val BACKGROUND = "--background"

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
