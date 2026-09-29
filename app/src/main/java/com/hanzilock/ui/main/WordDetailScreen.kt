package com.hanzilock.ui.main

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.data.Attempt
import com.hanzilock.data.Word
import com.hanzilock.quiz.SentenceTiles
import com.hanzilock.ui.common.LabeledValue
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.common.formatDateTime
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.hanziStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WordDetailScreen(nav: Navigator, id: Long) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()
    val version by app.words.changes.collectAsStateWithLifecycle()
    val word by produceState<Word?>(null, id, version) { value = withContext(Dispatchers.IO) { app.words.get(id) } }
    val attempts by produceState(emptyList<Attempt>(), id, version) {
        value = withContext(Dispatchers.IO) { app.words.attemptsFor(id) }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = word?.hanzi.orEmpty(),
        onBack = nav::back,
        actions = {
            IconButton(onClick = { nav.go(Route.WordEdit(id)) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        },
    ) { padding ->
        val w = word ?: return@ScreenScaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.hanzi, style = hanziStyle(56))
                        w.traditional?.let { Text("Traditional: $it", style = hanziStyle(16)) }
                        Text(w.pinyinDisplay, style = MaterialTheme.typography.headlineSmall)
                    }
                    SpeakButton(onClick = { app.speaker.speak(w.hanzi, slow = true) })
                }
            }
            item {
                SectionTitle("Meanings")
                w.meanings.forEachIndexed { i, m -> Text("${i + 1}. $m", style = MaterialTheme.typography.bodyLarge) }
                if (w.altMeanings.isNotEmpty()) {
                    Text(
                        "Also accepted: ${w.altMeanings.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (w.examples.isNotEmpty()) {
                item { SectionTitle("Examples") }
                items(w.examples) { ex ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(SentenceTiles.display(ex.zh), style = hanziStyle(20), modifier = Modifier.weight(1f))
                                SpeakButton(onClick = { app.speaker.speak(SentenceTiles.display(ex.zh)) })
                            }
                            ex.pinyin?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            ex.en?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            item {
                SectionTitle("Progress")
                LabeledValue("Right / missed", "${w.wins} / ${w.losses}")
                LabeledValue("Review level", "${w.box} of 8")
                LabeledValue("Next review", if (w.isNew) "not tested yet" else formatDateTime(w.dueAt))
                if (w.tags.isNotEmpty()) LabeledValue("Tags", w.tags.joinToString(", "))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Include in practice", modifier = Modifier.weight(1f))
                    Switch(checked = w.enabled, onCheckedChange = { on -> scope.launch(Dispatchers.IO) { app.words.setEnabled(w.id, on) } })
                }
            }
            item { SectionTitle("History") }
            if (attempts.isEmpty()) item { Text("Not tested yet.", style = MaterialTheme.typography.bodyMedium) }
            items(attempts, key = { it.id }) { a ->
                AttemptRow(a, showHanzi = false)
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${word?.hanzi.orEmpty()}?") },
            text = { Text("It's removed from your list; its practice history stays in the stats.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { app.words.delete(id) }
                        nav.back()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** One logged attempt: result, which check failed and what you answered. */
@Composable
fun AttemptRow(a: Attempt, showHanzi: Boolean, onClick: (() -> Unit)? = null) {
    val details = buildList {
        a.heard?.let { add("Said: $it") }
        a.meaningAnswer?.let { add("Meaning: $it") }
        a.sentenceAnswer?.let { add("Sentence: $it") }
        a.feedback?.let { add(it) }
    }
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = {
            Text(if (a.win) "✓" else "✗", color = if (a.win) WinColor else LossColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showHanzi) {
                    Text(a.hanzi, style = hanziStyle(20))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (a.win) "Right" else "Missed the ${a.failedPart?.label ?: "answer"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        supportingContent = if (details.isEmpty()) null else {
            { Text(details.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = { Text(formatDateTime(a.at), style = MaterialTheme.typography.labelSmall) },
    )
}
