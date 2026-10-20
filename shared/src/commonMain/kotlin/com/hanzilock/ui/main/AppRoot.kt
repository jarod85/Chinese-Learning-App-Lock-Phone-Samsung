package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hanzilock.data.DictEntry
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.platform.PickedFile
import com.hanzilock.ui.common.AppIcons
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.stories.StoriesScreen
import com.hanzilock.ui.stories.StoryBrowseScreen
import com.hanzilock.ui.stories.StoryInputDialog
import com.hanzilock.ui.stories.StoryLauncherDialog
import com.hanzilock.ui.stories.StoryReaderScreen
import com.hanzilock.ui.stories.importStory
import com.hanzilock.ui.stories.rememberStoryLauncher
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data object Words : Route
    data object Dictionary : Route
    data object Stats : Route
    data object Settings : Route
    data class WordDetail(val id: Long) : Route
    data class WordEdit(val id: Long?, val prefill: DictEntry? = null, val setId: Long? = null) : Route
    data class Learn(val id: Long) : Route
    data object Practice : Route
    data object AllowedApps : Route
    data object PriorityEmail : Route
    data object Setup : Route
    data object Sets : Route
    data class SetWords(val setId: Long, val name: String) : Route
    data object Stories : Route
    data object StoryBrowse : Route
    data class StoryReader(val id: Long) : Route
}

class Navigator(initial: Route) {
    val stack = mutableStateListOf(initial)
    val current: Route get() = stack.last()
    fun go(route: Route) { stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(route: Route) { stack.clear(); stack.add(route) }
    fun replace(route: Route) { stack[stack.lastIndex] = route }
}

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Route.Home, "Home", Icons.Default.Home),
    Tab(Route.Words, "Words", Icons.AutoMirrored.Filled.List),
    Tab(Route.Stories, "Stories", AppIcons.Book),
    Tab(Route.Dictionary, "Dictionary", Icons.Default.Search),
    Tab(Route.Stats, "Stats", Icons.Default.Star),
    Tab(Route.Settings, "Settings", Icons.Default.Settings),
)

/** Cmd (Mac) or Ctrl + 1…6 switch tabs. */
private val TAB_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six)

@Composable
fun AppRoot(
    incoming: PickedFile? = null,
    onIncomingHandled: () -> Unit = {},
    incomingText: String? = null,
    onIncomingTextHandled: () -> Unit = {},
) {
    val app = LocalAppCore.current
    val platformUi = LocalPlatformUi.current
    val nav = remember { Navigator(if (app.pin.isSet) Route.Home else Route.Setup) }
    var settingsUnlocked by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val storyLauncher = rememberStoryLauncher(nav, snackbar)
    platformUi.BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    // Leaving the app locks the settings again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && settingsUnlocked) {
                settingsUnlocked = false
                if (nav.stack.any { it == Route.Settings || it == Route.AllowedApps || it == Route.PriorityEmail }) nav.tab(Route.Home)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openTab(tab: Tab) {
        if (tab.route == Route.Settings && !settingsUnlocked && app.pin.isSet) askPin = true
        else nav.tab(tab.route)
    }
    platformUi.KeyShortcuts { e ->
        val i = TAB_KEYS.indexOf(e.key)
        if (e.type == KeyEventType.KeyDown && (e.isMetaPressed || e.isCtrlPressed) && i in TABS.indices && app.pin.isSet) {
            openTab(TABS[i])
            true
        } else {
            false
        }
    }

    val current = nav.current
    val showTabs = TABS.any { it.route == current }
    // A wide window (the Mac, a tablet, DeX) gets a sidebar that is always there, and text that doesn't stretch.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (showTabs && !wide) TabBar(nav, ::openTab) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (wide && app.pin.isSet) SideBar(nav, ::openTab)
                Box(Modifier.weight(1f).fillMaxHeight().imePadding(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 960.dp).fillMaxSize()) { Screen(nav, current, snackbar) }
                }
            }
        }
    }

    // A file shared to / opened with Lingo Lock: import it as a new set.
    if (incoming != null && app.pin.isSet) {
        ImportDialog(incoming, onDismiss = onIncomingHandled, onDone = { msg ->
            onIncomingHandled()
            scope.launch { snackbar.showSnackbar(msg) }
        })
    }

    // A web page (or Chinese text) shared to Lingo Lock, e.g. from Chrome: read it as a story.
    if (incomingText != null && app.pin.isSet) {
        StoryInputDialog(
            initial = incomingText,
            onDismiss = onIncomingTextHandled,
            onRead = { input ->
                onIncomingTextHandled()
                storyLauncher.run("Loading the story…") { importStory(app, input) }
            },
        )
    }
    StoryLauncherDialog(storyLauncher)

    if (askPin) {
        PinDialog(
            title = "Settings are protected",
            message = "Enter the master PIN to change the lock settings.",
            onDismiss = { askPin = false },
            onVerified = {
                askPin = false
                settingsUnlocked = true
                nav.tab(Route.Settings)
            },
        )
    }
}

@Composable
private fun TabBar(nav: Navigator, onTab: (Tab) -> Unit) {
    NavigationBar {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = nav.stack.first() == tab.route,
                onClick = { onTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1, softWrap = false) },
            )
        }
    }
}

@Composable
private fun SideBar(nav: Navigator, onTab: (Tab) -> Unit) {
    NavigationRail {
        Spacer(Modifier.height(8.dp))
        TABS.forEach { tab ->
            NavigationRailItem(
                selected = nav.stack.first() == tab.route,
                onClick = { onTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1, softWrap = false) },
            )
        }
    }
}

@Composable
private fun Screen(nav: Navigator, current: Route, snackbar: SnackbarHostState) {
    val platformUi = LocalPlatformUi.current
    when (current) {
        Route.Home -> HomeScreen(nav, snackbar)
        Route.Words -> WordsScreen(nav, snackbar)
        Route.Dictionary -> DictionaryScreen(nav)
        Route.Stats -> StatsScreen(nav)
        Route.Settings -> SettingsScreen(nav, snackbar)
        is Route.WordDetail -> WordDetailScreen(nav, current.id)
        is Route.WordEdit -> WordEditScreen(nav, current.id, current.prefill, snackbar, current.setId)
        is Route.Learn -> LearnScreen(nav, current.id)
        Route.Practice -> PracticeScreen(nav)
        Route.AllowedApps -> platformUi.AllowedAppsScreen(nav)
        Route.PriorityEmail -> platformUi.PriorityEmailScreen(nav)
        Route.Setup -> platformUi.SetupScreen(nav)
        Route.Sets -> SetsScreen(nav, snackbar)
        is Route.SetWords -> WordsScreen(nav, snackbar, setScope = current)
        Route.Stories -> StoriesScreen(nav, snackbar)
        Route.StoryBrowse -> StoryBrowseScreen(nav, snackbar)
        is Route.StoryReader -> StoryReaderScreen(nav, current.id)
    }
}

/** Top bar + content, without re-applying the insets the root scaffold already handled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}
