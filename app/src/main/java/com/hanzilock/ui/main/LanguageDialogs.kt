package com.hanzilock.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanzilock.HanziLockApp
import com.hanzilock.core.CatalogLanguage
import com.hanzilock.core.LanguageProfile
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pick the language to practise; "Add a language…" opens the catalog. */
@Composable
fun LanguageDialog(onDismiss: () -> Unit, onAdd: () -> Unit) {
    val app = HanziLockApp.get(LocalContext.current)
    val active = app.settings.activeLanguage
    val rows by produceState(emptyList<Pair<LanguageProfile, Int>>()) {
        value = withContext(Dispatchers.IO) {
            app.languages.all().map { it to app.words.countPractising(it.code) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Language to practise") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows, key = { it.first.code }) { (lang, count) ->
                    ListItem(
                        modifier = Modifier.clickable { app.switchLanguage(lang.code); onDismiss() },
                        leadingContent = { RadioButton(selected = lang.code == active, onClick = { app.switchLanguage(lang.code); onDismiss() }) },
                        headlineContent = { Text(lang.native, style = termStyle(18, lang.locale)) },
                        supportingContent = { Text(if (count > 0) "${lang.name} · $count words switched on" else lang.name) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add a language…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Languages that can be added. Those with an open word pack download A1-B1 level sets
 * (about 2,000 words with meanings and example sentences); the others start empty so you can
 * import your own lists.
 */
@Composable
fun AddLanguageDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val app = HanziLockApp.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    val catalog = remember { app.languages.addable() }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }

    fun add(entry: CatalogLanguage) {
        busy = entry.profile.code
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { app.languagePacks.install(entry) { progress = it } }
            }
            busy = null
            result.onSuccess { n ->
                app.switchLanguage(entry.profile.code)
                onAdded(
                    if (n > 0) "Added ${entry.profile.name} with $n words (A1-B1). Switched to it."
                    else "Added ${entry.profile.name}. Import your own word lists in Words > Sets.",
                )
            }.onFailure { onAdded("Couldn't add ${entry.profile.name}: ${it.message}") }
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("Add a language") },
        text = {
            Column {
                if (busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress ?: "Starting…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(catalog, key = { it.profile.code }) { entry ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = busy == null) { add(entry) },
                            headlineContent = { Text("${entry.profile.name} · ${entry.profile.native}") },
                            supportingContent = {
                                Text(if (entry.packRepo != null) "Downloads A1-B1 word lists (≈2,000 words)" else "Starts empty - import your own lists")
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = busy == null, onClick = onDismiss) { Text("Close") } },
    )
}
