package com.hanzilock.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.speech.Speaker
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.SetupChecklist
import com.hanzilock.ui.main.SetupItem
import com.hanzilock.ui.main.greeting
import com.hanzilock.ui.main.rememberResumeCount
import kotlinx.coroutines.flow.MutableStateFlow

/** First-run checklist on the Mac: the PIN, then a voice and speech recognition for the pronunciation check. */
@Composable
fun DesktopSetupScreen(nav: Navigator, platform: DesktopPlatform) {
    val app = LocalAppCore.current
    val resumed = rememberResumeCount()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val tts by app.speaker.status.collectAsStateWithLifecycle()
    val permissions by (platform.permissions?.state ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()
    LaunchedEffect(resumed) { platform.permissions?.refresh() }

    val lang = remember(settingsVersion) { app.languages.active }
    val hasVoice = remember(resumed, settingsVersion, tts) { tts == Speaker.Status.READY && app.speaker.hasVoice(lang.javaLocale) }
    // Without the helper (Windows, Linux, a run from Gradle) there are no voices and no speech recognition.
    val items = if (platform.helper == null) {
        emptyList()
    } else {
        listOf(
            SetupItem(
                "Microphone and speech recognition",
                when {
                    permissions?.granted == true -> "Lingo Lock can hear you pronounce each word."
                    permissions?.denied == true -> "Turned off. Allow Lingo Lock under System Settings > Privacy & Security > " +
                        "Microphone and > Speech Recognition, then come back."
                    else -> "Used to hear you pronounce each word. macOS asks you once."
                },
                permissions?.granted == true, if (permissions?.denied == true) "Open" else "Allow", true,
                {
                    if (permissions?.denied == true) MacSettings.open(MacSettings.PRIVACY_MICROPHONE) else platform.permissions?.request()
                },
            ),
            SetupItem(
                "${lang.name} voice",
                when {
                    tts == Speaker.Status.STARTING -> "Checking the Mac's voices…"
                    hasVoice -> "Pronunciation audio is ready."
                    else -> "Add a ${lang.name} voice: System Settings > Accessibility > Spoken Content > System voice > " +
                        "Manage Voices… > ${lang.name}. The \"Premium\" and \"Enhanced\" voices sound best."
                },
                hasVoice, "Open", false, { MacSettings.open(MacSettings.SPOKEN_CONTENT) },
                "Play “${greeting(lang.code)}”" to { app.speaker.speak(greeting(lang.code), lang.javaLocale) },
            ),
        )
    }

    SetupChecklist(
        nav,
        "Lingo Lock on your Mac: your words, stories and dictionary, the same as on the phone. " +
            "The practice gate is off until you switch it on in Settings.",
        items,
    )
}

/** Opens panes of System Settings. */
object MacSettings {
    const val PRIVACY_MICROPHONE = "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
    const val SPOKEN_CONTENT = "x-apple.systempreferences:com.apple.preference.universalaccess?SpokenContent"

    fun open(url: String) {
        if (DesktopPaths.isMac) runCatching { ProcessBuilder("/usr/bin/open", url).start() }
    }
}
