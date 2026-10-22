package io.github.andreadp271.parkmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.Fmt
import io.github.andreadp271.parkmap.data.Http
import io.github.andreadp271.parkmap.data.LatLon
import io.github.andreadp271.parkmap.data.OsmNotes
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.ProviderState
import io.github.andreadp271.parkmap.data.t
import kotlinx.coroutines.launch

private val blue = Color(0xFF2563EB)
private val green = Color(0xFF16A34A)
private val orange = Color(0xFFEA580C)
private val purple = Color(0xFF7C3AED)
private val gray = Color(0xFF64748B)

/** Scheda "Menu": strumenti, aiuto e impostazioni. */
@Composable
fun MenuScreen(nav: Nav) {
    val ctx = LocalContext.current
    val update by UiState.update.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Bar(t("Menu"))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (update != null) {
                Surface(onClick = { nav.go("aggiornamenti") }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Aggiornamento disponibile"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(t("Versione {v} · tocca per installarla", "v" to update!!.version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Chevron()
                    }
                }
            }
            Group(t("Strumenti")) {
                Item(Icons.Filled.Refresh, green, t("Aggiorna dati"), t("Scarica di nuovo i parcheggi di questa zona"), onClick = {
                    ParkingRepo.refresh(); UiState.say(t("Aggiornamento dati...")); nav.tab("mappa")
                })
                Divider()
                Item(Icons.Filled.Share, blue, t("Condividi posizione"), t("Manda il link di questa zona della mappa"), onClick = {
                    val c = UiState.mapCenter.value ?: LatLon(45.4642, 9.19)
                    val url = Http.SITE + "?lat=%.6f&lon=%.6f&z=%d".format(java.util.Locale.US, c.lat, c.lon, UiState.mapZoom.value.toInt())
                    shareText(ctx, t("Guarda questa posizione su ParkMap"), url)
                })
                Divider()
                Item(Icons.Filled.AddLocationAlt, orange, t("Segnala parcheggio mancante"), t("Tieni premuto sulla mappa il punto dove si trova e scegli «Segnala parcheggio mancante»."))
            }
            Group(t("Aiuto e impostazioni")) {
                Item(Icons.Filled.Timeline, gray, t("Stato provider"), t("Da dove arrivano i dati dei parcheggi"), trailing = { Chevron() }, onClick = { nav.go("provider") })
                Divider()
                Item(Icons.Filled.Settings, gray, t("Impostazioni"), trailing = { Chevron() }, onClick = { nav.go("impostazioni") })
            }
        }
    }
}

// ─── Legenda ───

private class LegendItem(val label: String, val fill: Color, val stroke: Color, val round: Boolean = false)

@Composable
fun LegendSheet() {
    val groups = listOf(
        t("Aree di sosta") to listOf(
            LegendItem(t("Pagamento"), Color(0xFF60A5FA), Color(0xFF1D4ED8)), LegendItem(t("Gratuito"), Color(0xFF4ADE80), Color(0xFF15803D)),
            LegendItem(t("Disco orario"), Color(0xFFA78BFA), Color(0xFF7C3AED)), LegendItem(t("Limitato/Privato"), Color(0xFFFB923C), Color(0xFFEA580C)),
            LegendItem(t("Non specificato"), Color(0xFF94A3B8), Color(0xFF64748B)),
        ),
        t("Stalli speciali") to listOf(
            LegendItem(t("Disabili"), Color(0xFFFDE047), Color(0xFFEAB308)), LegendItem(t("Ricarica EV"), Color(0xFFF0F9FF), Color(0xFF0284C7)),
            LegendItem(t("Carico/Scarico"), Color(0xFFFED7AA), Color(0xFFD97706)), LegendItem(t("Famiglie/Rosa"), Color(0xFFFBCFE8), Color(0xFFDB2777)),
        ),
        t("Parcometri") to listOf(LegendItem(t("Parcometro (ticket)"), Color(0xFFF97316), Color(0xFF111827), true)),
        t("Altri servizi") to listOf(
            LegendItem(t("Colonnina di ricarica"), Color(0xFF059669), Color(0xFF047857), true), LegendItem(t("Parcheggio moto"), Color(0xFFFCA5A5), Color(0xFFB91C1C), true),
            LegendItem(t("Parcheggio bici"), Color(0xFFBEF264), Color(0xFF4D7C0F), true),
        ),
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t("Legenda"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        groups.forEach { (title, items) ->
            Column {
                SectionTitle(title)
                Panel(Modifier.padding(top = 4.dp)) {
                    items.forEach { it ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(16.dp).clip(if (it.round) CircleShape else RoundedCornerShape(4.dp)).background(it.stroke).padding(1.5.dp)
                                    .clip(if (it.round) CircleShape else RoundedCornerShape(3.dp)).background(it.fill),
                            )
                            Text(it.label, Modifier.padding(start = 14.dp))
                        }
                    }
                }
            }
        }
    }
}

// ─── Stato dei provider ───

@Composable
fun ProvidersScreen(nav: Nav) {
    val providers by ParkingRepo.providers.collectAsState()
    val info by ParkingRepo.staticInfo.collectAsState()
    val center by UiState.mapCenter.collectAsState()
    val (zones, bytes) = remember { ParkingRepo.cacheStats() }
    val (precomputed, osmTs) = center?.let { ParkingRepo.sourceAt(it) } ?: (false to null)
    val scope = rememberCoroutineScope()
    var pack by remember { mutableStateOf(ParkingRepo.offlinePack()) }
    var packProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Column(Modifier.fillMaxSize()) {
        Bar(t("Provider dati OSM"), onBack = nav::back)
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel(padding = 0.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Provider", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(t("Rete"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Query", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ParkingRepo.providerLabels.forEach { label ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val p = providers[label]
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            StateIcon(when (p?.reachable) { true -> ProviderState.OK; false -> ProviderState.FAILED; null -> ProviderState.IDLE }, Modifier.padding(horizontal = 18.dp))
                            StateIcon(p?.state ?: ProviderState.IDLE)
                        }
                    }
                }
            }
            item {
                Panel {
                    KeyValue(t("Cache per questa zona"), if (zones > 0) t("{n} zone", "n" to zones) + " · " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes) else t("nessuna zona"))
                    KeyValue(
                        t("Dati precalcolati"),
                        when {
                            info == null -> t("non disponibili")
                            precomputed -> t("OSM del {date}", "date" to (osmTs?.take(10) ?: ""))
                            else -> t("fuori area (Postpass)")
                        },
                    )
                }
            }
            item {
                Button(onClick = { ParkingRepo.refresh(); UiState.say(t("Aggiornamento dati...")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Text(t("Aggiorna ora"), Modifier.padding(start = 8.dp))
                }
            }
            if (info != null) item {
                Panel {
                    val pp = packProgress
                    Text(t("Dati offline"), fontWeight = FontWeight.SemiBold)
                    Text(
                        pack?.let { (n, b) -> t("Italia scaricata: {n} zone · {size}", "n" to n, "size" to android.text.format.Formatter.formatShortFileSize(LocalContext.current, b)) }
                            ?: t("Scarica tutti i parcheggi precalcolati per usare la mappa anche senza rete."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    if (pp != null) {
                        LinearProgressIndicator(progress = { pp.first.toFloat() / pp.second }, modifier = Modifier.fillMaxWidth())
                        Text("${pp.first} / ${pp.second}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                packProgress = 0 to maxOf(1, ParkingRepo.packCells())
                                scope.launch {
                                    val failed = ParkingRepo.downloadPack { d, total -> packProgress = d to total }
                                    packProgress = null; pack = ParkingRepo.offlinePack()
                                    UiState.say(if (failed > 0) t("Alcuni file non sono stati scaricati: riprova da Menu › Stato provider.") else t("Fatto"))
                                }
                            }) { Text(if (pack == null) t("Scarica tutta l'Italia") else t("Aggiorna")) }
                            if (pack != null) OutlinedButton(onClick = { ParkingRepo.clearOfflinePack(); pack = null }) { Text(t("Elimina")) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { ParkingRepo.reloadStatic(); UiState.say(t("Scarico i dati precalcolati...")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp)); Text(t("Riscarica dati precalcolati"), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun StateIcon(state: ProviderState, modifier: Modifier = Modifier) {
    when (state) {
        ProviderState.OK -> Icon(Icons.Filled.CheckCircle, null, modifier.size(20.dp), tint = Color(0xFF16A34A))
        ProviderState.FAILED -> Icon(Icons.Filled.Error, null, modifier.size(20.dp), tint = Color(0xFFDC2626))
        ProviderState.LOADING -> CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp)
        ProviderState.IDLE -> Text("—", modifier, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

// ─── Segnalazione su OpenStreetMap ───

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(nav: Nav) {
    val ctx = UiState.report.collectAsState().value
    val scope = rememberCoroutineScope()
    val types = linkedMapOf(
        "missing" to (t("Parcheggio mancante") to t("Es. parcheggio gratuito con circa 20 posti, ingresso da via Roma.")),
        "wrong" to (t("Informazioni sbagliate") to t("Cosa non è corretto? Es. ora è a pagamento dal lunedì al sabato, 8–20.")),
        "gone" to (t("Non esiste più") to t("Es. il parcheggio è stato chiuso per lavori / trasformato in area pedonale.")),
        "other" to (t("Altro") to t("Descrivi la situazione.")),
    )
    var type by remember(ctx) { mutableStateOf(ctx?.type ?: "missing") }
    var text by remember(ctx) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val c = ctx
    Column(Modifier.fillMaxSize()) {
        Bar(t("Segnala su OpenStreetMap"), onBack = nav::back)
        if (c == null) { Muted(t("Nessun elemento da segnalare."), Modifier.padding(24.dp)); return@Column }
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel {
                    Text(t("Cosa vuoi segnalare?"), fontWeight = FontWeight.SemiBold)
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { (k, v) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(v.first) }) }
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.take(1500) }, minLines = 4, placeholder = { Text(types[type]!!.second) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Muted((c.title?.let { "$it · " } ?: "") + "%.5f, %.5f".format(c.lat, c.lon), Modifier.padding(top = 8.dp))
                }
            }
            item {
                Button(
                    enabled = !sending, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (text.trim().length < 10) {
                            result = false to t("Scrivi qualche dettaglio in più (almeno 10 caratteri): aiuta chi dovrà correggere la mappa.")
                            return@Button
                        }
                        val body = listOf(
                            types[type]!!.first + " " + t("(segnalato con l'app ParkMap)"), "", text.trim(),
                            c.osmRef?.let { "\n" + t("Elemento:") + " https://www.openstreetmap.org/$it" } ?: "", "#ParkMap",
                        ).joinToString("\n")
                        sending = true; result = true to t("Invio in corso…")
                        scope.launch {
                            runCatching { OsmNotes.send(c.lat, c.lon, body) }
                                .onSuccess { id -> result = true to (t("Grazie! Segnalazione inviata") + (id?.let { ": " + t("nota") + " #$it" } ?: ".")) }
                                .onFailure { sending = false; result = false to t("Invio non riuscito. Controlla la connessione e riprova.") }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Text(t("Invia segnalazione"), Modifier.padding(start = 8.dp)) }
            }
            result?.let { (ok, msg) -> item { Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
            item { Muted(t("La segnalazione diventa una nota pubblica e anonima su OpenStreetMap: i volontari che curano la mappa la verificano e correggono i dati, per tutti.")) }
        }
    }
}
