package com.hanzilock.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hanzilock.lock.LockAccessibilityService
import com.hanzilock.lock.PriorityEmailListener
import android.provider.Settings as SystemSettings

/** Status checks and shortcuts into the system settings pages the setup checklist needs. */
object SystemAccess {
    fun accessibilityEnabled(context: Context): Boolean {
        if (LockAccessibilityService.running) return true
        val ours = ComponentName(context, LockAccessibilityService::class.java)
        val enabled = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    fun notificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun canDrawOverlays(context: Context): Boolean = SystemSettings.canDrawOverlays(context)

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun speechAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun start(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(SystemSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun openAccessibilitySettings(context: Context) = start(context, Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))

    /** App info page, where Android 13+ hides "Allow restricted settings" behind the ⋮ menu. */
    fun openAppInfo(context: Context) =
        start(context, Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun openNotificationAccess(context: Context) {
        val detail = Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                SystemSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, PriorityEmailListener::class.java).flattenToString(),
            )
        runCatching { context.startActivity(detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { start(context, Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    fun openOverlaySettings(context: Context) =
        start(context, Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))

    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) =
        start(context, Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))

    fun openTtsSettings(context: Context) = start(context, Intent("com.android.settings.TTS_SETTINGS"))

    fun openVoiceInputSettings(context: Context) = start(context, Intent(SystemSettings.ACTION_VOICE_INPUT_SETTINGS))
}
