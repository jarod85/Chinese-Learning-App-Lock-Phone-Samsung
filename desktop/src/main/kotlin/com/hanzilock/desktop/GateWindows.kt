package com.hanzilock.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanzilock.AppCore
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.quiz.SessionKind
import com.hanzilock.ui.lock.LockScreen
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.ScreenScaffold
import com.hanzilock.ui.quiz.QuizViewModel
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.GraphicsEnvironment

/**
 * The gate's windows while a session is on it: the words on the main screen, a plain cover on any other
 * screen. They float above other windows and hide while an allowed app is in front.
 */
@Composable
fun ApplicationScope.GateWindows(core: AppCore, gate: MacGate, ui: DesktopPlatformUi, keys: KeyShortcutRegistry) {
    val open by gate.open.collectAsState()
    val visible by gate.visible.collectAsState()
    if (!open) return
    val screens = remember {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val main = env.defaultScreenDevice
        (listOf(main) + env.screenDevices.filter { it != main }).map { it.defaultConfiguration.bounds }
    }
    screens.forEachIndexed { i, b ->
        key(b) {
            Window(
                onCloseRequest = {},   // only finishing the words (or the PIN) closes it
                visible = visible,
                title = "Lingo Lock - practice",
                undecorated = true,
                resizable = false,
                alwaysOnTop = true,
                focusable = i == 0,
                state = rememberWindowState(position = WindowPosition(b.x.dp, b.y.dp), size = DpSize(b.width.dp, b.height.dp)),
                onKeyEvent = keys::dispatch,
            ) {
                fun bringToFront() {
                    window.toFront()
                    if (i == 0) {
                        window.requestFocus()
                        val desktop = Desktop.getDesktop()
                        if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
                    }
                }
                LaunchedEffect(visible) { if (visible) bringToFront() }
                LaunchedEffect(Unit) { gate.toFront.collect { bringToFront() } }
                LingoLockContent(core, ui) {
                    if (i == 0) GateSession(gate, visible) else Cover()
                }
            }
        }
    }
}

@Composable
private fun GateSession(gate: MacGate, visible: Boolean) {
    val app = LocalAppCore.current
    val vm: QuizViewModel = viewModel(key = "gate") { QuizViewModel(app) }
    LaunchedEffect(Unit) {
        // A session finished on your other device may have unlocked this one already: sync first (briefly).
        withTimeoutOrNull(4_000) { app.sync.syncNow() }
        vm.start(SessionKind.LOCK)
    }
    // Back from an allowed app: the session may have been finished elsewhere meanwhile.
    LaunchedEffect(visible) { if (visible) vm.onHostResume() }
    // A whole screen is wide: the words stay a readable width in the middle.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 900.dp).fillMaxSize()) { LockScreen(vm = vm, onClose = gate::close) }
        }
    }
}

@Composable
private fun Cover() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            Text("Practice time - the words are on your main screen.", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Shortcuts to the allowed apps, under the words on the gate. */
@Composable
fun AllowedAppShortcuts(helper: MacHelper, allowList: MacAllowList) {
    val apps by produceState(emptyList<MacHelper.InstalledApp>()) {
        val allowed = allowList.allowedApps()
        value = helper.installedApps().filter { it.bundleId in allowed }
    }
    if (apps.isEmpty()) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            HorizontalDivider()
            Text(
                "Still available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(apps, key = { it.bundleId }) { a ->
                    TextButton(onClick = { runCatching { ProcessBuilder("/usr/bin/open", "-b", a.bundleId).start() } }) { Text(a.name) }
                }
            }
        }
    }
}

/** Settings > Allowed apps on the Mac: which apps stay usable while the gate is up. */
@Composable
fun DesktopAllowedAppsScreen(nav: Navigator, helper: MacHelper?, allowList: MacAllowList) {
    val app = LocalAppCore.current
    val apps by produceState<List<MacHelper.InstalledApp>?>(null) { value = helper?.installedApps().orEmpty() }
    var selected by remember { mutableStateOf(allowList.allowedApps()) }
    var query by remember { mutableStateOf("") }

    fun toggle(id: String, on: Boolean) {
        selected = if (on) selected + id else selected - id
        app.settings.allowedPackages = selected
    }

    ScreenScaffold("Allowed while locked", onBack = nav::back) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tick the apps you want to use while practice is due; the practice screen steps aside while one of " +
                            "them is in front. Everything else brings the practice screen back.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        selected = MacAllowList.DEFAULTS
                        app.settings.allowedPackages = null
                    }) { Text("Reset to defaults (Calendar, Clock, FaceTime, Messages, Zoom, Teams, Webex)") }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        helper == null -> Text("The list of apps needs the Mac app.")
                        apps == null -> Text("Looking for apps…")
                    }
                }
            }
            val q = query.trim().lowercase()
            items(apps.orEmpty().filter { q.isEmpty() || it.name.lowercase().contains(q) || it.bundleId.lowercase().contains(q) }, key = { it.bundleId }) { a ->
                val locked = a.bundleId in MacAllowList.ALWAYS
                val checked = locked || a.bundleId in selected
                ListItem(
                    modifier = Modifier.clickable(enabled = !locked) { toggle(a.bundleId, !checked) },
                    headlineContent = { Text(a.name) },
                    supportingContent = { Text(if (locked) "Always allowed" else a.bundleId, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Checkbox(checked = checked, enabled = !locked, onCheckedChange = { toggle(a.bundleId, it) }) },
                )
            }
        }
    }
}
