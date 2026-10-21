package com.hanzilock.ui.stories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanzilock.data.DictEntry
import com.hanzilock.data.WordScope
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.stories.Story
import com.hanzilock.stories.StoryAnswerReview
import com.hanzilock.ui.common.AppIcons
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.common.rememberMicAction
import com.hanzilock.ui.main.DictRow
import com.hanzilock.ui.main.Navigator
import com.hanzilock.ui.main.Route
import com.hanzilock.ui.main.ScreenScaffold
import com.hanzilock.ui.stories.StoryViewModel.Companion.EXPLANATION
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import com.hanzilock.ui.theme.ZhLocale
import com.hanzilock.ui.theme.hanziStyle
import com.hanzilock.ui.theme.termStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val ZH = "zh-CN"
private const val STORY_TEXT_SIZE = 24

/**
 * A story, page by page. Point at a word (with a mouse) for its pinyin and meaning; click or tap it
 * to see what its sentence means; press and hold it (or right-click) for the dictionary card. The
 * panel at the top shows the sentence's translation, or the whole page's; the search finds words in
 * the dictionary and in the story; the speaker button reads the story aloud. After the last page
 * come the comprehension questions.
 */
@Composable
fun StoryReaderScreen(nav: Navigator, id: Long) {
    val app = LocalAppCore.current
    val vm: StoryViewModel = viewModel(key = "story-$id") { StoryViewModel(app) }
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

    // On a computer's keyboard: ← and → turn the pages, ⌘F searches, Esc closes the search.
    LocalPlatformUi.current.KeyShortcuts { e ->
        val step = when (e.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> 0
        }
        when {
            e.type != KeyEventType.KeyDown -> false
            e.key == Key.F && (e.isMetaPressed || e.isCtrlPressed) -> true.also { vm.openSearch() }
            e.key == Key.Escape && vm.ui.value.searching -> true.also { vm.closeSearch() }
            step != 0 -> true.also { scope.launch { pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, pageCount - 1)) } }
            else -> false
        }
    }
    val jumpTo: (Int) -> Unit = { page -> scope.launch { pager.animateScrollToPage(page.coerceIn(0, pageCount - 1)) } }
    val addWord: (DictEntry) -> Unit = { e -> nav.go(Route.WordEdit(null, e)) }

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
            IconButton(onClick = { if (ui.searching) vm.closeSearch() else vm.openSearch() }) {
                Icon(Icons.Default.Search, contentDescription = "Search for a word")
            }
            if (onStoryPage) {
                TextButton(onClick = vm::toggleSlow) { Text(if (ui.slow) "Slow" else "Normal") }
                IconButton(onClick = { if (ui.reading) vm.stopReading() else vm.play(pager.currentPage) }) {
                    Icon(
                        if (ui.reading) AppIcons.Pause else AppIcons.VolumeUp,
                        contentDescription = if (ui.reading) "Pause" else "Read aloud",
                    )
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // The search goes beside the story when the window is wide enough, over it on a phone.
            val searchBeside = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                        areaTop = it.positionInWindow().y
                        areaHeight = it.size.height.toFloat().coerceAtLeast(1f)
                    },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (onStoryPage) {
                            ReadingPanel(ui, pager.currentPage, vm, addWord, Modifier.fillMaxWidth().fillMaxHeight(0.3f))
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
                            onAdd = addWord,
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
                if (ui.searching && searchBeside) {
                    VerticalDivider()
                    SearchPanel(ui, vm, addWord, jumpTo, Modifier.width(380.dp).fillMaxHeight())
                }
            }
            if (ui.searching && !searchBeside) {
                // Over the story: picking a word that's in it shows you where (the search keeps what you typed).
                Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxSize()) {
                    SearchPanel(ui, vm, addWord, onJump = { page -> vm.hideSearch(); jumpTo(page) }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * The top section, as in Du Chinese: what the sentence you clicked means (or the translation of the
 * whole page), and the meaning of the word you last pointed at or clicked.
 */
@Composable
private fun ReadingPanel(ui: StoryViewModel.Ui, page: Int, vm: StoryViewModel, onAdd: (DictEntry) -> Unit, modifier: Modifier = Modifier) {
    val mouse = LocalPlatformUi.current.hasMouse
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // Fixed heights, so the text below never moves while you point at words.
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelLabel(if (ui.showEnglish) "Page translation" else "Sentence meaning", Modifier.weight(1f))
                    TextButton(onClick = vm::toggleEnglish) { Text(if (ui.showEnglish) "One sentence" else "Whole page") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = 10.dp, bottom = 6.dp)) {
                    SentenceMeaning(ui, page, mouse)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WordMeaning(ui, vm, onAdd, mouse, Modifier.fillMaxWidth().height(88.dp))
        }
    }
}

@Composable
private fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun BoxScope.SentenceMeaning(ui: StoryViewModel.Ui, page: Int, mouse: Boolean) {
    val story = ui.story ?: return
    val scroll = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    if (ui.showEnglish) {
        val sentences = ui.pages.getOrNull(page)?.sentences ?: IntRange.EMPTY
        val translated = sentences.map { story.body.chunks[it] }
        if (translated.none { it.en.isNotBlank() }) {
            NoTranslation(ui)
            return
        }
        val text = buildAnnotatedString {
            translated.forEachIndexed { k, c ->
                if (k > 0) append(if (c.p) "\n\n" else " ")
                val i = sentences.first + k
                val start = length
                append(c.en.ifBlank { "…" })
                if ((ui.reading && i == ui.readingSentence) || i == ui.selectedSentence) {
                    addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                }
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = scroll)
        return
    }
    val en = story.body.chunks.getOrNull(ui.selectedSentence)?.en
    when {
        en == null -> Text(
            if (mouse) "Click a word to see what its sentence means." else "Tap a word to see what its sentence means.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        en.isNotBlank() -> Text(en, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), modifier = scroll)
        else -> NoTranslation(ui)
    }
}

/** While Claude translates, or why there's no translation. */
@Composable
private fun BoxScope.NoTranslation(ui: StoryViewModel.Ui) {
    if (ui.translating) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Translating…", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Text(
            ui.translateError ?: "No translation yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.translateError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun WordMeaning(ui: StoryViewModel.Ui, vm: StoryViewModel, onAdd: (DictEntry) -> Unit, mouse: Boolean, modifier: Modifier = Modifier) {
    val app = LocalAppCore.current
    val word = ui.shown?.span?.word
    Column(modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp)) {
        PanelLabel("Word meaning")
        if (word == null) {
            Text(
                if (mouse) "Point at a word to see what it means." else "Tap a word to see what it means. Press and hold it for more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }
        val m = ui.meanings[word]
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, localeList = ZhLocale)) { append(word) }
            append("  ")
            when {
                m == null -> append(if (app.dictionary.isReady) "…" else "The dictionary is still being prepared.")
                m.entries.isNotEmpty() -> {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append(m.pinyin) }
                    append("  ")
                    append(m.entries.first().definitions.take(5).joinToString("; "))
                }
                m.characters.isNotEmpty() -> m.characters.forEachIndexed { i, c ->
                    if (i > 0) append("   ")
                    withStyle(SpanStyle(localeList = ZhLocale)) { append(c.simplified) }
                    append(" ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append(c.pinyinMarked) }
                    append(" ")
                    append(c.definitions.take(2).joinToString("; "))
                }
                else -> append("Not in the dictionary (perhaps a name).")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SpeakButton(onClick = { vm.speakWord(word) })
            m?.entries?.firstOrNull()?.let { e ->
                IconButton(onClick = { onAdd(e) }) { Icon(Icons.Default.Add, contentDescription = "Add to my words") }
            }
        }
    }
}

@Composable
private fun StoryPage(ui: StoryViewModel.Ui, pageIndex: Int, vm: StoryViewModel, lowerHalf: (Float) -> Boolean) {
    val page = ui.pages[pageIndex]
    // The word under the pointer (or else the one you clicked) is coloured, with its pinyin above it.
    val pointed = (ui.hovered ?: ui.selected)?.takeIf { it.page == pageIndex }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        page.blocks.forEachIndexed { b, block ->
            val word = pointed?.takeIf { it.block == b }?.span
            StoryParagraph(
                block = block,
                reading = if (ui.reading) block.sentences.firstOrNull { it.first == ui.readingSentence }?.second else null,
                sentence = block.sentences.firstOrNull { it.first == ui.selectedSentence }?.second,
                pressed = ui.lookup?.takeIf { it.page == pageIndex && it.block == b }?.span,
                pointed = word,
                pinyin = word?.let { ui.meanings[it.word]?.pinyin?.takeIf(String::isNotBlank) },
                marked = ui.marked,
                onPress = { w, y -> vm.press(pageIndex, b, w, lowerHalf(y)) },
                onHover = { w -> vm.hover(pageIndex, b, w) },
                onTap = { w ->
                    if (w >= 0) {
                        vm.select(pageIndex, b, w)
                    } else {
                        vm.dismissLookup()
                        vm.clearSelection()
                    }
                },
            )
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun StoryParagraph(
    block: StoryViewModel.Block,
    /** The sentence being read aloud. */
    reading: IntRange?,
    /** The sentence of the word you clicked. */
    sentence: IntRange?,
    /** The word whose dictionary card is open. */
    pressed: StoryViewModel.WordSpan?,
    pointed: StoryViewModel.WordSpan?,
    pinyin: String?,
    /** A word you searched for, marked wherever it is. */
    marked: String?,
    onPress: (word: Int, windowY: Float) -> Unit,
    onHover: (word: Int) -> Unit,
    onTap: (word: Int) -> Unit,
) {
    val readingColor = MaterialTheme.colorScheme.tertiaryContainer
    val sentenceColor = MaterialTheme.colorScheme.surfaceVariant
    val pressedColor = MaterialTheme.colorScheme.primaryContainer
    val markedColor = MaterialTheme.colorScheme.secondaryContainer
    val pointedColor = MaterialTheme.colorScheme.primary
    val text = remember(block, reading, sentence, pressed, pointed, marked, readingColor, sentenceColor, pressedColor, markedColor, pointedColor) {
        buildAnnotatedString {
            append(block.text)
            if (sentence != null) addStyle(SpanStyle(background = sentenceColor), sentence.first, sentence.last + 1)
            if (!marked.isNullOrEmpty()) {
                var i = block.text.indexOf(marked)
                while (i >= 0) {
                    addStyle(SpanStyle(background = markedColor), i, i + marked.length)
                    i = block.text.indexOf(marked, i + marked.length)
                }
            }
            if (reading != null) addStyle(SpanStyle(background = readingColor), reading.first, reading.last + 1)
            if (pressed != null) addStyle(SpanStyle(background = pressedColor), pressed.start, pressed.end)
            if (pointed != null) addStyle(SpanStyle(color = pointedColor), pointed.start, pointed.end)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val press by rememberUpdatedState(onPress)
    val hover by rememberUpdatedState(onHover)
    val tap by rememberUpdatedState(onTap)
    Box(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = termStyle(STORY_TEXT_SIZE, ZH).copy(lineHeight = 42.sp),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords = it }
                .pointerInput(block) {
                    // With a mouse: pointing at a word shows its meaning; a right-click opens its dictionary card.
                    awaitPointerEventScope {
                        var last = -1
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.first()
                            if (change.type != PointerType.Mouse) continue
                            val word = layout?.let { wordAt(block, it, change.position) } ?: -1
                            when (event.type) {
                                PointerEventType.Move, PointerEventType.Enter -> if (word != last) {
                                    last = word
                                    hover(word)
                                }
                                PointerEventType.Exit -> if (last != -1) {
                                    last = -1
                                    hover(-1)
                                }
                                PointerEventType.Press -> if (event.buttons.isSecondaryPressed && word >= 0) {
                                    event.changes.forEach { it.consume() }
                                    press(word, (coords?.positionInWindow()?.y ?: 0f) + change.position.y)
                                }
                            }
                        }
                    }
                }
                .pointerInput(block) {
                    detectTapGestures(
                        onLongPress = { pos ->
                            val l = layout ?: return@detectTapGestures
                            val word = wordAt(block, l, pos)
                            if (word >= 0) press(word, (coords?.positionInWindow()?.y ?: 0f) + pos.y)
                        },
                        onTap = { pos -> tap(layout?.let { wordAt(block, it, pos) } ?: -1) },
                    )
                },
        )
        val l = layout
        if (pointed != null && pinyin != null && l != null && pointed.end <= l.layoutInput.text.length) PinyinAbove(pinyin, l, pointed)
    }
}

/** [pinyin] centred just above [span]'s characters (on its first line), without moving the text. */
@Composable
private fun PinyinAbove(pinyin: String, layout: TextLayoutResult, span: StoryViewModel.WordSpan) {
    val line = layout.getLineForOffset(span.start)
    val lastOnLine = minOf(span.end, layout.getLineEnd(line, visibleEnd = true)) - 1
    val centerX = (layout.getBoundingBox(span.start).left + layout.getBoundingBox(lastOnLine.coerceAtLeast(span.start)).right) / 2
    // The characters sit in the middle of their (taller) line.
    val glyphTop = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2 - with(LocalDensity.current) { (STORY_TEXT_SIZE / 2).sp.toPx() }
    Text(
        pinyin,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity))
            layout(0, 0) { p.place((centerX - p.width / 2f).roundToInt(), (glyphTop - p.height).roundToInt()) }
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
    val app = LocalAppCore.current
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

/**
 * Searching the dictionary while you read: characters, pinyin or English. Words that are in the story
 * come first and are marked in the text; click one to go to the page it's on.
 */
@Composable
private fun SearchPanel(ui: StoryViewModel.Ui, vm: StoryViewModel, onAdd: (DictEntry) -> Unit, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppCore.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val version by app.words.changes.collectAsStateWithLifecycle()
    val known by produceState(emptySet<String>(), version) {
        value = withContext(Dispatchers.IO) { app.words.list("zh", scope = WordScope.Practising, limit = Int.MAX_VALUE).map { it.term }.toSet() }
    }
    Column(modifier) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::closeSearch) { Icon(Icons.Default.Close, contentDescription = "Close the search") }
        }
        OutlinedTextField(
            value = ui.searchQuery,
            onValueChange = vm::search,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("汉字, pinyin or English") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
        )
        ui.marked?.let { term ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(term, style = termStyle(17, ZH), color = MaterialTheme.colorScheme.primary)
                Text(
                    if (ui.markedPages.isEmpty()) " isn't in this story." else " in this story:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ui.markedPages.forEach { p -> TextButton(onClick = { onJump(p) }) { Text("page ${p + 1}") } }
            }
        }
        when {
            ui.searchQuery.isBlank() -> Text(
                "Look a word up in the dictionary by its characters, pinyin (xuexi or xue2xi2) or English. " +
                    "Words that are in this story come first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            ui.searchResults.isEmpty() && !app.dictionary.isReady -> Text(
                "The dictionary is still being prepared - try again in a moment.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ui.searchResults, key = { it.entry.id }) { hit ->
                val inStory = hit.pages.isNotEmpty()
                DictRow(
                    entry = hit.entry,
                    inList = hit.entry.simplified in known,
                    onAdd = { onAdd(hit.entry) },
                    onSpeak = { vm.speakWord(hit.entry.simplified) },
                    modifier = if (inStory) {
                        Modifier.clickable {
                            vm.mark(hit.entry.simplified)
                            onJump(hit.pages.first())
                        }
                    } else Modifier,
                    note = if (inStory) "In this story: " + pagesLabel(hit.pages) else null,
                )
                HorizontalDivider()
            }
        }
    }
}

/** "page 3", "pages 1, 4 and 6" (pages counted from 0). */
private fun pagesLabel(pages: List<Int>): String {
    val shown = pages.take(6).map { (it + 1).toString() }
    return when {
        shown.size == 1 -> "page ${shown.single()}"
        pages.size > shown.size -> "pages ${shown.joinToString(", ")}…"
        else -> "pages ${shown.dropLast(1).joinToString(", ")} and ${shown.last()}"
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
                    Icon(AppIcons.Mic, contentDescription = if (listening) "Stop dictation" else "Dictate")
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
