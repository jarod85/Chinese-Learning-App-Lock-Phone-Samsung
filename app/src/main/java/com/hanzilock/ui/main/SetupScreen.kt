package com.hanzilock.ui.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.speech.Speaker

/** First-run checklist. Everything needed for the lock to work on a Samsung Galaxy phone. */
@Composable
fun SetupScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val resumed = rememberResumeCount()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val tts by app.speaker.status.collectAsStateWithLifecycle()
    var micAsked by remember { mutableIntStateOf(0) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micAsked++ }

    val lang = remember(settingsVersion) { app.languages.active }
    val hasVoice = remember(resumed, settingsVersion, tts) { tts == Speaker.Status.READY && app.speaker.hasVoice(lang.javaLocale) }
    val items = remember(resumed, settingsVersion, tts, micAsked) {
        listOf(
            SetupItem(
                "Microphone",
                "Used to hear you pronounce each word.",
                SystemAccess.micGranted(context), "Allow", true, { mic.launch(Manifest.permission.RECORD_AUDIO) },
            ),
            SetupItem(
                "Practice gate (Accessibility)",
                "Settings > Accessibility > Installed apps > Lingo Lock practice gate > On.\n" +
                    "If the switch is greyed out (\"Restricted setting\"): open App info, tap ⋮ (top right) > " +
                    "\"Allow restricted settings\", then try again.",
                SystemAccess.accessibilityEnabled(context), "Open", true,
                { SystemAccess.openAccessibilitySettings(context) },
                "App info" to { SystemAccess.openAppInfo(context) },
            ),
            SetupItem(
                "Run in the background",
                "Stops One UI from putting Lingo Lock to sleep. Also add it under Settings > Battery > " +
                    "Background usage limits > Never sleeping apps.",
                SystemAccess.batteryUnrestricted(context), "Allow", true, { SystemAccess.requestBatteryExemption(context) },
            ),
            SetupItem(
                "Appear on top (recommended)",
                "Lets the practice screen jump in front of other apps more reliably.",
                SystemAccess.canDrawOverlays(context), "Open", false, { SystemAccess.openOverlaySettings(context) },
            ),
            SetupItem(
                "${lang.name} voice",
                when {
                    tts == Speaker.Status.STARTING -> "Checking the text-to-speech engine…"
                    tts == Speaker.Status.UNAVAILABLE -> "No text-to-speech engine found. Install Google Speech Services from the Play Store."
                    hasVoice -> "Pronunciation audio is ready."
                    else -> "Install a ${lang.name} voice: Settings > General management > Text-to-speech > preferred engine " +
                        "(Samsung or Google) > ⚙ > Install voice data > ${lang.name}."
                },
                hasVoice, "Settings", false, { SystemAccess.openTtsSettings(context) },
                "Play “${greeting(lang.code)}”" to { app.speaker.speak(greeting(lang.code), lang.javaLocale) },
            ),
            SetupItem(
                "Speech recognition",
                if (SystemAccess.speechAvailable(context)) {
                    "Available. It works best online; for offline use download ${lang.name} in Google's speech recognition " +
                        "settings. Without it you can type the reading (Chinese, Japanese) or skip the step."
                } else {
                    "No speech recognizer found. Install or update the Google app to enable it."
                },
                SystemAccess.speechAvailable(context), "Voice input", false, { SystemAccess.openVoiceInputSettings(context) },
            ),
            SetupItem(
                "Priority emails (optional)",
                "Notification access lets emails you choose open while locked. Set the rules under Settings > Priority emails.",
                SystemAccess.notificationAccess(context), "Open", false, { SystemAccess.openNotificationAccess(context) },
            ),
        )
    }

    SetupChecklist(
        nav,
        "Lingo Lock turns your phone into a practice gate: at the times you choose it asks you a few words " +
            "before other apps open. Calls, alarms, your calendar and the apps you allow keep working.",
        items,
    )
}
