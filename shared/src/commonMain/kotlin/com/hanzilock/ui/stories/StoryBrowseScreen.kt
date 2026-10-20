package com.hanzilock.ui.stories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.stories.WebStories
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mandarin Bean's graded stories by HSK level; tapping one loads it onto the phone and opens it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryBrowseScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val app = LocalAppCore.current
    val launcher = rememberStoryLauncher(nav, snackbar)
    var level by rememberSaveable { mutableStateOf(app.settings.storyLevel.takeIf { it in 1..6 }) }
    var items by remember { mutableStateOf<List<WebStories.Listing>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val version by app.stories.changes.collectAsStateWithLifecycle()
    val saved by produceState(emptySet<String>(), version) { value = withContext(Dispatchers.IO) { app.stories.urls() } }

    fun load(next: Int) {
        val forLevel = level
        loading = true
        error = null
        launcher.scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { WebStories.mandarinBeanStories(forLevel, next) } }
            if (forLevel != level) return@launch
            loading = false
            r.onSuccess {
                items = if (next == 1) it.items else items + it.items
                page = next
                totalPages = it.totalPages
            }.onFailure { error = it.message ?: "Couldn't reach Mandarin Bean." }
        }
    }
    LaunchedEffect(level) {
        items = emptyList()
        page = 0
        load(1)
    }

    ScreenScaffold("Mandarin Bean stories", onBack = nav::back) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Graded stories from mandarinbean.com, sorted by HSK level. A story you open is loaded onto your ${LocalPlatformUi.current.deviceName} for your own reading.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = level == null, onClick = { level = null }, label = { Text("All") })
                        (1..6).forEach { n -> FilterChip(selected = level == n, onClick = { level = n }, label = { Text("HSK $n") }) }
                    }
                }
            }
            items(items, key = { it.url }) { s ->
                ListItem(
                    modifier = Modifier.clickable { launcher.run("Loading \"${s.title}\"…") { app.storyImporter.fromLink(s.url) } },
                    headlineContent = { Text(s.title) },
                    supportingContent = s.level?.let { { Text("HSK $it") } },
                    trailingContent = if (s.url in saved) {
                        { Icon(Icons.Default.Check, contentDescription = "Saved", tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
                HorizontalDivider()
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    when {
                        loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        error != null -> {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { load(page + 1) }) { Text("Try again") }
                        }
                        page in 1 until totalPages -> TextButton(onClick = { load(page + 1) }) { Text("Load more") }
                        page >= 1 && items.isEmpty() -> Text("No stories at this level yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    StoryLauncherDialog(launcher)
}
