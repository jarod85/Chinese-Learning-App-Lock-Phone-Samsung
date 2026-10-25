package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.core.CompletionRule
import com.hanzilock.core.LockEngine
import com.hanzilock.core.ScheduleMath
import com.hanzilock.data.DayStat
import com.hanzilock.data.Totals
import com.hanzilock.data.WordSet
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.common.formatTime
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val app = LocalAppCore.current
    val platformUi = LocalPlatformUi.current
    val scope = rememberCoroutineScope()
    val resumed = rememberResumeCount()
    val now = rememberTicker(15_000)
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val wordsVersion by app.words.changes.collectAsStateWithLifecycle()
    val status = remember(now, settingsVersion, resumed) { app.lockEngine.status() }
    val problems = remember(resumed, settingsVersion) { platformUi.setupProblems() }
    val lang = remember(settingsVersion) { app.languages.active }
    val totals by produceState<Totals?>(null, wordsVersion, lang.code) { value = withContext(Dispatchers.IO) { app.words.totals(lang.code) } }
    val sets by produceState(emptyList<WordSet>(), wordsVersion, lang.code) {
        value = withContext(Dispatchers.IO) { app.words.sets(lang.code) }
    }
    val activeSets = sets.filter { it.enabled }.map { it.name }
    // The set last chosen for endless practice, else the first switched-on one.
    val endlessSet = sets.firstOrNull { it.id == app.settings.endlessSetId(lang.code) }
        ?: sets.firstOrNull { it.enabled } ?: sets.firstOrNull()
    var pickEndlessSet by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var addLanguage by remember { mutableStateOf(false) }
    val today by produceState<DayStat?>(null, wordsVersion, lang.code) {
        value = withContext(Dispatchers.IO) { app.words.dailyStats(lang.code, 1).lastOrNull() }
    }
    var confirmDisable by remember { mutableStateOf(false) }

    ScreenScaffold(title = "Lingo Lock") { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (problems.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                        }
                        problems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = { nav.go(Route.Setup) }) { Text("Open the setup checklist") }
                    }
                }
            }

            UpdatePanel(always = false)

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Learning", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(lang.native, style = termStyle(26, lang.locale, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        TextButton(onClick = { pickLanguage = true }) { Text("Change") }
                    }
                    Text(
                        if (activeSets.isEmpty()) "No sets switched on yet" else activeSets.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { nav.go(Route.Sets) }) { Text("Choose sets / import your own words") }
                }
            }

            LockCard(
                status = status,
                times = app.settings.resetTimes,
                words = app.settings.wordsPerSession,
                rule = app.settings.completionRule,
                onToggle = { enable ->
                    if (enable) {
                        if (!app.pin.isSet) {
                            nav.go(Route.Setup)
                        } else {
                            app.lockEngine.setEnabled(true)
                            platformUi.lockEnabledWarning()?.let { scope.launch { snackbar.showSnackbar(it) } }
                        }
                    } else {
                        confirmDisable = true
                    }
                },
                onLockNow = {
                    app.lockEngine.lockNow()
                    platformUi.showGateNow()
                },
                onResume = { app.lockEngine.resume() },
                canLockNow = app.pin.isSet,
            )

            Button(onClick = { nav.go(Route.Practice()) }, modifier = Modifier.fillMaxWidth()) { Text("Practice now") }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Endless practice", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keep practising one set for as long as you like. It doesn't unlock the lock.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            endlessSet?.let { "${it.name} · ${it.wordCount} words" } ?: "No sets yet",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pickEndlessSet = true }, enabled = sets.isNotEmpty()) { Text("Switch set") }
                    }
                    OutlinedButton(
                        onClick = {
                            endlessSet?.let {
                                app.settings.setEndlessSetId(lang.code, it.id)
                                nav.go(Route.Practice(it.id))
                            }
                        },
                        enabled = endlessSet != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Practise this set endlessly") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    val t = today
                    if (t == null || t.wins + t.losses == 0) {
                        Text("No words practised yet today.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row {
                            Text("✓ ${t.wins} right", color = WinColor, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(16.dp))
                            Text("✗ ${t.losses} missed", color = LossColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    totals?.let {
                        Text(
                            "${it.words} words switched on · ${it.seen} practised · ${it.streakDays}-day streak",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (pickEndlessSet) {
        SetPickerDialog(
            lang = lang.code,
            selected = endlessSet?.id,
            onDismiss = { pickEndlessSet = false },
            onPick = {
                pickEndlessSet = false
                app.settings.setEndlessSetId(lang.code, it.id)
            },
        )
    }
    if (pickLanguage) {
        LanguageDialog(onDismiss = { pickLanguage = false }, onAdd = { pickLanguage = false; addLanguage = true })
    }
    if (addLanguage) {
        AddLanguageDialog(onDismiss = { addLanguage = false }, onAdded = { msg ->
            addLanguage = false
            scope.launch { snackbar.showSnackbar(msg) }
        })
    }
    if (confirmDisable) {
        PinDialog(
            title = "Turn the lock off?",
            message = "Enter the master PIN to switch the practice lock off.",
            onDismiss = { confirmDisable = false },
            onVerified = {
                confirmDisable = false
                app.lockEngine.setEnabled(false)
            },
        )
    }
}

@Composable
private fun LockCard(
    status: LockEngine.Status,
    times: List<Int>,
    words: Int,
    rule: CompletionRule,
    onToggle: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    onResume: () -> Unit,
    canLockNow: Boolean,
) {
    val (headline, color) = when {
        status.due -> "Practice is due now" to MaterialTheme.colorScheme.error
        status.pausedUntil != null -> "Paused until ${formatTime(status.pausedUntil)}" to MaterialTheme.colorScheme.secondary
        !status.enabled -> "Lock is off" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Unlocked" to WinColor
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleLarge, color = color)
                    if (status.enabled && status.nextResetAt != null && !status.due) {
                        Text("Next practice at ${formatTime(status.nextResetAt)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Switch(checked = status.enabled, onCheckedChange = onToggle)
            }
            Text(
                "${times.size}× a day at ${times.joinToString(" · ") { ScheduleMath.format(it) }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (rule == CompletionRule.CORRECT) "$words correct answers per session" else "$words words per session",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLockNow, enabled = canLockNow) { Text("Lock now (test)") }
                if (status.pausedUntil != null) OutlinedButton(onClick = onResume) { Text("Resume lock") }
            }
        }
    }
}
