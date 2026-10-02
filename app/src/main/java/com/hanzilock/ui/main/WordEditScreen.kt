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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanzilock.HanziLockApp
import com.hanzilock.data.AppJson
import com.hanzilock.data.DictEntry
import com.hanzilock.data.Example
import com.hanzilock.data.WordDraft
import com.hanzilock.quiz.ClaudeGrader
import com.hanzilock.quiz.Pinyin
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add or edit a word. Chinese pinyin and meanings can be filled in from CC-CEDICT; any language
 * can get its reading, meanings and an example from Claude. New words go into [setId] (or your
 * "My words" set).
 */
@Composable
fun WordEditScreen(nav: Navigator, id: Long?, prefill: DictEntry?, snackbar: SnackbarHostState, setId: Long? = null) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val scope = rememberCoroutineScope()

    var lang by rememberSaveable { mutableStateOf(if (prefill != null) "zh" else app.settings.activeLanguage) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var term by rememberSaveable { mutableStateOf(prefill?.simplified.orEmpty()) }
    var traditional by rememberSaveable { mutableStateOf(prefill?.let { p -> p.traditional.takeIf { it != p.simplified } }.orEmpty()) }
    var reading by rememberSaveable { mutableStateOf(prefill?.pinyinMarked.orEmpty()) }
    var meanings by rememberSaveable { mutableStateOf(prefill?.definitions?.take(4)?.joinToString("\n").orEmpty()) }
    var exText by rememberSaveable { mutableStateOf("") }
    var exReading by rememberSaveable { mutableStateOf("") }
    var exEn by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var moreExamples by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var forms by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(id) {
        if (id == null || loaded) return@LaunchedEffect
        val w = withContext(Dispatchers.IO) { app.words.get(id) } ?: return@LaunchedEffect
        lang = w.lang
        term = w.term
        traditional = w.traditional.orEmpty()
        reading = w.reading
        meanings = w.meanings.joinToString("\n")
        w.examples.firstOrNull()?.let { exText = it.text; exReading = it.reading.orEmpty(); exEn = it.en.orEmpty() }
        moreExamples = w.examples.drop(1).map { AppJson.encodeToString(Example.serializer(), it) }
        forms = w.forms
        tags = w.tags.joinToString(", ")
        loaded = true
    }
    val profile = remember(lang) { app.languages.get(lang) }

    fun fillFromDictionary() {
        val key = term.trim()
        if (key.isEmpty()) return
        scope.launch {
            val entry = withContext(Dispatchers.IO) { app.dictionary.lookup(key).firstOrNull() }
            if (entry == null) {
                snackbar.showSnackbar(if (app.dictionary.isReady) "$key isn't in CC-CEDICT." else "The dictionary is still loading.")
                return@launch
            }
            reading = entry.pinyinMarked
            meanings = entry.definitions.take(4).joinToString("\n")
            if (traditional.isBlank() && entry.traditional != entry.simplified) traditional = entry.traditional
            if (exText.isBlank()) app.corpus.find(key, 1).firstOrNull()?.let { exText = it.text; exEn = it.en.orEmpty() }
        }
    }

    fun askClaude() {
        val grader = app.graderOrNull() ?: run {
            scope.launch { snackbar.showSnackbar("Add a Claude API key in Settings (and be online) to fill words in automatically.") }
            return
        }
        busy = true
        scope.launch {
            val need = meanings.isBlank() || reading.isBlank() && profile.canTypeReading
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (need) {
                        grader.describeWords(profile.name, listOf(term.trim())).firstOrNull()?.let { d ->
                            Triple(d.reading, d.meanings, Example(d.example, null, d.exampleTranslation))
                        }
                    } else {
                        val ex = grader.generateExample(
                            ClaudeGrader.WordInfo(term.trim(), reading, meanings.lines().filter { it.isNotBlank() }, profile.name),
                        )
                        Triple("", emptyList<String>(), Example(ex.zh, ex.pinyin.ifBlank { null }, ex.en))
                    }
                }
            }
            busy = false
            r.onSuccess { t ->
                if (t == null) return@onSuccess
                if (reading.isBlank() && t.first.isNotBlank()) reading = t.first
                if (meanings.isBlank() && t.second.isNotEmpty()) meanings = t.second.joinToString("\n")
                if (t.third.text.isNotBlank()) { exText = t.third.text; exReading = t.third.reading.orEmpty(); exEn = t.third.en.orEmpty() }
            }.onFailure { snackbar.showSnackbar(it.message ?: "Claude couldn't help with this word.") }
        }
    }

    fun save() {
        val t = term.trim()
        val meaningList = meanings.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val normalizedReading = if (lang == "zh") Pinyin.normalizeToMarked(reading).ifEmpty { reading.trim() } else reading.trim()
        val error = when {
            t.isEmpty() -> "Enter the word."
            lang == "zh" && normalizedReading.isBlank() -> "Enter the pinyin (or fill it from the dictionary)."
            meaningList.isEmpty() -> "Enter at least one meaning."
            else -> null
        }
        if (error != null) {
            scope.launch { snackbar.showSnackbar(error) }
            return
        }
        val examples = buildList {
            if (exText.isNotBlank()) add(Example(exText.trim(), exReading.trim().ifEmpty { null }, exEn.trim().ifEmpty { null }))
            moreExamples.forEach { json -> runCatching { AppJson.decodeFromString(Example.serializer(), json) }.getOrNull()?.let(::add) }
        }
        val draft = WordDraft(
            lang = lang,
            term = t,
            traditional = traditional.trim().ifEmpty { null },
            reading = normalizedReading,
            meanings = meaningList,
            examples = examples,
            forms = forms,
            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
        scope.launch {
            // (message, close the screen?)
            val (message, done) = withContext(Dispatchers.IO) {
                if (id == null) {
                    val target = setId ?: app.words.myWordsSet(lang)
                    val existing = app.words.find(lang, t)
                    if (existing != null) {
                        // Already known from another set (e.g. HSK 4): share it, keeping its progress.
                        app.words.addToSet(target, existing.id)
                        "Added $t - it was already in another set, so its progress is kept." to true
                    } else {
                        app.words.insert(draft, "user", target)
                        null to true
                    }
                } else {
                    val clash = app.words.find(lang, t)
                    if (clash != null && clash.id != id) {
                        "$t is already in your words." to false
                    } else {
                        app.words.update(id, draft)
                        null to true
                    }
                }
            }
            if (done) nav.back()
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    ScreenScaffold(title = if (id == null) "Add ${profile.name} word" else "Edit word", onBack = nav::back) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = term, onValueChange = { term = it }, label = { Text(if (lang == "zh") "Word (simplified)" else "Word") },
                textStyle = termStyle(if (profile.cjk) 24 else 20, profile.locale), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lang == "zh") OutlinedButton(onClick = ::fillFromDictionary, enabled = term.isNotBlank()) { Text("Fill from dictionary") }
                OutlinedButton(onClick = ::askClaude, enabled = !busy && term.isNotBlank()) { Text(if (busy) "Asking Claude…" else "Fill in with Claude") }
            }
            if (lang == "zh") {
                OutlinedTextField(
                    value = traditional, onValueChange = { traditional = it }, label = { Text("Traditional (optional)") },
                    textStyle = termStyle(18, profile.locale), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (profile.readingLabel != null || reading.isNotBlank()) {
                OutlinedTextField(
                    value = reading, onValueChange = { reading = it },
                    label = { Text(if (lang == "zh") "Pinyin (xué xí or xue2 xi2)" else if (lang == "ja") "Reading (hiragana)" else "Reading") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = meanings, onValueChange = { meanings = it }, label = { Text("English meanings, one per line") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("Example sentence")
            if (lang == "zh") {
                Text(
                    "Put spaces between words (我 每天 学习 汉语。) - offline practice turns them into word tiles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = exText, onValueChange = { exText = it }, label = { Text(profile.name) },
                textStyle = termStyle(18, profile.locale), modifier = Modifier.fillMaxWidth(),
            )
            if (profile.readingLabel != null) {
                OutlinedTextField(value = exReading, onValueChange = { exReading = it }, label = { Text("Reading (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(value = exEn, onValueChange = { exEn = it }, label = { Text("English") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags, comma separated (e.g. work, food)") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = ::save) { Text("Save") }
            }
        }
    }
}
