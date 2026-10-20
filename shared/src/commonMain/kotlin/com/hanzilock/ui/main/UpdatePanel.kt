package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.platform.LocalAppCore
import kotlinx.coroutines.launch
import com.hanzilock.update.UpdateState as State

/**
 * Updates over the internet. On Home ([always] = false) it only appears when a newer build is
 * waiting; in Settings it's always there, with "Check for updates".
 */
@Composable
fun UpdatePanel(always: Boolean) {
    val updater = LocalAppCore.current.updater
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsStateWithLifecycle()
    if (!updater.enabled) {
        if (always) Text("This build doesn't know where to look for updates (it was built outside git).", style = MaterialTheme.typography.bodySmall)
        return
    }
    val release = when (val s = state) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.NeedsPermission -> s.release
        is State.Installing -> s.release
        is State.Failed -> s.release
        else -> null
    }
    if (release == null && !always) return

    Card(
        Modifier.fillMaxWidth(),
        colors = if (release != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (release != null) "Update available: build ${release.build}" else "Lingo Lock build ${updater.currentBuild}",
                style = MaterialTheme.typography.titleMedium,
            )
            release?.notes?.takeIf { it.isNotBlank() }?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall) }
            when (val s = state) {
                State.Idle -> Text("Updates are downloaded from GitHub - no cable needed.", style = MaterialTheme.typography.bodySmall)
                State.Checking -> Text("Checking for updates…", style = MaterialTheme.typography.bodyMedium)
                is State.UpToDate -> Text("You have the latest build.", style = MaterialTheme.typography.bodyMedium)
                is State.Available -> Text("Your progress and settings are kept.", style = MaterialTheme.typography.bodySmall)
                is State.Downloading -> {
                    Text("Downloading… ${s.percent}%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is State.NeedsPermission -> Text(updater.permissionMessage, style = MaterialTheme.typography.bodyMedium)
                is State.Installing -> Text(updater.installingMessage, style = MaterialTheme.typography.bodyMedium)
                is State.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = state is State.Checking || state is State.Downloading || state is State.Installing
                if (release != null) {
                    if (state is State.NeedsPermission) {
                        OutlinedButton(onClick = updater::openInstallPermission) { Text("Allow") }
                    }
                    Button(onClick = { updater.install(release) }, enabled = !busy) { Text(if (state is State.Failed) "Try again" else "Install") }
                }
                if (always) {
                    OutlinedButton(onClick = { scope.launch { updater.check() } }, enabled = !busy) { Text("Check for updates") }
                }
            }
        }
    }
}
