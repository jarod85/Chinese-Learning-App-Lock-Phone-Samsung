package com.hanzilock.ui.lock

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.core.PriorityEmail
import com.hanzilock.lock.PriorityEmailListener
import com.hanzilock.ui.common.AppIcon
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.common.appLabel
import com.hanzilock.ui.quiz.QuizContent
import com.hanzilock.ui.quiz.QuizViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(vm: QuizViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
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
                    Text("练习时间 · Practice time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Finish the words to unlock your phone", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { askPin = true }) { Text("PIN") }
            }
            PriorityEmails()
            QuizContent(vm, onDone = onClose, modifier = Modifier.weight(1f).imePadding())
            AllowedApps()
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

/** Emails that match your priority rules; tapping one opens it (the email app gets a short pass). */
@Composable
private fun PriorityEmails() {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val emails by app.emailPasses.inbox.collectAsStateWithLifecycle()
    if (emails.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Priority email", style = MaterialTheme.typography.labelLarge)
            emails.take(3).forEach { email -> EmailRow(email) { PriorityEmailListener.open(context, email) } }
        }
    }
}

@Composable
private fun EmailRow(email: PriorityEmail, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Email, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(email.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (email.text.isNotBlank()) {
                Text(email.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Shortcuts to the apps that keep working while locked (phone, messages, clock, ...). */
@Composable
private fun AllowedApps() {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val apps by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            app.allowList.shortcutPackages().map { it to appLabel(context, it) }
        }
    }
    if (apps.isEmpty()) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
            HorizontalDivider()
            Text(
                "Still available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(apps, key = { it.first }) { (pkg, label) ->
                    Column(
                        Modifier.width(68.dp).clickable { launchApp(context, pkg) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(pkg, 36.dp)
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun launchApp(context: Context, pkg: String) {
    context.packageManager.getLaunchIntentForPackage(pkg)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?.let { runCatching { context.startActivity(it) } }
}
