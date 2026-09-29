package com.hanzilock.core

import android.content.Context
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Which apps stay usable while a practice session is due.
 *
 * Always allowed (not editable): HanziLock itself, the system UI (notification shade, volume,
 * power menu), keyboards, permission dialogs, the phone/in-call screens, emergency services and
 * your default phone and SMS apps. On top of that you choose apps in Settings > Allowed apps
 * (defaults: contacts, clock/alarm, calendar, camera, maps, wallet). Email apps can also get a
 * short pass when you open a notification that matches your priority-email rules.
 */
class AllowList(
    private val context: Context,
    private val settings: Settings,
    private val emailPasses: EmailPasses,
) {
    private val ownPackage = context.packageName

    @Volatile private var dynamic: Set<String> = emptySet()
    @Volatile private var dynamicAt = 0L
    @Volatile private var defaults: Set<String>? = null

    fun isAllowed(pkg: String): Boolean =
        pkg == ownPackage ||
            pkg in ALWAYS_ALLOWED ||
            pkg in dynamicEssentials() ||
            pkg in allowedApps() ||
            emailPasses.has(pkg)

    /** The apps you picked (or the installed defaults until you edit the list). */
    fun allowedApps(): Set<String> = settings.allowedPackages ?: installedDefaults()

    /** Apps worth showing as shortcuts on the lock screen, in a stable order. */
    fun shortcutPackages(): List<String> {
        val pm = context.packageManager
        val essentials = listOfNotNull(defaultDialer(), defaultSms())
        return (essentials + allowedApps().sorted())
            .distinct()
            .filter { it != ownPackage && pm.getLaunchIntentForPackage(it) != null }
    }

    /** Email apps whose notifications can grant a pass. */
    fun emailApps(): Set<String> = settings.emailPackages ?: EmailRules.KNOWN_EMAIL_APPS.filter(::isInstalled).toSet()

    fun installedDefaults(): Set<String> = defaults ?: DEFAULT_ALLOWED.filter(::isInstalled).toSet().also { defaults = it }

    fun essentialDescription(): List<String> = listOfNotNull(defaultDialer(), defaultSms())

    private fun dynamicEssentials(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - dynamicAt > 60_000 || dynamicAt == 0L) {
            dynamic = buildSet {
                runCatching {
                    context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { add(it.packageName) }
                }
                defaultDialer()?.let(::add)
                defaultSms()?.let(::add)
            }
            dynamicAt = now
        }
        return dynamic
    }

    private fun defaultDialer(): String? =
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    private fun defaultSms(): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    companion object {
        /** System pieces that must never be blocked. */
        val ALWAYS_ALLOWED = setOf(
            "android",
            "com.android.systemui",
            "com.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            // Phone calls
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.app.telephonyui",
            "com.android.stk",
            // Emergency
            "com.android.emergency",
            "com.samsung.android.emergency",
            "com.sec.android.app.safetyassurance",
            "com.google.android.apps.safetyhub",
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
            // Unlocking
            "com.samsung.android.biometrics.app.setting",
        )

        /** Pre-selected "allowed while locked" apps (only the installed ones are used). */
        val DEFAULT_ALLOWED = listOf(
            // Contacts & messages
            "com.samsung.android.app.contacts", "com.google.android.contacts",
            "com.samsung.android.messaging", "com.google.android.apps.messaging",
            // Alarm / clock
            "com.sec.android.app.clockpackage", "com.google.android.deskclock",
            // Calendar
            "com.samsung.android.calendar", "com.google.android.calendar",
            // Camera
            "com.sec.android.app.camera", "com.google.android.GoogleCamera",
            // Navigation - never block maps while driving
            "com.google.android.apps.maps", "com.waze",
            // Paying at a checkout
            "com.samsung.android.spay", "com.google.android.apps.walletnfcrel",
        )
    }
}
