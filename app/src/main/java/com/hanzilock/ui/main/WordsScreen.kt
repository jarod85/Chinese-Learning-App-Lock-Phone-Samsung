package com.hanzilock.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.data.Word
import com.hanzilock.data.WordFilter
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.hanziStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Your word registry: the words practice sessions are drawn from. */
@Composable
fun WordsScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WordFilter.ALL) }
    val version by app.words.changes.collectAsStateWithLifecycle()
    val words by produceState(emptyList<Word>(), query, filter, version) {
        value = withContext(Dispatchers.IO) { app.words.list(filter, query) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.importExport.import(uri) } }
            val message = result.fold(
                onSuccess = { r ->
                    when {
                        r.restoredBackup -> "Backup restored (${r.added} words)."
                        else -> buildString {
                            append("Added ${r.added} words")
                            if (r.skipped > 0) append(", ${r.skipped} already in your list")
                            if (r.notFound.isNotEmpty()) append(". Not in the dictionary: ${r.notFound.take(5).joinToString(" ")}")
                        }
                    }
                },
                onFailure = { "Import failed: ${it.message}" },
            )
            snackbar.showSnackbar(message)
        }
    }

    ScreenScaffold(
        title = "Words (${words.size})",
        actions = { TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import") } },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.go(Route.WordEdit(null)) }) { Icon(Icons.Default.Add, contentDescription = "Add word") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("汉字, pinyin or English") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WordFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            if (words.isEmpty()) {
                Text(
                    if (query.isBlank() && filter == WordFilter.ALL) "No words yet. Add some with +, the Dictionary tab or Import (Pleco flashcard export, CSV or a list of words)."
                    else "Nothing matches.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(words, key = { it.id }) { w ->
                    WordRow(w) { nav.go(Route.WordDetail(w.id)) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun WordRow(w: Word, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.hanzi, style = hanziStyle(22))
                Spacer(Modifier.width(10.dp))
                Text(w.pinyinDisplay, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = { Text(w.meanings.joinToString("; "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                when {
                    !w.enabled -> Text("off", style = MaterialTheme.typography.labelSmall)
                    w.isNew -> Text("new", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    else -> {
                        Text("✓${w.wins}", style = MaterialTheme.typography.labelSmall, color = WinColor)
                        Text("✗${w.losses}", style = MaterialTheme.typography.labelSmall, color = LossColor)
                    }
                }
            }
        },
    )
}
