package com.hanzilock.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.hanzilock.AppCore
import com.hanzilock.platform.FileSaver
import com.hanzilock.platform.PickedFile
import com.hanzilock.platform.PlatformUi
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JOptionPane

/** Window-level keyboard shortcuts: the newest handler on screen gets each key first. */
class KeyShortcutRegistry {
    private val handlers = ArrayList<(KeyEvent) -> Boolean>()

    fun add(handler: (KeyEvent) -> Boolean) {
        handlers += handler
    }

    fun remove(handler: (KeyEvent) -> Boolean) {
        handlers -= handler
    }

    fun dispatch(event: KeyEvent): Boolean = handlers.asReversed().toList().any { it(event) }
}

/** The Mac's [PlatformUi]: Esc for back, Finder's open and save panels, files dropped onto the window. */
class DesktopPlatformUi(
    private val core: AppCore,
    private val platform: DesktopPlatform,
    private val gate: MacGate,
    private val keys: KeyShortcutRegistry,
    private val window: () -> Frame?,
) : PlatformUi {
    override val deviceName = if (DesktopPaths.isMac) "Mac" else "computer"
    override val hasPriorityEmail = false
    override val hasMouse = true

    override fun setupProblems(): List<String> = buildList {
        if (!core.pin.isSet) add("Set a master PIN")
    }

    override fun lockEnabledWarning(): String? =
        if (DesktopPaths.installedApp != null) "Lingo Lock now starts when you log in, so the practice gate works all day." else null

    override fun showGateNow() = gate.refresh()

    override fun allowedAppsSummary() = "${gate.allowList.allowedApps().size} apps"

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val active by rememberUpdatedState(enabled)
        val back by rememberUpdatedState(onBack)
        KeyShortcuts { e ->
            if (active && e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                back()
                true
            } else {
                false
            }
        }
    }

    @Composable
    override fun KeyShortcuts(onKey: (KeyEvent) -> Boolean) {
        val current by rememberUpdatedState(onKey)
        DisposableEffect(keys) {
            val handler: (KeyEvent) -> Boolean = { current(it) }
            keys.add(handler)
            onDispose { keys.remove(handler) }
        }
    }

    /** macOS asks for the microphone itself the first time you speak. */
    @Composable
    override fun rememberMicAction(onGranted: () -> Unit): () -> Unit {
        val granted by rememberUpdatedState(onGranted)
        return { granted() }
    }

    @Composable
    override fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val picked by rememberUpdatedState(onPicked)
        return {
            val dialog = FileDialog(window(), "Choose a word list, set or backup", FileDialog.LOAD)
            dialog.isVisible = true
            dialog.files.firstOrNull()?.let { file -> scope.launch { read(file)?.let(picked) } }
        }
    }

    @Composable
    override fun rememberFileSaver(): FileSaver {
        val scope = rememberCoroutineScope()
        return remember(scope) {
            FileSaver { name, write, onDone ->
                val dialog = FileDialog(window(), "Save", FileDialog.SAVE).apply { file = name }
                dialog.isVisible = true
                val file = dialog.files.firstOrNull() ?: return@FileSaver
                scope.launch {
                    onDone(withContext(Dispatchers.IO) { runCatching { file.writeText(write(), Charsets.UTF_8) } })
                }
            }
        }
    }

    /** Accepts a .csv / .txt file dragged from Finder onto the screen. */
    @OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
    @Composable
    override fun fileDropTarget(onFile: (PickedFile) -> Unit): Modifier {
        val scope = rememberCoroutineScope()
        val dropped by rememberUpdatedState(onFile)
        val target = remember(scope) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val t = event.awtTransferable
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                    val file = (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.firstOrNull() as? File ?: return false
                    scope.launch { read(file)?.let(dropped) }
                    return true
                }
            }
        }
        return Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { e -> e.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
            target = target,
        )
    }

    @Composable
    override fun SetupScreen(nav: Navigator) = DesktopSetupScreen(nav, platform)

    @Composable
    override fun AllowedAppsScreen(nav: Navigator) = DesktopAllowedAppsScreen(nav, platform.helper, gate.allowList)

    @Composable
    override fun PriorityEmailScreen(nav: Navigator) {
        ScreenScaffold("Priority emails", onBack = nav::back) { padding ->
            Text("Priority emails are a phone feature. On the Mac, add your email app to the allowed apps.", Modifier.padding(padding).padding(16.dp))
        }
    }

    @Composable
    override fun LockScreenTop() = Unit

    @Composable
    override fun LockScreenBottom() {
        platform.helper?.let { AllowedAppShortcuts(it, gate.allowList) }
    }

    private suspend fun read(file: File): PickedFile? {
        val picked = withContext(Dispatchers.IO) { runCatching { PickedFile(file.name, file.readText(Charsets.UTF_8)) }.getOrNull() }
        if (picked == null) JOptionPane.showMessageDialog(window(), "Couldn't open ${file.name}.", "Lingo Lock", JOptionPane.WARNING_MESSAGE)
        return picked
    }
}
