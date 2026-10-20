package com.hanzilock.ui.main

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.data.WordSet
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.platform.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The active language's word sets: switch levels / your own sets on and off for practice. */
@Composable
fun SetsScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val app = LocalAppCore.current
    val scope = rememberCoroutineScope()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val version by app.words.changes.collectAsStateWithLifecycle()
    val installing by app.setSync.progress.collectAsStateWithLifecycle()
    val lang = remember(settingsVersion) { app.languages.active }
    val sets by produceState(emptyList<WordSet>(), lang.code, version) { value = withContext(Dispatchers.IO) { app.words.sets(lang.code) } }
    var importFile by remember { mutableStateOf<PickedFile?>(null) }
    var renaming by remember { mutableStateOf<WordSet?>(null) }
    var deleting by remember { mutableStateOf<WordSet?>(null) }
    var combining by remember { mutableStateOf(false) }
    val pickFile = LocalPlatformUi.current.rememberFilePicker { importFile = it }
    val saver = LocalPlatformUi.current.rememberFileSaver()
    fun export(set: WordSet) = saver.save(
        "${set.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "set" }}.json",
        write = { app.importExport.exportSet(set.id) },
        onDone = { r ->
            scope.launch {
                snackbar.showSnackbar(r.fold({ "Exported \"${set.name}\" - drop the file on add-words.cmd in the repo to bundle it with the app." }, { "Export failed: ${it.message}" }))
            }
        },
    )

    ScreenScaffold(
        title = "Sets · ${lang.name}",
        onBack = nav::back,
        actions = {
            if (sets.size >= 2) TextButton(onClick = { combining = true }) { Text("Combine") }
            TextButton(onClick = pickFile) { Text("Import") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().fileDropTarget { importFile = it }) {
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
                        onExport = { export(set) },
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

    importFile?.let { file ->
        ImportDialog(file, onDismiss = { importFile = null }, onDone = { msg ->
            importFile = null
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
    if (combining) {
        CombineSetsDialog(sets, onDismiss = { combining = false }, onCombine = { name, picked, switchOff ->
            combining = false
            scope.launch {
                val created = withContext(Dispatchers.IO) {
                    val id = app.words.combineSets(lang.code, name, picked, switchOff)
                    app.words.set(id)
                }
                snackbar.showSnackbar("Combined into \"${created?.name ?: name}\" - ${created?.wordCount ?: 0} words")
            }
        })
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

/** Pick two or more sets and a name; the new set holds all their words. */
@Composable
private fun CombineSetsDialog(sets: List<WordSet>, onDismiss: () -> Unit, onCombine: (String, Set<Long>, Boolean) -> Unit) {
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var name by remember { mutableStateOf("") }
    var switchOff by remember { mutableStateOf(true) }
    val suggested = sets.filter { it.id in picked }.joinToString(" + ") { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Combine sets") },
        text = {
            Column {
                Text(
                    "Makes a new set of your own with every word of the sets you tick. Words keep their progress, and the originals stay.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text(suggested.ifEmpty { "Combined set" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    sets.forEach { set ->
                        val checked = set.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (checked) picked - set.id else picked + set.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { picked = if (it) picked + set.id else picked - set.id })
                            Text("${set.name} (${set.wordCount})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { switchOff = !switchOff }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = switchOff, onCheckedChange = { switchOff = it })
                    Text("Switch the originals off", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.size >= 2, onClick = { onCombine(name.ifBlank { suggested }, picked, switchOff) }) { Text("Combine") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
