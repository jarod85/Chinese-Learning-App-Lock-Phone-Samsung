package com.hanzilock.ui.stories

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.AppCore
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.quiz.SentenceTiles
import com.hanzilock.stories.StoryLength
import com.hanzilock.stories.StorySummary
import com.hanzilock.stories.StoryText
import com.hanzilock.stories.WebStories
import com.hanzilock.ui.common.AppIcons
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.Route
import com.hanzilock.ui.main.ScreenScaffold
import com.hanzilock.ui.theme.hanziStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Stories tab: get a new Chinese story (written by Claude at your level, from Mandarin Bean,
 * or from any link or text) and the stories you have, with how far you got.
 */
@Composable
fun StoriesScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val app = LocalAppCore.current
    val version by app.stories.changes.collectAsStateWithLifecycle()
    val stories by produceState<List<StorySummary>?>(null, version) {
        value = withContext(Dispatchers.IO) { app.stories.list() }
    }
    val defaultLevel by produceState(app.settings.storyLevel.takeIf { it in 1..6 } ?: 1) {
        if (app.settings.storyLevel !in 1..6) value = withContext(Dispatchers.IO) { highestHskLevel(app) }
    }
    var writing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StorySummary?>(null) }
    val launcher = rememberStoryLauncher(nav, snackbar)

    ScreenScaffold("Stories · 故事") { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                NewStoryCard(
                    onWrite = { writing = true },
                    onBrowse = { nav.go(Route.StoryBrowse) },
                    onAdd = { adding = true },
                )
            }
            val list = stories
            if (list != null && list.isEmpty()) {
                item {
                    Text(
                        "Your stories will be listed here. Press and hold a word while reading to see its pinyin and meaning, " +
                            "tap the top of the page for the English translation, and answer questions in Chinese at the end.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (list != null) {
                item { SectionTitle("Your stories", Modifier.padding(horizontal = 16.dp)) }
                items(list, key = { it.id }) { s ->
                    StoryRow(s, onOpen = { nav.go(Route.StoryReader(s.id)) }, onDelete = { deleting = s })
                    HorizontalDivider()
                }
            }
        }
    }

    if (writing) {
        WriteStoryDialog(
            initialLevel = defaultLevel,
            onDismiss = { writing = false },
            onWrite = { level, length, topic ->
                writing = false
                app.settings.storyLevel = level
                launcher.run("Claude is writing your story…") { app.storyImporter.write(level, length, topic) }
            },
        )
    }
    if (adding) {
        StoryInputDialog(
            initial = "",
            onDismiss = { adding = false },
            onRead = { input ->
                adding = false
                launcher.run("Loading the story…") { importStory(app, input) }
            },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${s.title}\"?") },
            text = { Text("The story and its translations are removed from your ${LocalPlatformUi.current.deviceName}.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    launcher.scope.launch(Dispatchers.IO) { app.stories.delete(s.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    StoryLauncherDialog(launcher)
}

/** The highest HSK set you have switched on, as a starting level for stories. */
private fun highestHskLevel(app: AppCore): Int =
    app.words.sets("zh").filter { it.enabled }.mapNotNull { Regex("hsk(\\d)").find(it.key)?.groupValues?.get(1)?.toIntOrNull() }
        .filter { it in 1..6 }.maxOrNull() ?: 1

@Composable
private fun NewStoryCard(onWrite: () -> Unit, onBrowse: () -> Unit, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New story", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Write one for my level (Claude)")
            }
            OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Book, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Graded stories from Mandarin Bean")
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("From a link or text")
            }
            Text(
                "Tip: in Chrome, Share > Lingo Lock opens the page you're reading here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StoryRow(s: StorySummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(s.title, style = hanziStyle(19), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(s.titleEn.takeIf { it.isNotBlank() }, s.source, s.level, "${s.chars} characters").joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.finishedAt != null) {
                    Icon(Icons.Default.Check, contentDescription = "Finished", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Text("${(s.page + 1).coerceAtMost(s.pages)}/${s.pages}", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WriteStoryDialog(initialLevel: Int, onDismiss: () -> Unit, onWrite: (Int, StoryLength, String) -> Unit) {
    var level by rememberSaveable { mutableIntStateOf(initialLevel) }
    var length by rememberSaveable { mutableStateOf(StoryLength.MEDIUM) }
    var topic by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A new story by Claude") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Level", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..6).forEach { n -> FilterChip(selected = level == n, onClick = { level = n }, label = { Text("HSK $n") }) }
                }
                Text("Length", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoryLength.entries.forEach { l -> FilterChip(selected = length == l, onClick = { length = l }, label = { Text(l.label) }) }
                }
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic (optional)") },
                    placeholder = { Text("a lost cat, a trip to Xi'an…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "An original story with mostly words of your level, its English translation and questions for the end. " +
                        "It takes Claude about half a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onWrite(level, length, topic.trim()) }) { Text("Write") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Paste a link to a page with a Chinese story, or the Chinese text itself. */
@Composable
fun StoryInputDialog(initial: String, onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read a story") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a link to a web page with a Chinese story, or the Chinese text itself.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("https://… or 从前有一只小猫…") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Traditional characters are changed to simplified. Very long pages are cut to the first 8,000 characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onRead(input) }, enabled = input.isNotBlank()) { Text("Read") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A link (also "Title https://…" as Chrome shares it) is fetched; anything else is read as the story itself. */
suspend fun importStory(app: AppCore, input: String): Long {
    val text = input.trim()
    val url = WebStories.findUrl(text)
        ?: text.takeIf { t -> t.none(Char::isWhitespace) && !StoryText.hasHan(t) && Regex("^[\\w.-]+\\.[a-z]{2,}(/.*)?$").matches(t) }
            ?.let { "https://$it" }
    val chineseAroundLink = url?.let { text.replace(it, "").count(SentenceTiles::isHanChar) } ?: 0
    return if (url != null && chineseAroundLink < 60) app.storyImporter.fromLink(url) else app.storyImporter.fromText(text)
}

/** Runs a story import with a progress dialog, then opens the story (or says what went wrong). */
class StoryLauncher(val scope: CoroutineScope, private val onOpen: (Long) -> Unit, private val onError: (String) -> Unit) {
    var busy by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun run(message: String, block: suspend () -> Long) {
        if (busy != null) return
        busy = message
        job = scope.launch {
            val r = runCatching { block() }
            busy = null
            r.onSuccess(onOpen).onFailure { e -> if (e !is CancellationException) onError(e.message ?: "Something went wrong.") }
        }
    }

    fun cancel() {
        job?.cancel()
        busy = null
    }
}

@Composable
fun rememberStoryLauncher(nav: Navigator, snackbar: SnackbarHostState): StoryLauncher {
    val scope = rememberCoroutineScope()
    return remember(nav, snackbar) {
        StoryLauncher(scope, onOpen = { nav.go(Route.StoryReader(it)) }, onError = { scope.launch { snackbar.showSnackbar(it) } })
    }
}

@Composable
fun StoryLauncherDialog(launcher: StoryLauncher) {
    val message = launcher.busy ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(message) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = launcher::cancel) { Text("Cancel") } },
    )
}
