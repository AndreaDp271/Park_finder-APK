package io.github.andreadp271.parkmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.Filter
import io.github.andreadp271.parkmap.data.Fmt
import io.github.andreadp271.parkmap.data.Kind
import io.github.andreadp271.parkmap.data.LatLon
import io.github.andreadp271.parkmap.data.Library
import io.github.andreadp271.parkmap.data.OsmRules
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.distanceM
import io.github.andreadp271.parkmap.data.t
import java.util.Calendar

private enum class RefKind { DEST, ME, CENTER }

private class Entry(val key: String, val tags: Map<String, String>, val name: String, val summary: String, val dist: Double, val lat: Double, val lon: Double)

/** Parcheggi vicini (ordinati per distanza) e preferiti. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(nav: Nav) {
    val elements by ParkingRepo.elements.collectAsState()
    val layers by AppSettings.layers.collectAsState()
    val filters by UiState.filters.collectAsState()
    val dest by UiState.destination.collectAsState()
    val fix by UiState.fix.collectAsState()
    val center by UiState.mapCenter.collectAsState()
    val favorites by Library.favorites.collectAsState()
    val tab by UiState.listTab.collectAsState()
    val now = rememberNow(60_000)

    // Punto da cui misurare: la destinazione se è nella zona che sto guardando, poi la mia posizione, altrimenti il centro
    val c = center ?: LatLon(45.4642, 9.19)
    val (ref, refKind) = when {
        dest != null && distanceM(c.lat, c.lon, dest!!.lat, dest!!.lon) < 3000 -> LatLon(dest!!.lat, dest!!.lon) to RefKind.DEST
        fix != null && distanceM(c.lat, c.lon, fix!!.lat, fix!!.lon) < 30000 -> fix!!.latLon to RefKind.ME
        else -> c to RefKind.CENTER
    }
    val walk = refKind != RefKind.CENTER

    val near = remember(elements, layers, filters, ref, now / 60_000) {
        val cal = Calendar.getInstance()
        val listFilters = mapOf(Kind.CHARGING to Filter.EV, Kind.MOTO to Filter.MOTO, Kind.BIKE to Filter.BIKE)
        elements.mapNotNull { el ->
            val kind = el.kind
            val p = el.center ?: return@mapNotNull null
            if (kind == Kind.METER) return@mapNotNull null
            // Moto, bici e colonnine solo con il loro filtro attivo
            listFilters[kind]?.let { if (it !in filters) return@mapNotNull null }
            if (!OsmRules.isShown(el.tags, layers, filters, cal)) return@mapNotNull null
            Entry(el.key, el.tags, OsmRules.title(el.tags), OsmRules.summary(el.tags), distanceM(ref, p), p.lat, p.lon)
        }.sortedBy { it.dist }
    }
    val favRows = remember(favorites, ref) {
        favorites.map { Entry(it.id, it.tags, it.name, it.summary, distanceM(ref, LatLon(it.lat, it.lon)), it.lat, it.lon) }.sortedBy { it.dist }
    }

    fun open(r: Entry) {
        UiState.focus.value = Focus(r.lat, r.lon, 17.0, r.key)
        nav.tab("mappa")
    }

    Column(Modifier.fillMaxSize()) {
        Bar(if (refKind == RefKind.DEST && tab == 0) t("Vicino a {name}", "name" to dest!!.name) else t("Parcheggi"))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            listOf(t("Vicini"), t("Preferiti")).forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { UiState.listTab.value = i }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}) { Text(label) }
            }
        }
        val rows = if (tab == 0) near.take(60) else favRows
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == 0) (if (filters.isNotEmpty()) t("Nessun parcheggio in questa zona con i filtri scelti.") else t("Nessun parcheggio in questa zona. Sposta la mappa o allarga la vista."))
                    else t("Nessun preferito.\nApri un parcheggio sulla mappa e tocca la stella per ritrovarlo qui."),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyColumn(contentPadding = screenPadding) {
            item {
                val hint = if (tab == 0) {
                    (when (refKind) {
                        RefKind.DEST -> t("Distanze a piedi da {name}", "name" to dest!!.name)
                        RefKind.ME -> t("Distanze dalla tua posizione")
                        RefKind.CENTER -> t("Distanze dal centro della mappa")
                    }) + (if (filters.isNotEmpty()) " · " + t("filtri attivi") else "") +
                        (if (near.size > 60) " · " + t("i 60 più vicini su {n}", "n" to near.size) else "")
                } else t("I preferiti restano salvati su questo dispositivo.")
                Muted(hint, Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
            item {
                Panel(padding = 0.dp) {
                    rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val theme = OsmRules.theme(r.tags)
                        Row(Modifier.fillMaxWidth().clickable { open(r) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(Color(theme.stroke)).padding(2.5.dp).clip(CircleShape).background(Color(theme.fill)))
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Muted(r.summary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.distance(r.dist), fontWeight = FontWeight.SemiBold)
                                if (walk && tab == 0) Muted(t("{n} min a piedi", "n" to Fmt.walkingMinutes(r.dist)))
                            }
                            if (tab == 1) IconButton(onClick = { Library.removeFavorite(r.key) }) { Icon(Icons.Filled.Delete, t("Rimuovi dai preferiti")) }
                        }
                    }
                }
            }
        }
    }
}
