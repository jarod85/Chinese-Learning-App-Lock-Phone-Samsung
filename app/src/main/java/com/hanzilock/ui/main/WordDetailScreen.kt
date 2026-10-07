package com.hanzilock.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import com.hanzilock.data.WordSet
import com.hanzilock.quiz.SentenceTiles
import com.hanzilock.ui.common.LabeledValue
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.common.formatDateTime
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.termStyle
import com.hanzilock.ui.theme.wordDisplaySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
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
    val sets by produceState(emptyList<WordSet>(), id, version) { value = withContext(Dispatchers.IO) { app.words.setsOf(id) } }
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = word?.term.orEmpty(),
        onBack = nav::back,
        actions = {
            IconButton(onClick = { nav.go(Route.WordEdit(id)) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        },
    ) { padding ->
        val w = word ?: return@ScreenScaffold
        val profile = app.languages.get(w.lang)
        val speak = { text: String, slow: Boolean -> app.speaker.speak(SentenceTiles.display(text, profile.spaced), profile.javaLocale, slow) }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.term, style = termStyle(wordDisplaySize(w.term, profile.cjk) - 16, profile.locale))
                        w.traditional?.let { Text("Traditional: $it", style = termStyle(16, profile.locale)) }
                        if (w.reading.isNotBlank()) Text(w.readingDisplay, style = termStyle(22, profile.locale))
                    }
                    SpeakButton(onClick = { speak(w.term, true) })
                }
                FilledTonalButton(onClick = { nav.go(Route.Learn(w.id)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Learn with Claude")
                }
            }
            item {
                SectionTitle("Meanings")
                if (w.meanings.isEmpty()) {
                    Text("No meaning yet - tap ✎ to add one. Until then this word is left out of practice.", color = MaterialTheme.colorScheme.error)
                }
                w.meanings.forEachIndexed { i, m -> Text("${i + 1}. $m", style = MaterialTheme.typography.bodyLarge) }
                if (w.altMeanings.isNotEmpty()) {
                    Text(
                        "Also accepted: ${w.altMeanings.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (w.forms.isNotEmpty()) {
                    Text(
                        (if (w.lang == "zh" || w.lang == "yue") "Also read: " else "Forms: ") +
                            "${w.forms.take(12).joinToString(", ")}${if (w.forms.size > 12) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (w.examples.isNotEmpty()) {
                item { SectionTitle("Examples") }
                items(w.examples) { ex ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(SentenceTiles.display(ex.text, profile.spaced), style = termStyle(20, profile.locale), modifier = Modifier.weight(1f))
                                SpeakButton(onClick = { speak(ex.text, false) })
                            }
                            ex.reading?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            ex.en?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            item {
                SectionTitle("In sets")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sets.forEach { s -> AssistChip(onClick = { nav.go(Route.SetWords(s.id, s.name)) }, label = { Text(s.name) }) }
                    if (sets.isEmpty()) Text("None", style = MaterialTheme.typography.bodyMedium)
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
                AttemptRow(a, showTerm = false)
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${word?.term.orEmpty()}?") },
            text = { Text("It's removed from all sets; its practice history stays in the stats.") },
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
fun AttemptRow(a: Attempt, showTerm: Boolean, onClick: (() -> Unit)? = null) {
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
                if (showTerm) {
                    val locale = HanziLockApp.get(LocalContext.current).languages.get(a.lang).locale
                    Text(a.term, style = termStyle(20, locale))
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
