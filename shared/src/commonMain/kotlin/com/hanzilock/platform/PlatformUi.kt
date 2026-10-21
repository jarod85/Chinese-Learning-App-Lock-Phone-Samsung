package com.hanzilock.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import com.hanzilock.AppCore
import com.hanzilock.ui.main.Navigator

/** Saves text to a file you choose; [write] runs on a background thread once you've picked where. */
fun interface FileSaver {
    fun save(suggestedName: String, write: () -> String, onDone: (Result<Unit>) -> Unit)
}

/** The parts of the UI that differ between the phone and the Mac. */
interface PlatformUi {
    /** How the screens refer to the device the gate locks ("phone", "Mac"). */
    val deviceName: String

    /** Priority emails (opening chosen email notifications while locked) exist on the phone only. */
    val hasPriorityEmail: Boolean

    /** Used with a mouse (pointing at a word looks it up) rather than a touch screen. */
    val hasMouse: Boolean

    /** Required setup steps that are still missing, in the order to do them. */
    fun setupProblems(): List<String>

    /** Something to tell you after the lock is switched on, e.g. a permission still missing. */
    fun lockEnabledWarning(): String?

    /** Shows the practice gate now ("Lock now (test)"). */
    fun showGateNow()

    /** "12 apps + phone, SMS & emergency": what stays usable while locked. */
    fun allowedAppsSummary(): String

    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    /**
     * Keyboard shortcuts while this is on screen, for keys no text field used (a computer's keyboard;
     * the phone ignores them). [onKey] returns true when it handled the key.
     */
    @Composable
    fun KeyShortcuts(onKey: (KeyEvent) -> Boolean)

    /** An action that runs [onGranted] once the microphone may be used (asking for it first if needed). */
    @Composable
    fun rememberMicAction(onGranted: () -> Unit): () -> Unit

    /** An action that lets you pick a text file (word list, set or backup). */
    @Composable
    fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit

    @Composable
    fun rememberFileSaver(): FileSaver

    /** Accepts a text file dragged onto the screen. */
    @Composable
    fun fileDropTarget(onFile: (PickedFile) -> Unit): Modifier

    @Composable
    fun SetupScreen(nav: Navigator)

    @Composable
    fun AllowedAppsScreen(nav: Navigator)

    @Composable
    fun PriorityEmailScreen(nav: Navigator)

    /** Shown above the words on the practice gate (priority emails on the phone). */
    @Composable
    fun LockScreenTop()

    /** Shown below the words on the practice gate (shortcuts to the allowed apps). */
    @Composable
    fun LockScreenBottom()
}

val LocalAppCore = staticCompositionLocalOf<AppCore> { error("No AppCore provided") }
val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { error("No PlatformUi provided") }
