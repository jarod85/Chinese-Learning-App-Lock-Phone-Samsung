package com.hanzilock.ui.tutor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.ui.common.SpeakButton
import com.hanzilock.ui.theme.ZhLocale
import com.hanzilock.ui.theme.hanziStyle
import com.hanzilock.ui.theme.termStyle

/** Height of the tutor's bar (all there is of it while it's closed). */
val TutorBarHeight = 56.dp

/** Width of the tutor's panel in a wide window. */
val TutorPanelWidth = 360.dp

/** How far the panel stays from the right edge of a wide window. */
val TutorPanelMargin = 24.dp

/**
 * Narrow screens (the phone): the closed tutor as a bar across the bottom, above the tabs. Tapping
 * it opens the conversation full screen (see [TutorDock]).
 */
@Composable
fun TutorBar(vm: TutorViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Header(ui, onClick = vm::open) {
                IconButton(onClick = vm::open) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open the tutor") }
            }
        }
    }
}

/**
 * The tutor's panel, like LinkedIn's messaging. In a wide window (the Mac) it is docked in the
 * bottom-right corner: its bar is always there, and clicking it opens the conversation above it.
 * On narrow screens the bar is [TutorBar] and the open conversation fills the screen. Esc (Mac)
 * or Back (phone) closes it again.
 */
@Composable
fun TutorDock(vm: TutorViewModel, wide: Boolean, maxHeight: Dp) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.open) LocalPlatformUi.current.BackHandler(enabled = true) { vm.close() }

    if (wide) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(end = TutorPanelMargin).width(TutorPanelWidth).animateContentSize(),
            ) {
                Column {
                    Header(ui, onClick = vm::toggle) {
                        if (ui.open && ui.messages.isNotEmpty()) NewConversationButton(vm)
                        IconButton(onClick = vm::toggle) {
                            if (ui.open) Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close the tutor")
                            else Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open the tutor")
                        }
                    }
                    if (ui.open) {
                        val height = (maxHeight - 48.dp).coerceAtMost(620.dp) - TutorBarHeight
                        Conversation(ui, vm, Modifier.height(height))
                    }
                }
            }
        }
    } else {
        AnimatedVisibility(
            visible = ui.open,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    Header(ui, onClick = vm::close) {
                        if (ui.messages.isNotEmpty()) NewConversationButton(vm)
                        IconButton(onClick = vm::close) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close the tutor") }
                    }
                    Conversation(ui, vm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Header(ui: TutorViewModel.Ui, onClick: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val reply = ui.lastReply
    val subtitle = when {
        ui.answering -> "Answering…"
        !ui.open && reply != null -> reply.lineSequence().first { it.isNotBlank() }
        else -> "Ask in any language · answers in Chinese"
    }
    Row(
        Modifier.fillMaxWidth().height(TutorBarHeight).clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("师", style = hanziStyle(18, FontWeight.Medium), color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Chinese tutor", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(localeList = ZhLocale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

@Composable
private fun NewConversationButton(vm: TutorViewModel) {
    IconButton(onClick = vm::newConversation) { Icon(Icons.Default.Add, contentDescription = "New conversation") }
}

@Composable
private fun Conversation(ui: TutorViewModel.Ui, vm: TutorViewModel, modifier: Modifier) {
    val platformUi = LocalPlatformUi.current
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // Newest at the bottom: a reply that is still being written grows upwards and stays in view.
    LaunchedEffect(ui.messages.size) { if (ui.messages.isNotEmpty()) list.scrollToItem(0) }
    LaunchedEffect(Unit) {
        if (platformUi.hasMouse) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (ui.messages.isEmpty()) {
            Suggestions(onAsk = vm::send, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                itemsIndexed(ui.messages.asReversed()) { i, m -> Bubble(m, writing = i == 0 && ui.answering, speak = vm::speak) }
            }
        }
        ui.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ui.input,
                onValueChange = vm::onInput,
                placeholder = { Text("Ask in any language…") },
                textStyle = termStyle(16, "zh-CN"),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                    // A computer's keyboard: Enter sends, Shift+Enter starts a new line.
                    if (platformUi.hasMouse && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        vm.send()
                        true
                    } else {
                        false
                    }
                },
            )
            IconButton(onClick = { vm.send() }, enabled = ui.input.isNotBlank() && !ui.answering) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun Suggestions(onAsk: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Ask about a word, grammar, pronunciation or a sentence you're reading, in any language. The tutor answers only in Chinese.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Try:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        TutorViewModel.SUGGESTIONS.forEach { question ->
            OutlinedCard(onClick = { onAsk(question) }, modifier = Modifier.fillMaxWidth()) {
                Text(question, style = termStyle(15, "zh-CN"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

/** One message: yours on the right, the tutor's on the left with a button to hear it. */
@Composable
private fun Bubble(m: TutorViewModel.Message, writing: Boolean, speak: (String) -> Unit) {
    val mine = m.fromLearner
    Row(
        Modifier.fillMaxWidth().padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 8.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp,
            ),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            when {
                mine -> Text(m.text, style = termStyle(16, "zh-CN"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                m.text.isEmpty() -> CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                else -> SelectionContainer {
                    Text(m.text, style = termStyle(17, "zh-CN"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        if (!mine) {
            if (writing || m.text.isEmpty()) Spacer(Modifier.width(40.dp))
            else SpeakButton(onClick = { speak(m.text) }, size = 20.dp)
        }
    }
}
