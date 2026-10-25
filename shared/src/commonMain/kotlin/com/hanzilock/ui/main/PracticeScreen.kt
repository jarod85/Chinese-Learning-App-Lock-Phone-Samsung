package com.hanzilock.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanzilock.data.WordSet
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.quiz.SessionKind
import com.hanzilock.ui.quiz.QuizContent
import com.hanzilock.ui.quiz.QuizViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Voluntary practice from the app. Leaving keeps your place; "End" closes the session.
 * With [setId] it practises that one set endlessly, and you can switch to another set.
 */
@Composable
fun PracticeScreen(nav: Navigator, setId: Long? = null) {
    val app = LocalAppCore.current
    val vm: QuizViewModel = viewModel(key = "practice") { QuizViewModel(app) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    val set by produceState<WordSet?>(null, setId) { value = setId?.let { withContext(Dispatchers.IO) { app.words.set(it) } } }
    LaunchedEffect(setId) { vm.start(SessionKind.PRACTICE, setId) }
    LaunchedEffect(ui.phase) {
        if (vm.ui.value.phase == QuizViewModel.Phase.CLOSED) nav.back()
    }
    val endless = setId != null && (ui.session?.endless == true || ui.phase == QuizViewModel.Phase.NO_WORDS)
    ScreenScaffold(
        title = set?.let { "Endless · ${it.name}" } ?: "Practice",
        onBack = nav::back,
        actions = {
            if (endless) TextButton(onClick = { switching = true }) { Text("Switch set") }
            if (ui.phase == QuizViewModel.Phase.QUIZ && ui.kind == SessionKind.PRACTICE) {
                TextButton(onClick = vm::leavePractice) { Text("End") }
            }
        },
    ) { padding ->
        QuizContent(
            vm,
            onDone = nav::back,
            modifier = Modifier.padding(padding),
            noWordsBody = if (setId != null) "This set has no words with a meaning yet. Switch to another set or add words to it." else null,
        )
    }
    if (switching) {
        SetPickerDialog(
            lang = set?.lang ?: app.languages.active.code,
            selected = setId,
            onDismiss = { switching = false },
            onPick = { picked ->
                switching = false
                app.settings.setEndlessSetId(picked.lang, picked.id)
                if (picked.id != setId) nav.replace(Route.Practice(picked.id))
            },
        )
    }
}

/** Every set of [lang], switched on or not, to choose one for endless practice. */
@Composable
fun SetPickerDialog(lang: String, selected: Long?, onDismiss: () -> Unit, onPick: (WordSet) -> Unit) {
    val app = LocalAppCore.current
    val version by app.words.changes.collectAsStateWithLifecycle()
    val sets by produceState(emptyList<WordSet>(), lang, version) { value = withContext(Dispatchers.IO) { app.words.sets(lang) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Practise which set?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sets, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text("${s.wordCount} words · ${s.seenCount} practised${if (s.enabled) "" else " · switched off"}") },
                        leadingContent = { RadioButton(selected = s.id == selected, onClick = { onPick(s) }) },
                        modifier = Modifier.clickable { onPick(s) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
