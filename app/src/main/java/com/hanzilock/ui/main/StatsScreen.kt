package com.hanzilock.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanzilock.HanziLockApp
import com.hanzilock.data.Attempt
import com.hanzilock.data.DayStat
import com.hanzilock.data.Totals
import com.hanzilock.data.Word
import com.hanzilock.ui.common.SectionTitle
import com.hanzilock.ui.theme.LossColor
import com.hanzilock.ui.theme.WinColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private data class StatsData(
    val totals: Totals,
    val days: List<DayStat>,
    val weakest: List<Word>,
    val losses: List<Attempt>,
)

/** Wins, losses and the loss log. */
@Composable
fun StatsScreen(nav: Navigator) {
    val app = HanziLockApp.get(LocalContext.current)
    val version by app.words.changes.collectAsStateWithLifecycle()
    val settingsVersion by app.settings.changes.collectAsStateWithLifecycle()
    val lang = remember(settingsVersion) { app.languages.active }
    val data by produceState<StatsData?>(null, version, lang.code) {
        value = withContext(Dispatchers.IO) {
            StatsData(app.words.totals(lang.code), app.words.dailyStats(lang.code, 14), app.words.weakest(lang.code, 8), app.words.recentLosses(lang.code, 60))
        }
    }

    ScreenScaffold("Stats · ${lang.name}") { padding ->
        val d = data ?: return@ScreenScaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                val t = d.totals
                val graded = t.wins + t.losses
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Words", "${t.seen}", "practised of ${t.words} on", Modifier.weight(1f))
                    StatTile("Accuracy", if (graded == 0) "-" else "${t.wins * 100 / graded}%", "$graded answers", Modifier.weight(1f))
                    StatTile("Streak", "${t.streakDays}", "days", Modifier.weight(1f))
                    StatTile("Sessions", "${t.sessionsCompleted}", "${t.sessionsSkipped} skipped", Modifier.weight(1f))
                }
            }
            item {
                SectionTitle("Last 14 days")
                DayBars(d.days)
            }
            if (d.weakest.isNotEmpty()) {
                item { SectionTitle("Weakest words") }
                items(d.weakest, key = { "w${it.id}" }) { w ->
                    WordRow(w, lang) { nav.go(Route.WordDetail(w.id)) }
                    HorizontalDivider()
                }
            }
            item { SectionTitle("Loss log") }
            if (d.losses.isEmpty()) item { Text("No losses recorded yet.", style = MaterialTheme.typography.bodyMedium) }
            items(d.losses, key = { "l${it.id}" }) { a ->
                AttemptRow(a, showTerm = true, onClick = { nav.go(Route.WordDetail(a.wordId)) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

/** Stacked daily bars: green = right, red = missed. */
@Composable
private fun DayBars(days: List<DayStat>) {
    val max = (days.maxOfOrNull { it.wins + it.losses } ?: 0).coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(150.dp).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val total = d.wins + d.losses
                    if (total > 0) Text("$total", style = MaterialTheme.typography.labelSmall)
                    Column(Modifier.fillMaxWidth().height((100f * total / max).dp)) {
                        if (d.losses > 0) Box(Modifier.fillMaxWidth().weight(d.losses.toFloat()).background(LossColor))
                        if (d.wins > 0) Box(Modifier.fillMaxWidth().weight(d.wins.toFloat()).background(WinColor))
                    }
                    Text(
                        LocalDate.ofEpochDay(d.epochDay).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
