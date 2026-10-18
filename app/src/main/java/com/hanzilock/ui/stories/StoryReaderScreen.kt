package com.hanzilock.ui.stories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanzilock.HanziLockApp
import com.hanzilock.R
import com.hanzilock.data.DictEntry
import com.hanzilock.stories.Story
import com.hanzilock.stories.StoryAnswerReview
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.common.rememberMicAction
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.Route
import com.hanzilock.ui.main.ScreenScaffold
import com.hanzilock.ui.stories.StoryViewModel.Companion.EXPLANATION
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.hanziStyle
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.launch

private const val ZH = "zh-CN"

/**
 * A story, page by page. Press and hold a word for its pinyin and meaning; tap the panel at the
 * top to show or hide the English translation of the page; the speaker button reads the story
 * aloud. After the last page come the comprehension questions.
 */
@Composable
fun StoryReaderScreen(nav: Navigator, id: Long) {
    val vm: StoryViewModel = viewModel(key = "story-$id")
    LaunchedEffect(id) { vm.start(id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.stopReading() } }
    val story = ui.story
    if (story == null) {
        ScreenScaffold("Story", onBack = nav::back) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (ui.loading) CircularProgressIndicator() else Text(ui.error ?: "Couldn't open the story.", Modifier.padding(24.dp))
            }
        }
    } else {
        Reader(nav, vm, ui, story)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Reader(nav: Navigator, vm: StoryViewModel, ui: StoryViewModel.Ui, story: Story) {
    val pageCount = ui.pages.size + 1
    val pager = rememberPagerState(initialPage = story.page.coerceIn(0, pageCount - 1)) { pageCount }
    val scope = rememberCoroutineScope()
    val onStoryPage = pager.currentPage < ui.pages.size

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            vm.onPage(page)
            // Swiping to another page stops reading aloud; following the voice doesn't.
            val s = vm.ui.value
            if (s.reading && s.sentencePage.getOrNull(s.readingSentence) != page) vm.stopReading()
        }
    }
    LaunchedEffect(ui.reading, ui.readingSentence) {
        if (!ui.reading) return@LaunchedEffect
        val page = ui.sentencePage.getOrNull(ui.readingSentence) ?: return@LaunchedEffect
        if (page != pager.currentPage) pager.animateScrollToPage(page)
    }
    LaunchedEffect(ui.showEnglish, pager.settledPage) {
        if (ui.showEnglish) vm.ensureTranslated(pager.settledPage)
    }
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage == ui.pages.size) vm.loadQuestions()
    }

    // Where the reading area is on screen, to show the word card away from the word you pressed.
    var areaTop by remember { mutableFloatStateOf(0f) }
    var areaHeight by remember { mutableFloatStateOf(1f) }

    ScreenScaffold(
        title = story.title,
        onBack = nav::back,
        actions = {
            if (onStoryPage) {
                TextButton(onClick = vm::toggleSlow) { Text(if (ui.slow) "Slow" else "Normal") }
                IconButton(onClick = { if (ui.reading) vm.stopReading() else vm.play(pager.currentPage) }) {
                    Icon(
                        painterResource(if (ui.reading) R.drawable.ic_pause else R.drawable.ic_volume_up),
                        contentDescription = if (ui.reading) "Pause" else "Read aloud",
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().onGloballyPositioned {
                areaTop = it.positionInWindow().y
                areaHeight = it.size.height.toFloat().coerceAtLeast(1f)
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (onStoryPage) {
                    TranslationPanel(ui, pager.currentPage, onToggle = vm::toggleEnglish, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.24f))
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    if (page < ui.pages.size) {
                        StoryPage(ui, page, vm, lowerHalf = { y -> y - areaTop > areaHeight / 2 })
                    } else {
                        ComprehensionPage(ui, vm, onDone = nav::back)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                    }
                    Text(
                        when {
                            !onStoryPage -> "Questions"
                            pager.currentPage == ui.pages.lastIndex -> "Page ${pager.currentPage + 1} of ${ui.pages.size} · questions next"
                            else -> "Page ${pager.currentPage + 1} of ${ui.pages.size}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < pageCount - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                    }
                }
            }
            ui.lookup?.let { l ->
                LookupCard(
                    l,
                    onSpeak = { vm.speakWord(l.span.word) },
                    onAdd = { e -> nav.go(Route.WordEdit(null, e)) },
                    onClose = vm::dismissLookup,
                    modifier = Modifier.align(if (l.atTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(bottom = if (l.atTop) 0.dp else 48.dp),
                )
            }
            ui.note?.let { note ->
                ElevatedCard(Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = 48.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::clearNote) { Text("OK") }
                    }
                }
            }
        }
    }
}

/** The top section: the English translation of the page, shown or hidden by tapping it. */
@Composable
private fun TranslationPanel(ui: StoryViewModel.Ui, page: Int, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val story = ui.story ?: return
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (!ui.showEnglish) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("English translation", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tap to show", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Box
            }
            val sentences = ui.pages.getOrNull(page)?.sentences ?: IntRange.EMPTY
            val translated = sentences.map { story.body.chunks[it] }
            if (translated.any { it.en.isNotBlank() }) {
                val text = buildAnnotatedString {
                    translated.forEachIndexed { k, c ->
                        if (k > 0) append(if (c.p) "\n\n" else " ")
                        val i = sentences.first + k
                        val start = length
                        append(c.en.ifBlank { "…" })
                        if (ui.reading && i == ui.readingSentence) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                    }
                }
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
            } else if (ui.translating) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Translating…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    ui.translateError ?: "No translation yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.translateError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun StoryPage(ui: StoryViewModel.Ui, pageIndex: Int, vm: StoryViewModel, lowerHalf: (Float) -> Boolean) {
    val page = ui.pages[pageIndex]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        page.blocks.forEachIndexed { b, block ->
            StoryParagraph(
                block = block,
                reading = if (ui.reading) block.sentences.firstOrNull { it.first == ui.readingSentence }?.second else null,
                selected = ui.lookup?.takeIf { it.page == pageIndex && it.block == b }?.span,
                onPress = { word, y -> vm.press(pageIndex, b, word, lowerHalf(y)) },
                onTap = vm::dismissLookup,
            )
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun StoryParagraph(
    block: StoryViewModel.Block,
    reading: IntRange?,
    selected: StoryViewModel.WordSpan?,
    onPress: (word: Int, windowY: Float) -> Unit,
    onTap: () -> Unit,
) {
    val readingColor = MaterialTheme.colorScheme.tertiaryContainer
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val text = remember(block, reading, selected, readingColor, selectedColor) {
        buildAnnotatedString {
            append(block.text)
            if (reading != null) addStyle(SpanStyle(background = readingColor), reading.first, reading.last + 1)
            if (selected != null) addStyle(SpanStyle(background = selectedColor), selected.start, selected.end)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val press by rememberUpdatedState(onPress)
    val tap by rememberUpdatedState(onTap)
    Text(
        text,
        style = termStyle(24, ZH).copy(lineHeight = 42.sp),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .pointerInput(block) {
                detectTapGestures(
                    onLongPress = { pos ->
                        val l = layout ?: return@detectTapGestures
                        val word = wordAt(block, l, pos)
                        if (word >= 0) press(word, (coords?.positionInWindow()?.y ?: 0f) + pos.y)
                    },
                    onTap = { tap() },
                )
            },
    )
}

/** The word under [pos], or -1 (punctuation, or beside the text). */
private fun wordAt(block: StoryViewModel.Block, layout: TextLayoutResult, pos: Offset): Int {
    val offset = layout.getOffsetForPosition(pos)
    for (o in intArrayOf(offset, offset - 1)) {
        if (o !in block.text.indices) continue
        val box = layout.getBoundingBox(o)
        val slack = 6f
        if (pos.x in (box.left - slack)..(box.right + slack) && pos.y in (box.top - slack)..(box.bottom + slack)) {
            val i = block.words.indexOfFirst { o >= it.start && o < it.end }
            if (i >= 0) return i
        }
    }
    return -1
}

@Composable
private fun LookupCard(
    l: StoryViewModel.Lookup,
    onSpeak: () -> Unit,
    onAdd: (DictEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = HanziLockApp.get(LocalContext.current)
    ElevatedCard(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.span.word, style = hanziStyle(32, FontWeight.Medium), modifier = Modifier.weight(1f).padding(top = 8.dp))
                SpeakButton(onClick = onSpeak)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    l.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    l.entries.isNotEmpty() -> {
                        l.entries.forEach { e ->
                            Text(e.pinyinMarked, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(e.definitions.take(6).joinToString("; "), style = MaterialTheme.typography.bodyLarge)
                        }
                        TextButton(onClick = { onAdd(l.entries.first()) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add to my words")
                        }
                    }
                    l.characters.isNotEmpty() -> {
                        Text("Not in the dictionary as one word. Character by character:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        l.characters.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.simplified, style = hanziStyle(22))
                                Spacer(Modifier.width(8.dp))
                                Text(c.pinyinMarked, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(c.definitions.take(3).joinToString("; "), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> Text(
                        if (app.dictionary.isReady) "Not in the dictionary (perhaps a name)." else "The dictionary is still being prepared - try again in a moment.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** After the last page: questions about the story, then explaining it in your own words, all in Chinese. */
@Composable
private fun ComprehensionPage(ui: StoryViewModel.Ui, vm: StoryViewModel, onDone: () -> Unit) {
    val story = ui.story ?: return
    var showPinyin by rememberSaveable { mutableStateOf(true) }
    var showEnglish by rememberSaveable { mutableStateOf(false) }
    val questions = story.body.questions
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Did you understand it?", style = MaterialTheme.typography.titleLarge)
        Text(
            "Answer in Chinese - type it or tap the mic and say it. Claude tells you what's right and corrects your Chinese.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showPinyin, onClick = { showPinyin = !showPinyin }, label = { Text("Pinyin") })
            FilterChip(selected = showEnglish, onClick = { showEnglish = !showEnglish }, label = { Text("English") })
        }
        when {
            ui.questionsLoading -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Claude is writing questions about the story…", style = MaterialTheme.typography.bodyLarge)
            }
            questions.isEmpty() && ui.questionsError != null -> {
                Text(ui.questionsError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::loadQuestions) { Text("Try again") }
            }
        }
        questions.forEachIndexed { i, q ->
            AnswerCard("${i + 1}.", q.question, q.pinyin, q.english, i, ui, vm, showPinyin, showEnglish)
        }
        AnswerCard(
            label = "",
            prompt = "用中文说说这个故事讲了什么。",
            pinyin = "Yòng Zhōngwén shuōshuo zhège gùshi jiǎngle shénme.",
            english = "In Chinese, explain in your own words what this story is about.",
            index = EXPLANATION,
            ui = ui,
            vm = vm,
            showPinyin = showPinyin,
            showEnglish = showEnglish,
            minLines = 4,
        )
        if (ui.allReviewed) {
            val right = ui.reviews.count { it?.correct == true } + if (ui.explanationReview?.correct == true) 1 else 0
            Text("$right of ${ui.reviews.size + 1} answers were right.", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = { vm.finish(); onDone() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (story.finishedAt != null) "Back to stories" else "Finish story")
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun AnswerCard(
    label: String,
    prompt: String,
    pinyin: String,
    english: String,
    index: Int,
    ui: StoryViewModel.Ui,
    vm: StoryViewModel,
    showPinyin: Boolean,
    showEnglish: Boolean,
    minLines: Int = 2,
) {
    val dictate = rememberMicAction { vm.toggleDictation(index) }
    val answer = if (index == EXPLANATION) ui.explanation else ui.answers.getOrNull(index).orEmpty()
    val review = if (index == EXPLANATION) ui.explanationReview else ui.reviews.getOrNull(index)
    val listening = ui.listening == index
    val checking = ui.checking == index
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (label.isEmpty()) prompt else "$label $prompt", style = termStyle(20, ZH))
                    if (showPinyin && pinyin.isNotBlank()) {
                        Text(pinyin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // The explanation task is always shown in English too, so it's clear what to do.
                    if ((showEnglish || index == EXPLANATION) && english.isNotBlank()) {
                        Text(english, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SpeakButton(onClick = { vm.speak(prompt) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (listening && ui.partial.isNotEmpty()) answer + ui.partial else answer,
                    onValueChange = { vm.onAnswer(index, it) },
                    label = { Text(if (index == EXPLANATION) "Your explanation in Chinese" else "Your answer in Chinese") },
                    enabled = !listening && !checking,
                    textStyle = termStyle(19, ZH),
                    minLines = minLines,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = dictate,
                    enabled = !checking,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = if (listening) "Stop dictation" else "Dictate")
                }
            }
            ui.checkError?.takeIf { it.first == index }?.let {
                Text(it.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { vm.check(index) }, enabled = answer.isNotBlank() && ui.checking == null && !listening, modifier = Modifier.fillMaxWidth()) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (review == null) "Check with Claude" else "Check again")
            }
            review?.let { Feedback(it, showPinyin, showEnglish, onSpeak = vm::speak) }
        }
    }
}

@Composable
private fun Feedback(r: StoryAnswerReview, showPinyin: Boolean, showEnglish: Boolean, onSpeak: (String) -> Unit) {
    var english by remember(r) { mutableStateOf(showEnglish) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                if (r.correct) "✓" else "✗",
                color = if (r.correct) WinColor else LossColor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(r.feedback, style = termStyle(17, ZH))
                if (showPinyin && r.feedbackPinyin.isNotBlank()) {
                    Text(r.feedbackPinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (r.feedbackEn.isNotBlank()) {
                    if (english || showEnglish) {
                        Text(r.feedbackEn, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TextButton(onClick = { english = true }, modifier = Modifier.padding(0.dp)) { Text("Show in English") }
                    }
                }
            }
        }
        if (r.betterAnswer.isNotBlank()) {
            Text("A good answer:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.betterAnswer, style = termStyle(18, ZH, FontWeight.Medium))
                    if (showPinyin && r.betterAnswerPinyin.isNotBlank()) {
                        Text(r.betterAnswerPinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SpeakButton(onClick = { onSpeak(r.betterAnswer) })
            }
        }
    }
}
