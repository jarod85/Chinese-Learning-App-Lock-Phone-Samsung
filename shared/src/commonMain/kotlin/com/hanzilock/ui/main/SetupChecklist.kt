package com.hanzilock.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hanzilock.platform.LocalAppCore
import com.hanzilock.ui.common.PinDialog
import com.hanzilock.ui.common.SetPinDialog
import com.hanzilock.ui.theme.WinColor

/** One step of the setup checklist; [action] is the button that does it. */
data class SetupItem(
    val title: String,
    val detail: String,
    val done: Boolean,
    val label: String,
    val required: Boolean,
    val action: () -> Unit,
    val extra: Pair<String, () -> Unit>? = null,
)

/** First-run checklist: [intro], the master PIN, then the device's own [items]. "Done" needs the PIN. */
@Composable
fun SetupChecklist(nav: Navigator, intro: String, items: List<SetupItem>) {
    val app = LocalAppCore.current
    var setPin by remember { mutableStateOf(false) }
    var confirmOldPin by remember { mutableStateOf(false) }
    val pinItem = SetupItem(
        "Master PIN",
        "Your emergency exit: ends a session early, pauses the lock, protects settings.",
        app.pin.isSet, if (app.pin.isSet) "Change" else "Set PIN", true,
        { if (app.pin.isSet) confirmOldPin = true else setPin = true },
    )

    ScreenScaffold("Setup", onBack = if (nav.stack.size > 1) nav::back else null) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(intro, style = MaterialTheme.typography.bodyMedium)
            (listOf(pinItem) + items).forEach { SetupRow(it) }
            Button(
                onClick = { nav.tab(Route.Home) },
                enabled = app.pin.isSet,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (app.pin.isSet) "Done" else "Set a PIN to continue") }
        }
    }

    if (confirmOldPin) {
        PinDialog(
            title = "Current PIN",
            message = "Enter your current master PIN to change it.",
            onDismiss = { confirmOldPin = false },
            onVerified = { confirmOldPin = false; setPin = true },
        )
    }
    if (setPin) {
        SetPinDialog(onDismiss = { setPin = false }, onSet = { pin ->
            app.pin.setPin(pin)
            setPin = false
        })
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = if (item.done) WinColor else if (item.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (item.done) "✓" else if (item.required) "!" else "·", color = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!item.done || item.label == "Change") FilledTonalButton(onClick = item.action) { Text(item.label) }
                    item.extra?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                }
            }
        }
    }
}

/** "Hello" in [code], to try the voice. */
fun greeting(code: String): String = when (code) {
    "zh", "yue" -> "你好"
    "ja" -> "こんにちは"
    "ko" -> "안녕하세요"
    "es" -> "hola"
    "fr" -> "bonjour"
    "it" -> "ciao"
    "de" -> "hallo"
    "pt" -> "olá"
    "ru" -> "привет"
    else -> "hello"
}
