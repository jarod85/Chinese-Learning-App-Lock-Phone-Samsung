package com.hanzilock.ui.main

import android.net.Uri
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
import com.hanzilock.data.Word
import com.hanzilock.data.WordFilter
import com.hanzilock.data.WordScope
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Your words in the active language: the ones being practised, one set, or everything. */
@Composable
fun WordsScreen(nav: Navigator, snackbar: SnackbarHostState, setScope: Route.SetWords? = null) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val lang = remember(settingsVersion) { app.languages.active }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WordFilter.ALL) }
    var everything by rememberSaveable { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val version by app.words.changes.collectAsStateWithLifecycle()
    val wordScope: WordScope = when {
        setScope != null -> WordScope.InSet(setScope.setId)
        everything -> WordScope.Everything
        else -> WordScope.Practising
    }
    val setLang = if (setScope != null) remember(setScope.setId, version) { app.words.set(setScope.setId)?.lang } else null
    val listLang = setLang ?: lang.code
    val words by produceState(emptyList<Word>(), query, filter, wordScope, version, listLang) {
        delay(150)
        value = withContext(Dispatchers.IO) { app.words.list(listLang, filter, query, wordScope) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) importUri = it }
    val profile = app.languages.get(listLang)

    ScreenScaffold(
        title = setScope?.name ?: "Words · ${lang.name}",
        onBack = if (setScope != null) nav::back else null,
        actions = {
            if (setScope == null) TextButton(onClick = { nav.go(Route.Sets) }) { Text("Sets") }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import") }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.go(Route.WordEdit(null, setId = setScope?.setId)) }) {
                Icon(Icons.Default.Add, contentDescription = "Add word")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().fileDropTarget { importUri = it }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(if (lang.isChinese) "汉字, pinyin or English" else "${lang.native} or English") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (setScope == null) {
                    FilterChip(selected = !everything, onClick = { everything = false }, label = { Text("Practising") })
                    FilterChip(selected = everything, onClick = { everything = true }, label = { Text("All sets") })
                }
                WordFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            Text(
                "${words.size}${if (words.size >= 1500) "+" else ""} words",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (words.isEmpty()) {
                Text(
                    if (query.isBlank() && filter == WordFilter.ALL && !everything && setScope == null) {
                        "No sets switched on for ${lang.name}. Open Sets to choose levels, or import your own list."
                    } else {
                        "Nothing matches."
                    },
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(words, key = { it.id }) { w ->
                    WordRow(w, profile) { nav.go(Route.WordDetail(w.id)) }
                    HorizontalDivider()
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
}

@Composable
fun WordRow(w: Word, profile: LanguageProfile?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.term, style = termStyle(if (profile?.cjk != false) 22 else 19, profile?.locale), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (w.reading.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(w.readingDisplay, style = termStyle(15, profile?.locale), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        supportingContent = {
            Text(
                if (w.needsDetails) "needs a meaning - tap to add" else w.meanings.joinToString("; "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (w.needsDetails) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
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
