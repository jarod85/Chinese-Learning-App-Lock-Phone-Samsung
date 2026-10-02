package com.hanzilock.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.data.WordSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The active language's word sets: switch levels / your own sets on and off for practice. */
@Composable
fun SetsScreen(nav: Navigator, snackbar: SnackbarHostState, incoming: Uri? = null) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val version by app.words.changes.collectAsStateWithLifecycle()
    val installing by app.setSync.progress.collectAsStateWithLifecycle()
    val lang = remember(settingsVersion) { app.languages.active }
    val sets by produceState(emptyList<WordSet>(), lang.code, version) { value = withContext(Dispatchers.IO) { app.words.sets(lang.code) } }
    var importUri by remember { mutableStateOf(incoming) }
    var renaming by remember { mutableStateOf<WordSet?>(null) }
    var deleting by remember { mutableStateOf<WordSet?>(null) }
    var exporting by remember { mutableStateOf<WordSet?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) importUri = it }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val set = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.importExport.exportSet(uri, set.id) } }
            snackbar.showSnackbar(r.fold({ "Exported \"${set.name}\" - drop the file on add-words.cmd in the repo to bundle it with the app." }, { "Export failed: ${it.message}" }))
        }
    }

    ScreenScaffold(
        title = "Sets · ${lang.name}",
        onBack = nav::back,
        actions = { TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import") } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().fileDropTarget { importUri = it }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Only switched-on sets are used for practice. A word that's in several sets shares one progress record.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    installing?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                items(sets, key = { it.id }) { set ->
                    SetRow(
                        set = set,
                        onOpen = { nav.go(Route.SetWords(set.id, set.name)) },
                        onToggle = { on -> scope.launch(Dispatchers.IO) { app.words.setSetEnabled(set.id, on) } },
                        onRename = { renaming = set },
                        onExport = { exporting = set; exporter.launch("${set.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "set" }}.json") },
                        onDelete = { deleting = set },
                    )
                    HorizontalDivider()
                }
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Add your own words", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Tap Import, share a .csv / .txt file to Lingo Lock, or drag one onto this screen (split screen with My Files). " +
                                    "A plain list of words is enough - one per line or comma-separated.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    importUri?.let { uri ->
        ImportDialog(uri, onDismiss = { importUri = null }, onDone = { msg ->
            importUri = null
            scope.launch { snackbar.showSnackbar(msg) }
        })
    }
    renaming?.let { set ->
        var name by remember(set.id) { mutableStateOf(set.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename set") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = null
                    scope.launch(Dispatchers.IO) { app.words.renameSet(set.id, name) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { set ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${set.name}\"?") },
            text = { Text("Its words are removed unless they're also in another set. Your practice history stays in the stats.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch(Dispatchers.IO) { app.words.deleteSet(set.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetRow(
    set: WordSet,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(set.name, style = MaterialTheme.typography.titleMedium)
                set.level?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = { Text("${set.wordCount} words · ${set.seenCount} practised") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = set.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export") }, onClick = { menu = false; onExport() })
                        if (!set.bundled) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        },
    )
}
