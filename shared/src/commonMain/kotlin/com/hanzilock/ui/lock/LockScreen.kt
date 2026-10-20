package com.hanzilock.ui.lock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.platform.LocalPlatformUi
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.quiz.QuizContent
import com.hanzilock.ui.quiz.QuizViewModel

@Composable
fun LockScreen(vm: QuizViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val app = LocalAppCore.current
    val platformUi = LocalPlatformUi.current
    var askPin by remember { mutableStateOf(false) }
    var showEscape by remember { mutableStateOf(false) }

    LaunchedEffect(ui.phase) {
        if (ui.phase == QuizViewModel.Phase.CLOSED) onClose()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Practice time · ${ui.language?.native ?: app.languages.active.native}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Finish the words to unlock your ${platformUi.deviceName}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { askPin = true }) { Text("PIN") }
            }
            platformUi.LockScreenTop()
            QuizContent(vm, onDone = onClose, modifier = Modifier.weight(1f).imePadding())
            platformUi.LockScreenBottom()
        }
    }

    if (askPin) {
        PinDialog(
            title = "Master PIN",
            message = "Stop practising without finishing?",
            onDismiss = { askPin = false },
            onVerified = { askPin = false; showEscape = true },
        )
    }
    if (showEscape) {
        AlertDialog(
            onDismissRequest = { showEscape = false },
            title = { Text("Unlock") },
            text = {
                Column {
                    EscapeOption("Skip this session", "Unlocks until the next reset time (logged as skipped)") {
                        showEscape = false; vm.skipWithPin()
                    }
                    EscapeOption("Pause for 1 hour", "This session comes back afterwards") { showEscape = false; vm.pauseLock(60) }
                    EscapeOption("Pause for 3 hours", null) { showEscape = false; vm.pauseLock(180) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showEscape = false }) { Text("Keep practising") } },
        )
    }
}

@Composable
private fun EscapeOption(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
