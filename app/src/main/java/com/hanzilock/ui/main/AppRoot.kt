package com.hanzilock.ui.main

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hanzilock.HanziLockApp
import com.hanzilock.data.DictEntry
import com.hanzilock.ui.common.PinDialog
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data object Words : Route
    data object Dictionary : Route
    data object Stats : Route
    data object Settings : Route
    data class WordDetail(val id: Long) : Route
    data class WordEdit(val id: Long?, val prefill: DictEntry? = null, val setId: Long? = null) : Route
    data object Practice : Route
    data object AllowedApps : Route
    data object PriorityEmail : Route
    data object Setup : Route
    data object Sets : Route
    data class SetWords(val setId: Long, val name: String) : Route
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
    Tab(Route.Dictionary, "Dictionary", Icons.Default.Search),
    Tab(Route.Stats, "Stats", Icons.Default.Star),
    Tab(Route.Settings, "Settings", Icons.Default.Settings),
)

@Composable
fun AppRoot(incoming: Uri? = null, onIncomingHandled: () -> Unit = {}) {
    val app = HanziLockApp.get(LocalContext.current)
    val nav = remember { Navigator(if (app.pin.isSet) Route.Home else Route.Setup) }
    var settingsUnlocked by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

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

    val current = nav.current
    val showTabs = TABS.any { it.route == current }
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = nav.stack.first() == tab.route,
                            onClick = {
                                if (tab.route == Route.Settings && !settingsUnlocked && app.pin.isSet) askPin = true
                                else nav.tab(tab.route)
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (current) {
                Route.Home -> HomeScreen(nav)
                Route.Words -> WordsScreen(nav, snackbar)
                Route.Dictionary -> DictionaryScreen(nav)
                Route.Stats -> StatsScreen(nav)
                Route.Settings -> SettingsScreen(nav, snackbar)
                is Route.WordDetail -> WordDetailScreen(nav, current.id)
                is Route.WordEdit -> WordEditScreen(nav, current.id, current.prefill, snackbar, current.setId)
                Route.Practice -> PracticeScreen(nav)
                Route.AllowedApps -> AllowedAppsScreen(nav)
                Route.PriorityEmail -> PriorityEmailScreen(nav)
                Route.Setup -> SetupScreen(nav)
                Route.Sets -> SetsScreen(nav, snackbar)
                is Route.SetWords -> WordsScreen(nav, snackbar, setScope = current)
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
