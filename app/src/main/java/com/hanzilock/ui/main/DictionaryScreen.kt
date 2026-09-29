package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.data.DictEntry
import com.hanzilock.data.DictState
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.theme.hanziStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** CC-CEDICT lookup (characters, pinyin with or without tones, or English) - add hits to your words. */
@Composable
fun DictionaryScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val state by app.dictionary.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val results by produceState(emptyList<DictEntry>(), query, state) {
        delay(250)
        value = withContext(Dispatchers.IO) { app.dictionary.search(query) }
    }
    val version by app.words.changes.collectAsStateWithLifecycle()
    val known by produceState(emptySet<String>(), version) {
        value = withContext(Dispatchers.IO) { app.words.list().map { it.hanzi }.toSet() }
    }

    ScreenScaffold("Dictionary") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("汉字, pinyin (xuexi / xue2xi2) or English") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when (val s = state) {
                is DictState.Importing -> Column(Modifier.padding(16.dp)) {
                    Text("Preparing the dictionary (first start only)… ${s.entries} entries", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                DictState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                DictState.Missing -> Text(
                    "No dictionary bundled. Run tools/update-cedict.ps1 and rebuild the app.",
                    modifier = Modifier.padding(16.dp),
                )
                is DictState.Failed -> Text("Dictionary error: ${s.message}", modifier = Modifier.padding(16.dp))
                is DictState.Ready -> if (query.isBlank()) {
                    Text(
                        "${s.entries} entries from CC-CEDICT. Tap + to add a word to your practice list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { e ->
                    DictRow(
                        entry = e,
                        inList = e.simplified in known,
                        onAdd = { nav.go(Route.WordEdit(null, e)) },
                        onSpeak = { app.speaker.speak(e.simplified, slow = true) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DictRow(entry: DictEntry, inList: Boolean, onAdd: () -> Unit, onSpeak: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.simplified, style = hanziStyle(22))
                if (entry.traditional != entry.simplified) {
                    Spacer(Modifier.width(6.dp))
                    Text("[${entry.traditional}]", style = hanziStyle(16), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(10.dp))
                Text(entry.pinyinMarked, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Text(entry.definitions.joinToString("; "), maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakButton(onClick = onSpeak)
                if (inList) {
                    Icon(Icons.Default.Check, contentDescription = "In your list", tint = MaterialTheme.colorScheme.primary)
                } else {
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add to my words") }
                }
            }
        },
    )
}
