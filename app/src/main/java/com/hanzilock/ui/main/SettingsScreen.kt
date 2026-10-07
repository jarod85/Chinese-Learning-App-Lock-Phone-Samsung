package com.hanzilock.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.BuildConfig
import com.hanzilock.HanziLockApp
import com.hanzilock.core.CompletionRule
import com.hanzilock.core.GradingMode
import com.hanzilock.core.ScheduleMath
import com.hanzilock.core.Settings
import com.hanzilock.data.DictState
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.common.SetPinDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable copy of the settings, re-read whenever they change, so the UI never shows stale values. */
private data class SettingsSnapshot(
    val lockEnabled: Boolean,
    val resetTimes: List<Int>,
    val windowStart: Int,
    val windowEnd: Int,
    val wordsPerSession: Int,
    val completionRule: CompletionRule,
    val speechAttempts: Int,
    val preferTypedPinyin: Boolean,
    val requireTones: Boolean,
    val allowMeaningOverride: Boolean,
    val gradingMode: GradingMode,
    val emailRuleCount: Int,
    val allowedCount: Int,
    val allowSkip: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Navigator, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val s = app.settings
    val scope = rememberCoroutineScope()
    val version by s.changes.collectAsStateWithLifecycle()
    val dictState by app.dictionary.state.collectAsStateWithLifecycle()
    val snap = remember(version) {
        SettingsSnapshot(
            s.lockEnabled, s.resetTimes, s.windowStart, s.windowEnd, s.wordsPerSession, s.completionRule,
            s.speechAttempts, s.preferTypedPinyin, s.requireTones, s.allowMeaningOverride, s.gradingMode,
            s.emailRules.size, app.allowList.allowedApps().size, s.allowSkipPronunciation,
        )
    }

    var editTime by remember { mutableStateOf<TimeEdit?>(null) }
    var changePin by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(s.claudeApiKey) }
    var model by remember { mutableStateOf(s.claudeModel) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun setTimes(times: List<Int>) = app.lockEngine.updateSchedule(times.distinct().ifEmpty { listOf(s.windowStart) })
    fun regenerate(count: Int = s.resetTimes.size) = setTimes(ScheduleMath.evenlySpaced(count, s.windowStart, s.windowEnd))


    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.importExport.exportBackup(uri) } }
            snackbar.showSnackbar(r.fold({ "Backup saved." }, { "Backup failed: ${it.message}" }))
        }
    }

    ScreenScaffold("Settings") { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ---- schedule ----
            SectionTitle("Lock schedule")
            SwitchRow("Practice lock on", "Gate the phone at the times below", snap.lockEnabled) { app.lockEngine.setEnabled(it) }
            Stepper("Times per day", snap.resetTimes.size, 1..12) { regenerate(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editTime = TimeEdit.WindowStart(snap.windowStart) }) {
                    Text("First: ${ScheduleMath.format(snap.windowStart)}")
                }
                OutlinedButton(onClick = { editTime = TimeEdit.WindowEnd(snap.windowEnd) }) {
                    Text("Last: ${ScheduleMath.format(snap.windowEnd)}")
                }
            }
            Text(
                "Times are spread evenly between first and last. Tap a time to move it, × to remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snap.resetTimes.forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { editTime = TimeEdit.Existing(t) },
                        label = { Text(ScheduleMath.format(t)) },
                        trailingIcon = if (snap.resetTimes.size > 1) {
                            {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.clickable { setTimes(snap.resetTimes - t) },
                                )
                            }
                        } else null,
                        colors = InputChipDefaults.inputChipColors(),
                    )
                }
                TextButton(onClick = { editTime = TimeEdit.New(12 * 60) }) { Text("+ Add time") }
            }

            // ---- session ----
            SectionTitle("Each session")
            Stepper("Words per session", snap.wordsPerSession, 1..30) { s.wordsPerSession = it }
            CompletionRule.entries.forEach { rule ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = snap.completionRule == rule, onClick = { s.completionRule = rule })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = snap.completionRule == rule, onClick = { s.completionRule = rule })
                    Text(rule.label.replace("N", snap.wordsPerSession.toString()))
                }
            }
            Text(
                "A wrong answer is always logged as a loss and the quiz moves on to a different word.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- while locked ----
            SectionTitle("While locked")
            NavRow("Allowed apps", "${snap.allowedCount} apps + phone, SMS & emergency") { nav.go(Route.AllowedApps) }
            NavRow("Priority emails", if (snap.emailRuleCount == 0) "Off" else "${snap.emailRuleCount} rules") { nav.go(Route.PriorityEmail) }

            // ---- checks ----
            SectionTitle("Checks")
            Stepper("Pronunciation tries", snap.speechAttempts, 1..5) { s.speechAttempts = it }
            SwitchRow("Start with typed pinyin", "Instead of speaking (e.g. at the office)", snap.preferTypedPinyin) { s.preferTypedPinyin = it }
            SwitchRow("Strict typed readings", "Tones in pinyin (xue2xi2 ✓ xuexi ✗), long vowels in Japanese", snap.requireTones) { s.requireTones = it }
            SwitchRow("Allow skipping pronunciation", "For languages without a typed reading (Korean, Spanish, …) when you can't talk", snap.allowSkip) {
                s.allowSkipPronunciation = it
            }
            SwitchRow("Allow \"I was right\"", "Accept your own English answer when the offline check disagrees", snap.allowMeaningOverride) {
                s.allowMeaningOverride = it
            }

            // ---- Claude ----
            SectionTitle("AI grading (Claude)")
            Text(
                "Optional. With an Anthropic API key, Claude grades the sentences you write and double-checks meanings, " +
                    "fills in new words (example sentences with their pinyin) and guides learning mode. " +
                    "Without it (or offline) you rebuild the stored example sentence from word tiles instead. " +
                    "Each graded sentence is one small API call billed to your key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it.trim() }, label = { Text("Anthropic API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it.trim() }, label = { Text("Model") },
                supportingText = { Text("Default: ${Settings.DEFAULT_CLAUDE_MODEL}") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            GradingMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = snap.gradingMode == mode, onClick = { s.gradingMode = mode }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = snap.gradingMode == mode, onClick = { s.gradingMode = mode })
                    Text(mode.label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    s.claudeApiKey = apiKey
                    s.claudeModel = model
                    scope.launch { snackbar.showSnackbar("Saved.") }
                }) { Text("Save") }
                OutlinedButton(
                    enabled = !testing && apiKey.isNotBlank(),
                    onClick = {
                        s.claudeApiKey = apiKey
                        s.claudeModel = model
                        testing = true
                        testResult = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { ClaudeGrader(apiKey, s.claudeModel).use { it.testConnection() } }
                            }
                            testing = false
                            testResult = r.fold({ it }, { it.message ?: "Failed" })
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            // ---- data ----
            SectionTitle("Your words & progress")
            Text(
                "Back up everything (all languages, sets, progress and history) to a file. Restore it by importing the file " +
                    "(Words > Import). Single sets can be exported from Words > Sets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { exportBackup.launch("lingolock-backup.json") }) { Text("Backup") }

            // ---- security & setup ----
            SectionTitle("Security & setup")
            NavRow("Change master PIN", null) { changePin = true }
            NavRow("Setup checklist", "Permissions Samsung needs for the lock") { nav.go(Route.Setup) }

            SectionTitle("App updates")
            UpdatePanel(always = true)

            SectionTitle("About")
            Text(
                "Lingo Lock ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · " +
                    when (val d = dictState) {
                        is DictState.Ready -> "${d.entries} dictionary entries"
                        is DictState.Importing -> "dictionary loading"
                        else -> "no dictionary"
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Data: CC-CEDICT (CC BY-SA 4.0) · HSK lists by drkameleon and hskhsk.com (MIT) · OpenJLPT (CC BY-SA 4.0) · Bannerless Studio word packs (CC BY-SA 4.0) · google-books-ngram-frequency (CC BY 3.0) · Tatoeba sentences (CC BY 2.0 FR).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    editTime?.let { edit ->
        TimeDialog(
            initialMinutes = edit.minutes,
            onDismiss = { editTime = null },
            onPick = { m ->
                when (edit) {
                    is TimeEdit.WindowStart -> { s.windowStart = m; if (s.windowEnd < m) s.windowEnd = m; regenerate() }
                    is TimeEdit.WindowEnd -> { s.windowEnd = maxOf(m, s.windowStart); regenerate() }
                    is TimeEdit.Existing -> setTimes(s.resetTimes - edit.minutes + m)
                    is TimeEdit.New -> setTimes(s.resetTimes + m)
                }
                editTime = null
            },
        )
    }
    if (changePin) {
        SetPinDialog(onDismiss = { changePin = false }, onSet = {
            app.pin.setPin(it)
            changePin = false
            scope.launch { snackbar.showSnackbar("PIN changed.") }
        })
    }
}

private sealed class TimeEdit(val minutes: Int) {
    class WindowStart(m: Int) : TimeEdit(m)
    class WindowEnd(m: Int) : TimeEdit(m)
    class Existing(m: Int) : TimeEdit(m)
    class New(m: Int) : TimeEdit(m)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
