package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanzilock.HanziLockApp
import com.hanzilock.data.DictEntry
import com.hanzilock.data.Example
import com.hanzilock.data.WordDraft
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.Pinyin
import com.hanzilock.quiz.SpeechMatcher
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.theme.hanziStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add or edit a word. Pinyin and meanings can be filled in from CC-CEDICT; examples from Claude. */
@Composable
fun WordEditScreen(nav: Navigator, id: Long?, prefill: DictEntry?, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()

    var loaded by rememberSaveable { mutableStateOf(false) }
    var hanzi by rememberSaveable { mutableStateOf(prefill?.simplified.orEmpty()) }
    var traditional by rememberSaveable { mutableStateOf(prefill?.let { p -> p.traditional.takeIf { it != p.simplified } }.orEmpty()) }
    var pinyin by rememberSaveable { mutableStateOf(prefill?.pinyinMarked.orEmpty()) }
    var meanings by rememberSaveable { mutableStateOf(prefill?.definitions?.take(4)?.joinToString("\n").orEmpty()) }
    var exZh by rememberSaveable { mutableStateOf("") }
    var exPinyin by rememberSaveable { mutableStateOf("") }
    var exEn by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var moreExamples by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(id) {
        if (id == null || loaded) return@LaunchedEffect
        val w = withContext(Dispatchers.IO) { app.words.get(id) } ?: return@LaunchedEffect
        hanzi = w.hanzi
        traditional = w.traditional.orEmpty()
        pinyin = w.pinyin
        meanings = w.meanings.joinToString("\n")
        w.examples.firstOrNull()?.let { exZh = it.zh; exPinyin = it.pinyin.orEmpty(); exEn = it.en.orEmpty() }
        moreExamples = w.examples.drop(1).map { com.hanzilock.data.AppJson.encodeToString(Example.serializer(), it) }
        tags = w.tags.joinToString(", ")
        loaded = true
    }

    fun fillFromDictionary() {
        val key = hanzi.trim()
        if (key.isEmpty()) return
        scope.launch {
            val entry = withContext(Dispatchers.IO) { app.dictionary.lookup(key).firstOrNull() }
            if (entry == null) {
                snackbar.showSnackbar(if (app.dictionary.isReady) "$key isn't in CC-CEDICT." else "The dictionary is still loading.")
                return@launch
            }
            pinyin = entry.pinyinMarked
            meanings = entry.definitions.take(4).joinToString("\n")
            if (traditional.isBlank() && entry.traditional != entry.simplified) traditional = entry.traditional
        }
    }

    fun generateExample() {
        val grader = app.graderOrNull() ?: run {
            scope.launch { snackbar.showSnackbar("Add a Claude API key in Settings (and be online) to generate examples.") }
            return
        }
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    grader.generateExample(ClaudeGrader.WordInfo(hanzi.trim(), Pinyin.display(pinyin), meanings.lines().filter { it.isNotBlank() }))
                }
            }
            busy = false
            r.onSuccess { exZh = it.zh; exPinyin = it.pinyin; exEn = it.en }
                .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't generate an example.") }
        }
    }

    fun save() {
        val h = hanzi.trim()
        val meaningList = meanings.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val normalizedPinyin = Pinyin.normalizeToMarked(pinyin).ifEmpty { pinyin.trim() }
        val error = when {
            SpeechMatcher.hanOnly(h).isEmpty() -> "Enter the word in Chinese characters."
            normalizedPinyin.isBlank() -> "Enter the pinyin (or fill it from the dictionary)."
            meaningList.isEmpty() -> "Enter at least one meaning."
            else -> null
        }
        if (error != null) {
            scope.launch { snackbar.showSnackbar(error) }
            return
        }
        val examples = buildList {
            if (exZh.isNotBlank()) add(Example(exZh.trim(), exPinyin.trim().ifEmpty { null }, exEn.trim().ifEmpty { null }))
            moreExamples.forEach { json ->
                runCatching { com.hanzilock.data.AppJson.decodeFromString(Example.serializer(), json) }.getOrNull()?.let(::add)
            }
        }
        val draft = WordDraft(
            hanzi = h,
            traditional = traditional.trim().ifEmpty { null },
            pinyin = normalizedPinyin,
            meanings = meaningList,
            examples = examples,
            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                if (id == null) {
                    if (app.words.byHanzi(h) != null) "$h is already in your list." else { app.words.insert(draft, "user"); null }
                } else {
                    val clash = app.words.byHanzi(h)
                    if (clash != null && clash.id != id) "$h is already in your list." else { app.words.update(id, draft); null }
                }
            }
            if (message != null) snackbar.showSnackbar(message) else nav.back()
        }
    }

    ScreenScaffold(title = if (id == null) "Add word" else "Edit word", onBack = nav::back) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = hanzi, onValueChange = { hanzi = it }, label = { Text("Word (simplified)") },
                textStyle = hanziStyle(24), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = ::fillFromDictionary, enabled = hanzi.isNotBlank()) { Text("Fill pinyin & meaning from dictionary") }
            OutlinedTextField(
                value = traditional, onValueChange = { traditional = it }, label = { Text("Traditional (optional)") },
                textStyle = hanziStyle(18), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pinyin, onValueChange = { pinyin = it }, label = { Text("Pinyin (xué xí or xue2 xi2)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = meanings, onValueChange = { meanings = it }, label = { Text("English meanings, one per line") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("Example sentence")
            Text(
                "Put spaces between words (我 每天 学习 汉语。) - offline practice turns them into word tiles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = exZh, onValueChange = { exZh = it }, label = { Text("Chinese") },
                textStyle = hanziStyle(18), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = exPinyin, onValueChange = { exPinyin = it }, label = { Text("Pinyin (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = exEn, onValueChange = { exEn = it }, label = { Text("English") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = ::generateExample, enabled = !busy && hanzi.isNotBlank() && meanings.isNotBlank()) {
                Text(if (busy) "Asking Claude…" else "Suggest an example with Claude")
            }
            OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags, comma separated (e.g. HSK2, work)") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = ::save) { Text("Save") }
            }
        }
    }
}
