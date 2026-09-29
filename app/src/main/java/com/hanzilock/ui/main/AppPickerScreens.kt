package com.hanzilock.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanzilock.HanziLockApp
import com.hanzilock.core.AllowList
import com.hanzilock.core.EmailRules
import com.hanzilock.ui.common.AppIcon
import com.hanzilock.ui.common.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

/** Every app with a launcher icon, sorted by name (HanziLock itself excluded). */
fun launchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return infos.map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AllowedAppsScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val apps by produceState(emptyList<InstalledApp>()) { value = withContext(Dispatchers.IO) { launchableApps(context) } }
    var selected by remember { mutableStateOf(app.allowList.allowedApps()) }
    var query by remember { mutableStateOf("") }
    val always = remember { (AllowList.ALWAYS_ALLOWED + app.allowList.essentialDescription()).toSet() }

    fun toggle(pkg: String, on: Boolean) {
        selected = if (on) selected + pkg else selected - pkg
        app.settings.allowedPackages = selected
    }

    ScreenScaffold("Allowed while locked", onBack = nav::back) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Calls, the keyboard, emergency SOS, notifications and system screens always work, and so do " +
                            "your default phone and SMS apps. Tick anything else you want to use while practice is due. " +
                            "Everything unticked (including the home screen) shows the practice screen instead.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        selected = app.allowList.installedDefaults()
                        app.settings.allowedPackages = selected
                    }) { Text("Reset to defaults (contacts, clock, calendar, camera, maps, wallet)") }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val q = query.trim().lowercase()
            items(apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }, key = { it.packageName }) { a ->
                val locked = a.packageName in always
                val checked = locked || a.packageName in selected
                ListItem(
                    modifier = Modifier.clickable(enabled = !locked) { toggle(a.packageName, !checked) },
                    leadingContent = { AppIcon(a.packageName) },
                    headlineContent = { Text(a.label) },
                    supportingContent = { Text(if (locked) "Always allowed" else a.packageName, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Checkbox(checked = checked, enabled = !locked, onCheckedChange = { toggle(a.packageName, it) }) },
                )
            }
        }
    }
}

@Composable
fun PriorityEmailScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = HanziLockApp.get(context)
    val resumed = rememberResumeCount()
    val access = remember(resumed) { SystemAccess.notificationAccess(context) }
    val apps by produceState(emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) {
            launchableApps(context).filter {
                it.packageName in EmailRules.KNOWN_EMAIL_APPS || it.label.contains("mail", ignoreCase = true)
            }
        }
    }
    var selected by remember { mutableStateOf(app.allowList.emailApps()) }
    var rules by remember { mutableStateOf(app.settings.emailRules.joinToString("\n")) }
    var minutes by remember { mutableIntStateOf(app.settings.emailPassMinutes) }
    var saved by remember { mutableStateOf(false) }

    ScreenScaffold("Priority emails", onBack = nav::back) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "Notifications are never hidden. While practice is due, opening an email notification that matches a " +
                        "rule below lets its email app open for a few minutes; matching emails are also listed on the " +
                        "practice screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (access) "Notification access: on ✓" else "Notification access is off",
                        modifier = Modifier.weight(1f),
                        color = if (access) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (!access) FilledTonalButton(onClick = { SystemAccess.openNotificationAccess(context) }) { Text("Turn on") }
                }
                SectionTitle("Rules (one per line)")
                OutlinedTextField(
                    value = rules,
                    onValueChange = { rules = it; saved = false },
                    placeholder = { Text("me@work.com\nJane Doe\nInvoice") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A rule matches if it appears anywhere in the notification: the account the email was sent to " +
                        "(e.g. me@work.com - Gmail and Outlook show it), the sender's name, or a word in the subject. " +
                        "Use * to let every email through.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Email app stays open for", modifier = Modifier.weight(1f))
                    TextButton(onClick = { minutes = (minutes - 1).coerceAtLeast(1); saved = false }) { Text("−") }
                    Text("$minutes min")
                    TextButton(onClick = { minutes = (minutes + 1).coerceAtMost(60); saved = false }) { Text("+") }
                }
                Button(
                    onClick = {
                        app.settings.emailRules = EmailRules.parse(rules)
                        app.settings.emailPassMinutes = minutes
                        app.settings.emailPackages = selected
                        saved = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text(if (saved) "Saved ✓" else "Save") }
                SectionTitle("Email apps")
                if (apps.isEmpty()) Text("No email apps found.", style = MaterialTheme.typography.bodyMedium)
            }
            items(apps, key = { it.packageName }) { a ->
                val checked = a.packageName in selected
                ListItem(
                    modifier = Modifier.clickable {
                        selected = if (checked) selected - a.packageName else selected + a.packageName
                        saved = false
                    },
                    leadingContent = { AppIcon(a.packageName) },
                    headlineContent = { Text(a.label) },
                    trailingContent = {
                        Checkbox(checked = checked, onCheckedChange = {
                            selected = if (it) selected + a.packageName else selected - a.packageName
                            saved = false
                        })
                    },
                )
            }
        }
    }
}
