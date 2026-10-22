package io.github.andreadp271.parkmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp271.parkmap.data.Chip
import io.github.andreadp271.parkmap.data.Describe
import io.github.andreadp271.parkmap.data.Library
import io.github.andreadp271.parkmap.data.OsmElement
import io.github.andreadp271.parkmap.data.distanceM
import io.github.andreadp271.parkmap.data.t

/** Scheda di un parcheggio (equivalente del popup della web app): tipo, costo, orari, tariffe, accesso, stima e azioni. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElementCard(el: OsmElement, onReport: () -> Unit) {
    val ctx = LocalContext.current
    val fix by UiState.fix.collectAsState()
    val favorites by Library.favorites.collectAsState()
    val anchor = el.anchor
    val dist = if (fix != null && anchor != null) distanceM(fix!!.lat, fix!!.lon, anchor.lat, anchor.lon) else null
    val d = remember(el, dist != null) { Describe.of(el, dist) }
    val isFav = favorites.any { it.id == el.key }
    val dark = isDark()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(d.theme.fill), Color(d.theme.stroke)))),
                contentAlignment = Alignment.Center,
            ) { Icon(iconFor(d.theme.icon), null, tint = Color.White, modifier = Modifier.size(26.dp)) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (d.subtitle.isNotEmpty()) Muted(d.subtitle)
            }
        }

        if (d.chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.chips.forEach { InfoChip(it, dark) }
        }

        if (d.rows.isNotEmpty()) Panel(padding = 0.dp) {
            d.rows.forEachIndexed { i, r ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(r.label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(0.62f)) { r.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }

        d.access?.let { a ->
            val (bg, fg) = ChipTones.colors(if (a.restricted) "warn" else "", dark)
            Surface(shape = RoundedCornerShape(16.dp), color = bg) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (a.restricted) iconFor("lock") else Icons.Filled.LockOpen, null, tint = fg, modifier = Modifier.size(20.dp))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(a.title, fontWeight = FontWeight.SemiBold, color = fg)
                            if (a.restricted) Text(t("Accesso"), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
                        }
                    }
                    a.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = fg) }
                }
            }
        }

        if (d.costEstimator) CostEstimator(el)

        d.muted?.let { Muted(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { anchor?.let { navigateTo(ctx, it.lat, it.lon) } },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Filled.Navigation, null, Modifier.size(20.dp)); Text(t("Vai a"), Modifier.padding(start = 8.dp))
            }
            FilledTonalIconButton(onClick = {
                val on = Library.toggleFavorite(el)
                UiState.say(if (on) t("Aggiunto ai preferiti") else t("Tolto dai preferiti"))
            }) {
                Icon(
                    if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (isFav) t("Salvato nei preferiti") else t("Salva nei preferiti"),
                    tint = if (isFav) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = { openUrl(ctx, osmEditUrl(el)) }) { Icon(Icons.Filled.Edit, t("Modifica su OpenStreetMap")) }
            FilledTonalIconButton(onClick = onReport) { Icon(Icons.Filled.Flag, t("Segnala un errore")) }
        }
    }
}

@Composable
fun InfoChip(c: Chip, dark: Boolean) {
    val (bg, fg) = ChipTones.colors(c.tone, dark)
    Surface(shape = CircleShape, color = bg) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            c.icon?.let { Icon(iconFor(it), null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)) }
            Text(c.text, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val COST_DURATIONS = listOf(30, 60, 120, 240, 480)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CostEstimator(el: OsmElement) {
    var minutes by remember(el.key) { mutableStateOf<Int?>(null) }
    val dark = isDark()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Calculate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(t("Costo stimato"), fontWeight = FontWeight.SemiBold)
                    Muted(t("Quanto resti? Tocca una durata."))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COST_DURATIONS.forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                }
            }
            minutes?.let { m ->
                val r = remember(el.key, m) { Describe.costResult(el.tags, m) }
                if (r.big.isNotEmpty()) {
                    val (bg, fg) = ChipTones.colors(if (r.ok) "ok" else "paid", dark)
                    Surface(shape = RoundedCornerShape(12.dp), color = bg) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(r.big, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = fg)
                            Text(r.label, style = MaterialTheme.typography.bodySmall, color = fg)
                        }
                    }
                }
                r.notes.forEach { Muted(it) }
            }
        }
    }
}
