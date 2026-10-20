package com.hanzilock.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.hanzilock.AppCore
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.platform.PlatformUi
import com.hanzilock.ui.common.AppIcons
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.main.AppRoot
import com.hanzilock.ui.theme.HanziTheme
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "Lingo Lock")
    val platform = DesktopPlatform()
    val core = AppCore(platform)
    core.start()
    platform.helper?.start()
    val gate = MacGate(core, platform.helper).also { it.start() }
    // Started at login while the lock is on: just the menu bar icon and the gate.
    val background = LaunchAgent.BACKGROUND in args

    application {
        val frame = remember { arrayOfNulls<Frame>(1) }
        val keys = remember { KeyShortcutRegistry() }
        val ui = remember { DesktopPlatformUi(core, platform, gate, keys) { frame[0] } }
        val gateKeys = remember { KeyShortcutRegistry() }
        val gateUi = remember { DesktopPlatformUi(core, platform, gate, gateKeys) { null } }
        var showMain by remember { mutableStateOf(!background) }
        var askQuit by remember { mutableStateOf(false) }
        val settingsVersion by core.settings.changes.collectAsState()
        val lockOn = remember(settingsVersion) { core.settings.lockEnabled && core.pin.isSet }

        // While the lock is on, quitting (Cmd+Q, the Dock, the menu bar icon) needs the master PIN.
        fun quit() {
            if (core.settings.lockEnabled && core.pin.isSet) {
                showMain = true
                askQuit = true
            } else {
                exitApplication()
            }
        }
        DisposableEffect(Unit) {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                    desktop.setQuitHandler { _, response ->
                        if (core.settings.lockEnabled && core.pin.isSet) {
                            response.cancelQuit()
                            SwingUtilities.invokeLater { quit() }
                        } else {
                            response.performQuit()
                        }
                    }
                }
                if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                    desktop.addAppEventListener(AppReopenedListener { SwingUtilities.invokeLater { showMain = true } })
                }
            }
            onDispose {}
        }

        if (lockOn) {
            Tray(
                icon = rememberVectorPainter(AppIcons.Book),
                tooltip = "Lingo Lock",
                onAction = { showMain = true },
                menu = {
                    Item("Open Lingo Lock", onClick = { showMain = true })
                    Item("Quit Lingo Lock…", onClick = ::quit)
                },
            )
        }

        Window(
            // With the lock on, closing the window keeps Lingo Lock running in the menu bar.
            onCloseRequest = { if (lockOn) showMain = false else exitApplication() },
            visible = showMain,
            title = "Lingo Lock",
            state = rememberWindowState(size = DpSize(1100.dp, 800.dp), position = WindowPosition(Alignment.Center)),
            onKeyEvent = keys::dispatch,
        ) {
            LaunchedEffect(Unit) {
                frame[0] = window
                window.minimumSize = Dimension(480, 600)
            }
            // Looks for a new build now and then (at most every few hours).
            LaunchedEffect(Unit) {
                while (true) {
                    core.updater.checkIfDue()
                    delay(30 * 60_000L)
                }
            }
            LingoLockContent(core, ui) {
                AppRoot()
                if (askQuit) {
                    PinDialog(
                        title = "Quit Lingo Lock?",
                        message = "The practice lock is on. Enter the master PIN to quit (it starts again when you log in).",
                        onDismiss = { askQuit = false },
                        onVerified = ::exitApplication,
                    )
                }
            }
        }

        GateWindows(core, gate, gateUi, gateKeys)
    }
}

/** The app's theme, with the shared [AppCore], the Mac's [PlatformUi] and a home for view models. */
@Composable
fun LingoLockContent(core: AppCore, ui: PlatformUi, content: @Composable () -> Unit) {
    val viewModels = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(viewModels) { onDispose { viewModels.viewModelStore.clear() } }
    CompositionLocalProvider(
        LocalAppCore provides core,
        LocalPlatformUi provides ui,
        LocalViewModelStoreOwner provides viewModels,
    ) {
        HanziTheme(content = content)
    }
}
