package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.sync.SyncManager.State
import com.hanzilock.ui.common.formatDateTime
import kotlinx.coroutines.launch

/** Settings > Sync: the private GitHub repository your phone and Mac share their words, progress and stories through. */
@Composable
fun SyncPanel() {
    val app = LocalAppCore.current
    val s = app.settings
    val scope = rememberCoroutineScope()
    val state by app.sync.state.collectAsStateWithLifecycle()
    var repo by remember { mutableStateOf(s.syncRepo) }
    var token by remember { mutableStateOf(s.syncToken) }
    var name by remember { mutableStateOf(app.sync.deviceName) }
    val owner = app.updater.repo.substringBefore('/').ifBlank { "you" }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Keeps your words, sets, progress, stats, stories, languages and schedule the same on your phone and your Mac, " +
                "through a private GitHub repository. Each device needs the repository's name and a GitHub token that can " +
                "read and write it (README: \"Sync between your phone and Mac\"). A practice session finished on one device " +
                "unlocks the other until its next reset time. The master PIN, Claude key and allowed apps stay per device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = repo, onValueChange = { repo = it.trim() }, label = { Text("Repository") },
            placeholder = { Text("$owner/lingo-lock-data") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() }, label = { Text("GitHub token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("This device's name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state != State.Syncing && repo.contains('/') && token.isNotBlank(),
                onClick = {
                    s.syncRepo = repo
                    s.syncToken = token
                    s.syncDeviceName = name
                    scope.launch { app.sync.syncNow() }
                },
            ) { Text("Save & sync now") }
            if (s.syncRepo.isNotBlank()) {
                TextButton(onClick = {
                    s.syncRepo = ""
                    repo = ""
                    scope.launch { app.sync.syncNow() }
                }) { Text("Turn sync off") }
            }
        }
        when (val st = state) {
            State.Off -> Text("Sync is off.", style = MaterialTheme.typography.bodyMedium)
            State.Syncing -> Text("Syncing…", style = MaterialTheme.typography.bodyMedium)
            is State.Done -> Text(
                if (st.at == 0L) "Not synced yet." else "Synced ${formatDateTime(st.at)}" + when (st.otherDevices) {
                    0 -> " · no other device yet"
                    1 -> " · with 1 other device"
                    else -> " · with ${st.otherDevices} other devices"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            is State.Failed -> Text(st.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
