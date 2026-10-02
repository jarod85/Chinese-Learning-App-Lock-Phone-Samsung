package com.hanzilock.ui.main

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
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
import com.hanzilock.core.LanguageProfile
import com.hanzilock.data.DictEntry
import com.hanzilock.data.DictState
import com.hanzilock.data.Word
import com.hanzilock.data.WordScope
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.theme.hanziStyle
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pleco-style lookup. Chinese: the bundled CC-CEDICT (characters, pinyin with or without tones,
 * or English). Other languages: every word of the installed level sets, so you can find a word
 * from any level and add it to your own set.
 */
@Composable
fun DictionaryScreen(nav: Navigator) {
    val app = HanziLockApp.get(LocalContext.current)
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val lang = remember(settingsVersion) { app.languages.active }
    if (lang.isChinese) ChineseDictionary(nav) else SetDictionary(nav, lang)
}

@Composable
private fun ChineseDictionary(nav: Navigator) {
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
        value = withContext(Dispatchers.IO) {
            app.words.list("zh", scope = WordScope.Practising, limit = Int.MAX_VALUE).map { it.term }.toSet()
        }
    }

    ScreenScaffold("Dictionary · 中文") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query, "汉字, pinyin (xuexi / xue2xi2) or English") { query = it }
            when (val s = state) {
                is DictState.Importing -> Column(Modifier.padding(16.dp)) {
                    Text("Preparing the dictionary (first start only)… ${s.entries} entries", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                DictState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                DictState.Missing -> Text("No dictionary bundled. Run tools/update-cedict.ps1 and rebuild the app.", modifier = Modifier.padding(16.dp))
                is DictState.Failed -> Text("Dictionary error: ${s.message}", modifier = Modifier.padding(16.dp))
                is DictState.Ready -> if (query.isBlank()) {
                    Hint("${s.entries} entries from CC-CEDICT. Tap + to add a word to your own set (\"My words\").")
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { e ->
                    DictRow(
                        entry = e,
                        inList = e.simplified in known,
                        onAdd = { nav.go(Route.WordEdit(null, e)) },
                        onSpeak = { app.speaker.speak(e.simplified, app.languages.get("zh").javaLocale, slow = true) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SetDictionary(nav: Navigator, lang: LanguageProfile) {
    val app = HanziLockApp.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val version by app.words.changes.collectAsStateWithLifecycle()
    val results by produceState(emptyList<Word>(), query, version, lang.code) {
        delay(200)
        value = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) {
            app.words.list(lang.code, query = query, scope = WordScope.Everything, limit = 80)
        }
    }
    val mine by produceState(emptySet<Long>(), version, lang.code) {
        value = withContext(Dispatchers.IO) {
            app.words.list(lang.code, scope = WordScope.Practising, limit = Int.MAX_VALUE).map { it.id }.toSet()
        }
    }
    ScreenScaffold("Dictionary · ${lang.native}") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query, "${lang.native} or English") { query = it }
            if (query.isBlank()) {
                Hint("Search every ${lang.name} word in your level sets (including ones that are switched off). Tap + to add it to \"My words\".")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { w ->
                    ListItem(
                        modifier = Modifier.clickable { nav.go(Route.WordDetail(w.id)) },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.term, style = termStyle(20, lang.locale))
                                if (w.reading.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(w.readingDisplay, style = termStyle(15, lang.locale), color = MaterialTheme.colorScheme.primary)
                                }
                                if (w.tags.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(w.tags.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        supportingContent = { Text(w.meanings.joinToString("; "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SpeakButton(onClick = { app.speaker.speak(w.term, lang.javaLocale, slow = true) })
                                if (w.id in mine) {
                                    Icon(Icons.Default.Check, contentDescription = "Being practised", tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    IconButton(onClick = {
                                        scope.launch(Dispatchers.IO) { app.words.addToSet(app.words.myWordsSet(lang.code), w.id) }
                                    }) { Icon(Icons.Default.Add, contentDescription = "Add to My words") }
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
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
                    Icon(Icons.Default.Check, contentDescription = "Being practised", tint = MaterialTheme.colorScheme.primary)
                } else {
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add to my words") }
                }
            }
        },
    )
}
