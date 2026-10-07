package com.hanzilock.ui.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanzilock.quiz.SentenceTiles
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.termStyle

/**
 * Learning mode for one word (see [LearnViewModel]). Used by the Learn screen and, during a quiz,
 * on top of the result of a word.
 */
@Composable
fun LearnContent(wordId: Long, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val vm: LearnViewModel = viewModel(key = "learn-$wordId")
    LaunchedEffect(wordId) { vm.start(wordId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val locale = ui.language?.locale
    val spaced = ui.language?.spaced == true
    val readingLabel = ui.language?.readingLabel
    val showReading = readingLabel != null && ui.showReading

    @Composable
    fun Passage(text: String, reading: String, size: Int = 19) {
        if (text.isBlank()) return
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(SentenceTiles.display(text, spaced), style = termStyle(size, locale))
                if (showReading && reading.isNotBlank()) {
                    Text(reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SpeakButton(onClick = { vm.speak(text) })
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.word?.let { w ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.term, style = termStyle(40, locale, FontWeight.Medium), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (w.reading.isNotBlank()) {
                            Text(w.readingDisplay, style = termStyle(20, locale), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    SpeakButton(onClick = { vm.speak(w.term, slow = true) })
                }
            }
            if (readingLabel != null) {
                FilterChip(selected = ui.showReading, onClick = vm::toggleReading, label = { Text("Show $readingLabel") })
            }
        }

        val lesson = ui.lesson
        when {
            ui.loading -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Claude is preparing the lesson…", style = MaterialTheme.typography.bodyLarge)
            }
            lesson == null -> {
                Text(ui.error ?: "Couldn't load the lesson.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::retry) { Text("Try again") }
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
            else -> {
                SectionTitle("1 · What it means")
                Passage(lesson.explanation, lesson.explanationReading)

                SectionTitle("2 · How to use it")
                Passage(lesson.usage, lesson.usageReading)
                lesson.examples.forEach { ex ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Passage(ex.text, ex.reading, size = 20) }
                    }
                }

                SectionTitle("3 · Your turn")
                Text(
                    "Write ${LearnViewModel.SENTENCES} sentences of your own with ${ui.word?.term.orEmpty()}. Claude corrects them - " +
                        "this is practice, nothing is graded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.answers.forEachIndexed { i, a -> AnswerCard(i + 1, a, locale, spaced, showReading, vm::speak) }

                if (ui.done) {
                    Text(
                        "Lesson done: ${ui.right} of ${ui.answers.size} sentences were right.",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = ui.input,
                    onValueChange = vm::onInput,
                    label = {
                        Text(
                            if (ui.done) "Another sentence (optional)"
                            else "Sentence ${ui.answers.size + 1} of ${LearnViewModel.SENTENCES} in ${ui.language?.name ?: "the language"}",
                        )
                    },
                    enabled = !ui.checking,
                    textStyle = termStyle(20, locale),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.checkError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Button(onClick = vm::submit, enabled = ui.input.isNotBlank() && !ui.checking, modifier = Modifier.fillMaxWidth()) {
                    if (ui.checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Check with Claude")
                }
                if (ui.done) OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AnswerCard(
    number: Int,
    a: LearnViewModel.Answer,
    locale: String?,
    spaced: Boolean,
    showReading: Boolean,
    speak: (String, Boolean) -> Unit,
) {
    val r = a.review
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (r.correct) "✓" else "✗",
                    color = if (r.correct) WinColor else LossColor,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text("$number. ${a.sentence}", style = termStyle(18, locale), modifier = Modifier.weight(1f))
            }
            Text(r.feedback, style = termStyle(16, locale))
            if (showReading && r.feedbackReading.isNotBlank()) {
                Text(r.feedbackReading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val corrected = r.correctedSentence.trim()
            if (corrected.isNotEmpty() && SentenceTiles.display(corrected, spaced) != a.sentence.trim()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "→ " + SentenceTiles.display(corrected, spaced),
                            style = termStyle(18, locale, FontWeight.Medium),
                            color = if (r.correct) MaterialTheme.colorScheme.onSurface else WinColor,
                        )
                        if (showReading && r.correctedReading.isNotBlank()) {
                            Text(r.correctedReading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SpeakButton(onClick = { speak(corrected, false) })
                }
            }
        }
    }
}
