package com.hanzilock.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hanzilock.HanziLockApp
import com.hanzilock.lock.LockActivity
import com.hanzilock.platform.FileSaver
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.platform.PickedFile
import com.hanzilock.platform.PlatformUi
import com.hanzilock.ui.lock.AllowedAppShortcuts
import com.hanzilock.ui.lock.PriorityEmails
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.SystemAccess
import com.hanzilock.ui.theme.HanziTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's theme, with the shared [com.hanzilock.AppCore] and the phone's [PlatformUi] available to every screen. */
@Composable
fun LingoLockContent(app: HanziLockApp, content: @Composable () -> Unit) {
    val platformUi = remember(app) { AndroidPlatformUi(app) }
    CompositionLocalProvider(LocalAppCore provides app.core, LocalPlatformUi provides platformUi) {
        HanziTheme(content = content)
    }
}

private class AndroidPlatformUi(private val app: HanziLockApp) : PlatformUi {
    override val deviceName = "phone"
    override val hasPriorityEmail = true
    override val hasMouse = false

    override fun setupProblems(): List<String> = buildList {
        if (!app.pin.isSet) add("Set a master PIN")
        if (!SystemAccess.accessibilityEnabled(app)) add("Turn on the practice gate (Accessibility)")
        if (!SystemAccess.micGranted(app)) add("Allow the microphone for the pronunciation check")
        if (!SystemAccess.batteryUnrestricted(app)) add("Let Lingo Lock run in the background (battery)")
    }

    override fun lockEnabledWarning(): String? =
        if (SystemAccess.accessibilityEnabled(app)) null else "Also turn on the practice gate in Accessibility settings."

    override fun showGateNow() = LockActivity.launch(app)

    override fun allowedAppsSummary() = "${app.allowList.allowedApps().size} apps + phone, SMS & emergency"

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)

    @Composable
    override fun KeyShortcuts(onKey: (KeyEvent) -> Boolean) = Unit

    @Composable
    override fun rememberMicAction(onGranted: () -> Unit): () -> Unit {
        val context = LocalContext.current
        val granted by rememberUpdatedState(onGranted)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) granted()
        }
        return {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                granted()
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    @Composable
    override fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val picked by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch { read(context, uri)?.let(picked) }
        }
        return { launcher.launch(arrayOf("*/*")) }
    }

    @Composable
    override fun rememberFileSaver(): FileSaver {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val pending = remember { PendingSave() }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val write = pending.write ?: return@rememberLauncherForActivityResult
            val onDone = pending.onDone ?: return@rememberLauncherForActivityResult
            pending.write = null
            pending.onDone = null
            if (uri != null) scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = write()
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                            ?: error("Couldn't write the file")
                        Unit
                    }
                }
                onDone(result)
            }
        }
        return remember(launcher) {
            FileSaver { name, write, onDone ->
                pending.write = write
                pending.onDone = onDone
                launcher.launch(name)
            }
        }
    }

    /** Accepts a .csv / .txt file dragged onto the screen (split screen with My Files, pop-up view, DeX). */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun fileDropTarget(onFile: (PickedFile) -> Unit): Modifier {
        val context = LocalContext.current
        val activity = context as? Activity
        val scope = rememberCoroutineScope()
        val dropped by rememberUpdatedState(onFile)
        val target = remember(activity) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val drag = event.toAndroidDragEvent()
                    runCatching { activity?.requestDragAndDropPermissions(drag) }
                    val uri = drag.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return false
                    scope.launch { read(context, uri)?.let(dropped) }
                    return true
                }
            }
        }
        return Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { e -> e.mimeTypes().any { it.startsWith("text/") || it == "application/json" || it == "application/octet-stream" } },
            target = target,
        )
    }

    @Composable
    override fun SetupScreen(nav: Navigator) = com.hanzilock.ui.main.SetupScreen(nav)

    @Composable
    override fun AllowedAppsScreen(nav: Navigator) = com.hanzilock.ui.main.AllowedAppsScreen(nav)

    @Composable
    override fun PriorityEmailScreen(nav: Navigator) = com.hanzilock.ui.main.PriorityEmailScreen(nav)

    @Composable
    override fun LockScreenTop() = PriorityEmails()

    @Composable
    override fun LockScreenBottom() = AllowedAppShortcuts()

    private class PendingSave {
        var write: (() -> String)? = null
        var onDone: ((Result<Unit>) -> Unit)? = null
    }
}

/** Reads a picked, dropped or shared file; null (with a message) if it can't be opened. */
suspend fun readPickedFile(context: Context, uri: Uri): PickedFile? = read(context, uri)

private suspend fun read(context: Context, uri: Uri): PickedFile? {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Couldn't open the file")
            PickedFile(name, text)
        }.getOrNull()
    }
    if (file == null) Toast.makeText(context, "Couldn't open the file.", Toast.LENGTH_LONG).show()
    return file
}
