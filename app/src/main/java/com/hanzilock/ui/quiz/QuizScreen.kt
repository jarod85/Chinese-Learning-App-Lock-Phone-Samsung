package com.hanzilock.ui.quiz

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.R
import com.hanzilock.core.CompletionRule
import com.hanzilock.data.QuizPart
import com.hanzilock.data.Word
import com.hanzilock.quiz.SentenceTiles
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.common.formatTime
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.hanziStyle
import com.hanzilock.ui.quiz.QuizViewModel.Phase
import com.hanzilock.ui.quiz.QuizViewModel.SentenceMode
import com.hanzilock.ui.quiz.QuizViewModel.Step

/** The quiz itself; the lock screen and the practice screen put their own frame around it. */
@Composable
fun QuizContent(vm: QuizViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        when (ui.phase) {
            Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            Phase.NO_WORDS -> Message(
                title = "Your word list is empty",
                body = "Add words in HanziLock (Words tab, the dictionary or an import) and practice will start from them.",
                button = "OK",
                onClick = onDone,
            )
            Phase.SUMMARY -> Summary(ui.summary, onDone)
            Phase.CLOSED -> Unit
            Phase.QUIZ -> WordQuiz(ui, vm)
        }
    }
}

@Composable
private fun WordQuiz(ui: QuizViewModel.Ui, vm: QuizViewModel) {
    val word = ui.word ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionProgress(ui)
        WordCard(word, ui, vm)
        StepChips(ui)
        when (ui.step) {
            Step.PRONUNCIATION -> PronunciationStep(ui, vm)
            Step.MEANING -> MeaningStep(ui, vm)
            Step.SENTENCE -> SentenceStep(ui, vm, word)
            Step.RESULT -> ResultStep(ui, vm, word)
        }
        ui.note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SessionProgress(ui: QuizViewModel.Ui) {
    val s = ui.session ?: return
    val done = if (s.rule == CompletionRule.CORRECT) s.wins else s.attempted
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.rule == CompletionRule.CORRECT) "Correct: $done / ${s.target}" else "Word ${minOf(done + 1, s.target)} of ${s.target}",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text("✓ ${s.wins}", color = WinColor, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(10.dp))
            Text("✗ ${s.losses}", color = LossColor, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { done.toFloat() / s.target.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WordCard(word: Word, ui: QuizViewModel.Ui, vm: QuizViewModel) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(word.hanzi, style = hanziStyle(if (word.hanzi.length > 3) 52 else 72), color = MaterialTheme.colorScheme.onPrimaryContainer)
            word.traditional?.let {
                Text(it, style = hanziStyle(20), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
            // Pinyin and audio stay hidden until the pronunciation step is over.
            if (Step.PRONUNCIATION in ui.passed || ui.step == Step.RESULT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.pinyinDisplay, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    SpeakButton(onClick = { vm.speak(word.hanzi, slow = true) })
                }
            }
        }
    }
}

@Composable
private fun StepChips(ui: QuizViewModel.Ui) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Step.PRONUNCIATION to "1 Say it", Step.MEANING to "2 Meaning", Step.SENTENCE to "3 Sentence").forEach { (step, label) ->
            val passed = step in ui.passed
            val current = ui.step == step
            val failedHere = ui.step == Step.RESULT && ui.result?.win == false && ui.result.failedPart?.toStep() == step
            val bg = when {
                failedHere -> LossColor.copy(alpha = 0.15f)
                passed -> WinColor.copy(alpha = 0.15f)
                current -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(color = bg, shape = RoundedCornerShape(50)) {
                Text(
                    (if (passed) "✓ " else if (failedHere) "✗ " else "") + label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun QuizPart.toStep() = when (this) {
    QuizPart.PRONUNCIATION -> Step.PRONUNCIATION
    QuizPart.MEANING -> Step.MEANING
    QuizPart.SENTENCE -> Step.SENTENCE
}

@Composable
private fun rememberMicAction(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (listening) 1f + (level.coerceIn(0f, 10f) / 25f) else 1f, label = "mic")
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(80.dp).scale(scale),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = if (listening) "Stop" else "Speak", modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun PronunciationStep(ui: QuizViewModel.Ui, vm: QuizViewModel) {
    val listen = rememberMicAction(vm::toggleListening)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!ui.typedPinyin) {
            Text("Say the word out loud", style = MaterialTheme.typography.titleMedium)
            MicButton(ui.listening, ui.level, enabled = !ui.busy, onClick = listen)
            Text(
                when {
                    ui.listening && ui.partial.isNotEmpty() -> ui.partial
                    ui.listening -> "Listening…"
                    else -> "Tap the mic, then speak (${ui.speechTriesLeft} ${if (ui.speechTriesLeft == 1) "try" else "tries"} left)"
                },
                style = if (ui.listening && ui.partial.isNotEmpty()) hanziStyle(22) else MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { vm.setTypedPinyin(true) }) { Text("Can't talk right now? Type the pinyin") }
        } else {
            Text("Type the pinyin, with tones", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ui.pinyinInput,
                onValueChange = vm::onPinyinInput,
                label = { Text("e.g. xue2xi2 or xuéxí") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { vm.submitPinyin() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = vm::submitPinyin, enabled = ui.pinyinInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Check") }
            if (ui.speechAvailable) TextButton(onClick = { vm.setTypedPinyin(false) }) { Text("Use the microphone instead") }
        }
    }
}

@Composable
private fun MeaningStep(ui: QuizViewModel.Ui, vm: QuizViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What does it mean in English?", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ui.meaningInput,
            onValueChange = vm::onMeaningInput,
            label = { Text("Meaning (one is enough)") },
            singleLine = true,
            enabled = !ui.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.None),
            keyboardActions = KeyboardActions(onDone = { vm.submitMeaning() }),
            modifier = Modifier.fillMaxWidth(),
        )
        BusyButton("Check", busy = ui.busy, enabled = ui.meaningInput.isNotBlank(), onClick = vm::submitMeaning)
    }
}

@Composable
private fun SentenceStep(ui: QuizViewModel.Ui, vm: QuizViewModel, word: Word) {
    val dictate = rememberMicAction(vm::toggleDictation)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (ui.sentenceMode) {
            SentenceMode.WRITE_AI, SentenceMode.WRITE_OFFLINE -> {
                Text("Use ${word.hanzi} in a sentence", style = MaterialTheme.typography.titleMedium.merge(hanziStyle(18)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = if (ui.listening && ui.partial.isNotEmpty()) ui.partial else ui.sentenceInput,
                        onValueChange = vm::onSentenceInput,
                        label = { Text("Your sentence in Chinese") },
                        enabled = !ui.busy && !ui.listening,
                        textStyle = hanziStyle(20),
                        minLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    MicButtonSmall(listening = ui.listening, enabled = !ui.busy, onClick = dictate)
                }
                BusyButton(
                    if (ui.sentenceMode == SentenceMode.WRITE_AI) "Check with Claude" else "Check",
                    busy = ui.busy,
                    enabled = ui.sentenceInput.isNotBlank() && !ui.listening,
                    onClick = vm::submitSentence,
                )
                if (ui.sentenceMode == SentenceMode.WRITE_OFFLINE) {
                    Text(
                        "Offline mode can only check that your sentence uses the word. Add a Claude API key in Settings for real grading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SentenceMode.TILES -> TileBuilder(ui, vm)
        }
    }
}

@Composable
private fun MicButtonSmall(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        ),
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = if (listening) "Stop dictation" else "Dictate")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileBuilder(ui: QuizViewModel.Ui, vm: QuizViewModel) {
    Text("Build the example sentence", style = MaterialTheme.typography.titleMedium)
    ui.tileExample?.en?.let {
        Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.picked.isEmpty()) Text("Tap the words below in the right order", modifier = Modifier.padding(8.dp))
            ui.picked.forEach { i -> Tile(ui.tiles[i], filled = true) { vm.unpickTile(i) } }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ui.pool.forEach { i -> Tile(ui.tiles[i], filled = false) { vm.pickTile(i) } }
    }
    Button(onClick = vm::checkTiles, enabled = ui.picked.size == ui.tiles.size && ui.tiles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text("Check")
    }
}

@Composable
private fun Tile(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (filled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = hanziStyle(22))
    }
}

@Composable
private fun ResultStep(ui: QuizViewModel.Ui, vm: QuizViewModel, word: Word) {
    val r = ui.result ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (r.win) WinColor else LossColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (r.win) Icons.Default.Check else Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (r.win) "Correct!" else "Missed: ${r.failedPart?.label ?: "answer"} - logged as a loss",
                style = MaterialTheme.typography.titleMedium,
                color = if (r.win) WinColor else LossColor,
            )
        }
        r.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        r.feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        r.corrected?.takeIf { it.isNotBlank() }?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Better: ", style = MaterialTheme.typography.labelLarge)
                Text(it, style = hanziStyle(18), modifier = Modifier.weight(1f))
                SpeakButton(onClick = { vm.speak(it) })
            }
        }
        r.translation?.takeIf { it.isNotBlank() }?.let {
            Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnswerCard(word, vm)
        if (r.canOverrideMeaning) {
            OutlinedButton(onClick = vm::overrideMeaning, modifier = Modifier.fillMaxWidth()) {
                Text("I was right - accept “${ui.meaningInput.trim()}”")
            }
        }
        BusyButton("Next", busy = ui.busy, enabled = true, onClick = vm::next)
    }
}

/** Pleco-style entry: pinyin + audio, meanings and the example sentence. */
@Composable
fun AnswerCard(word: Word, vm: QuizViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word.hanzi, style = hanziStyle(26, FontWeight.Medium))
                Spacer(Modifier.width(12.dp))
                Text(word.pinyinDisplay, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SpeakButton(onClick = { vm.speak(word.hanzi, slow = true) })
            }
            word.meanings.forEachIndexed { i, m -> Text("${i + 1}. $m", style = MaterialTheme.typography.bodyLarge) }
            word.examples.firstOrNull()?.let { ex ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SentenceTiles.display(ex.zh), style = hanziStyle(19), modifier = Modifier.weight(1f))
                    SpeakButton(onClick = { vm.speak(ex.zh) })
                }
                ex.pinyin?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ex.en?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun Summary(summary: QuizViewModel.SummaryUi?, onDone: () -> Unit) {
    val s = summary ?: return
    Message(
        title = if (s.unlocked) "Done - phone unlocked 🎉" else "Practice complete",
        body = buildString {
            append("✓ ${s.wins} right   ✗ ${s.losses} missed")
            if (s.unlocked && s.nextResetAt != null) append("\n\nNext practice at ${formatTime(s.nextResetAt)}.")
        },
        button = "Done",
        onClick = onDone,
    )
}

@Composable
private fun Message(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClick) { Text(button) }
    }
}
