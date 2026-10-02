package com.hanzilock.ui.main

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanzilock.HanziLockApp
import com.hanzilock.data.ImportExport
import com.hanzilock.data.ImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Human summary of an import. */
fun importMessage(app: HanziLockApp, result: Result<ImportExport.ImportResult>): String = result.fold(
    onSuccess = { r ->
        if (r.restoredBackup) {
            "Backup restored (${r.total} words)."
        } else {
            buildString {
                append("Set \"${r.setName}\": ${r.total} words")
                if (r.linked > 0) append(" (${r.linked} you already had)")
                append(".")
                if (r.incomplete.isNotEmpty()) {
                    append(" ${r.incomplete.size} still need a meaning (${r.incomplete.take(4).joinToString(" ")}")
                    append(if (r.incomplete.size > 4) "…)" else ")")
                    append(if (app.settings.claudeApiKey.isBlank()) " - add a Claude key in Settings to fill them in automatically." else ".")
                }
                if (r.lang != app.settings.activeLanguage) append(" Switch to ${app.languages.get(r.lang).name} on Home to practise it.")
            }
        }
    },
    onFailure = { "Import failed: ${it.message}" },
)

/**
 * Import a file as a new word set: choose its name and language. Meanings, readings and example
 * sentences are filled in from the dictionary / sentence corpus, and by Claude if a key is set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportDialog(uri: Uri, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(app.settings.activeLanguage) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    val languages = remember { app.languages.all() }

    LaunchedEffect(uri) {
        val (suggested, detected) = withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8).take(4000) }
            }.getOrNull().orEmpty()
            app.importExport.suggestedSetName(uri) to detectLanguage(text, app.settings.activeLanguage)
        }
        if (name.isEmpty()) name = suggested
        lang = detected
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Import words") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The words become their own set, so they don't mix with the built-in levels. " +
                        "Words you already have keep their progress; readings, meanings and example sentences are filled in automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Set name") },
                    singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth(),
                )
                Text("Language", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.forEach { l ->
                        FilterChip(selected = lang == l.code, onClick = { if (!running) lang = l.code }, label = { Text(l.native) })
                    }
                }
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !running && name.isNotBlank(), onClick = {
                running = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { app.importExport.import(uri, lang, name.trim()) { p -> progress = p } }
                    }
                    running = false
                    onDone(importMessage(app, result))
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Guess a word list's language from its script (Chinese / Japanese / Korean), else the active one. */
fun detectLanguage(text: String, fallback: String): String = when {
    text.any { it in '぀'..'ヿ' } -> "ja"
    text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } -> "ko"
    ImportParser.hasCjk(text) -> if (fallback == "yue") "yue" else "zh"
    else -> fallback
}

/**
 * Accepts a .csv / .txt file dragged onto the screen (split screen with My Files, pop-up view, DeX).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.fileDropTarget(onFile: (Uri) -> Unit): Modifier {
    val activity = LocalContext.current as? Activity
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                runCatching { activity?.requestDragAndDropPermissions(drag) }
                val uri = drag.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return false
                onFile(uri)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { e -> e.mimeTypes().any { it.startsWith("text/") || it == "application/json" || it == "application/octet-stream" } },
        target = target,
    )
}
