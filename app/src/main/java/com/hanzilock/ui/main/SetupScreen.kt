package com.hanzilock.ui.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.speech.Speaker
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.common.SetPinDialog
import com.hanzilock.ui.theme.WinColor

private data class SetupItem(
    val title: String,
    val detail: String,
    val done: Boolean,
    val label: String,
    val required: Boolean,
    val action: () -> Unit,
    val extra: Pair<String, () -> Unit>? = null,
)

/** First-run checklist. Everything needed for the lock to work on a Samsung Galaxy phone. */
@Composable
fun SetupScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val resumed = rememberResumeCount()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val tts by app.speaker.status.collectAsStateWithLifecycle()
    var setPin by remember { mutableStateOf(false) }
    var confirmOldPin by remember { mutableStateOf(false) }
    var micAsked by remember { mutableIntStateOf(0) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micAsked++ }

    val items = remember(resumed, settingsVersion, tts, micAsked) {
        listOf(
            SetupItem(
                "Master PIN",
                "Your emergency exit: ends a session early, pauses the lock, protects settings.",
                app.pin.isSet, if (app.pin.isSet) "Change" else "Set PIN", true,
                { if (app.pin.isSet) confirmOldPin = true else setPin = true },
            ),
            SetupItem(
                "Microphone",
                "Used to hear you pronounce each word.",
                SystemAccess.micGranted(context), "Allow", true, { mic.launch(Manifest.permission.RECORD_AUDIO) },
            ),
            SetupItem(
                "Practice gate (Accessibility)",
                "Settings > Accessibility > Installed apps > HanziLock practice gate > On.\n" +
                    "If the switch is greyed out (\"Restricted setting\"): open App info, tap ⋮ (top right) > " +
                    "\"Allow restricted settings\", then try again.",
                SystemAccess.accessibilityEnabled(context), "Open", true,
                { SystemAccess.openAccessibilitySettings(context) },
                "App info" to { SystemAccess.openAppInfo(context) },
            ),
            SetupItem(
                "Run in the background",
                "Stops One UI from putting HanziLock to sleep. Also add it under Settings > Battery > " +
                    "Background usage limits > Never sleeping apps.",
                SystemAccess.batteryUnrestricted(context), "Allow", true, { SystemAccess.requestBatteryExemption(context) },
            ),
            SetupItem(
                "Appear on top (recommended)",
                "Lets the practice screen jump in front of other apps more reliably.",
                SystemAccess.canDrawOverlays(context), "Open", false, { SystemAccess.openOverlaySettings(context) },
            ),
            SetupItem(
                "Chinese voice",
                when (tts) {
                    Speaker.Status.READY -> "Pronunciation audio is ready."
                    Speaker.Status.NO_CHINESE_VOICE -> "Install a Chinese (Mandarin) voice: Settings > General management > " +
                        "Text-to-speech > preferred engine (Samsung or Google) > ⚙ > Install voice data > Chinese."
                    Speaker.Status.STARTING -> "Checking the text-to-speech engine…"
                    Speaker.Status.UNAVAILABLE -> "No text-to-speech engine found. Install Google Speech Services from the Play Store."
                },
                tts == Speaker.Status.READY, "Settings", false, { SystemAccess.openTtsSettings(context) },
                "Play 你好" to { app.speaker.speak("你好") },
            ),
            SetupItem(
                "Chinese speech recognition",
                if (SystemAccess.speechAvailable(context)) {
                    "Available. It works best online; for offline use download Chinese (Mandarin) in Google's speech " +
                        "recognition settings. Without it you can type pinyin instead."
                } else {
                    "No speech recognizer found - you'll type pinyin instead. Install or update the Google app to enable it."
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

    ScreenScaffold("Setup", onBack = if (nav.stack.size > 1) nav::back else null) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "HanziLock turns your phone into a practice gate: at the times you choose it asks you a few words " +
                    "before other apps open. Calls, alarms, your calendar and the apps you allow keep working.",
                style = MaterialTheme.typography.bodyMedium,
            )
            items.forEach { SetupRow(it) }
            Button(
                onClick = { nav.tab(Route.Home) },
                enabled = app.pin.isSet,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (app.pin.isSet) "Done" else "Set a PIN to continue") }
        }
    }

    if (confirmOldPin) {
        PinDialog(
            title = "Current PIN",
            message = "Enter your current master PIN to change it.",
            onDismiss = { confirmOldPin = false },
            onVerified = { confirmOldPin = false; setPin = true },
        )
    }
    if (setPin) {
        SetPinDialog(onDismiss = { setPin = false }, onSet = { pin ->
            app.pin.setPin(pin)
            setPin = false
        })
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = if (item.done) WinColor else if (item.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (item.done) "✓" else if (item.required) "!" else "·", color = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!item.done || item.label == "Change") FilledTonalButton(onClick = item.action) { Text(item.label) }
                    item.extra?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                }
            }
        }
    }
}
