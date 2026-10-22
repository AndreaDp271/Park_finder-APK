package io.github.andreadp271.parkmap.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import java.util.Locale
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.CarReminders
import io.github.andreadp271.parkmap.data.Geocoder
import io.github.andreadp271.parkmap.data.LatLon
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.SearchResult
import io.github.andreadp271.parkmap.data.T
import io.github.andreadp271.parkmap.data.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGES = 4

/** Setup al primo avvio: cosa fa l'app, lingua, permessi e zona da scaricare in anticipo. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }
    // Surface imposta anche il colore del testo (nero di default fuori da una Surface: illeggibile nel tema scuro)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.weight(1f)) {
            Crossfade(page, label = "onboarding") { p ->
                when (p) {
                    0 -> WelcomePage()
                    1 -> LanguagePage()
                    2 -> PermissionsPage()
                    else -> AreaPage(onDone)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES) { i ->
                    Box(
                        Modifier.size(if (i == page) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            if (page > 0) TextButton(onClick = { page-- }) { Text(t("Indietro")) }
            if (page < PAGES - 1) Button(onClick = { page++ }) { Text(t("Avanti")) }
        }
    }
    }
}

@Composable
private fun PageFrame(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

// ─── 1. Cosa fa l'app ───

@Composable
private fun WelcomePage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.LocalParking, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp)) }
        Text(t("Benvenuto in ParkMap"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(t("Trova dove parcheggiare, in ogni città."), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Feature(Icons.Filled.Map, t("Parcheggi colorati per costo"), t("Vedi a colpo d'occhio dove si paga, dove è gratis e dove serve il disco orario."))
        Feature(Icons.Filled.AttachMoney, t("Costo stimato e orari"), t("Le tariffe e le fasce orarie di OpenStreetMap, con il costo per la durata che scegli."))
        Feature(Icons.Filled.EvStation, t("Ricarica, moto, bici, disabili"), t("Colonnine, stalli speciali e parcheggi per due ruote, con i filtri."))
        Feature(Icons.Filled.DirectionsCar, t("La mia auto"), t("Salva dove hai parcheggiato e ricevi un promemoria prima che scada la sosta."))
        Feature(Icons.Filled.Public, t("Gratis, senza account"), t("Dati di OpenStreetMap, nessuna pubblicità, nessun servizio Google."))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── 2. Lingua ───

@Composable
private fun LanguagePage() {
    val ctx = LocalContext.current
    val lang by AppSettings.lang.collectAsState()
    PageFrame(t("Lingua"), t("Scegli la lingua dell'app. Puoi cambiarla in seguito dalle Impostazioni.")) {
        listOf("auto", "it", "en").forEach { code ->
            val label = when (code) { "it" -> "Italiano"; "en" -> "English"; else -> t("Automatica") }
            Surface(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                    if (code != lang) {
                        AppSettings.setLang(code)
                        T.apply(code)
                        (ctx as? Activity)?.recreate()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = if (code == lang) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    RadioButton(selected = code == lang, onClick = null)
                }
            }
        }
    }
}

// ─── 3. Permessi ───

@Composable
private fun PermissionsPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    // tick forza il ricalcolo dopo la risposta di Android
    val locOk = tick >= 0 && LocationTracker.hasPermission(ctx)
    val notifOk = tick >= 0 && CarReminders.notificationsAllowed(ctx)
    PageFrame(t("Permessi"), t("ParkMap funziona anche senza, ma con questi è più utile. Puoi cambiarli quando vuoi.")) {
        PermissionRow(
            Icons.Filled.LocationOn, t("Posizione"),
            t("Per mostrarti sulla mappa, trovare i parcheggi vicini e salvare dove lasci l'auto."), locOk,
        ) { locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        PermissionRow(
            Icons.Filled.Notifications, t("Notifiche"),
            t("Per il promemoria 10 minuti prima della scadenza della sosta."), notifOk,
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else tick++
        }
        Text(
            t("Facoltativo: il salvataggio automatico dell'auto via Bluetooth si attiva dalle Impostazioni."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, text: String, granted: Boolean, onAllow: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) Icon(Icons.Filled.Check, t("Consentito"), tint = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = onAllow) { Text(t("Consenti")) }
        }
    }
}

// ─── 4. Zona da scaricare ───

private class Picked(val name: String, val lat: Double, val lon: Double)

@Composable
private fun AreaPage(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var packProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val staticInfo by ParkingRepo.staticInfo.collectAsState()
    var country by rememberSaveable { mutableStateOf(Locale.getDefault().country.takeIf { it in Locale.getISOCountries() }) }
    var pickCountry by remember { mutableStateOf(false) }

    fun downloadPack() {
        packProgress = 0 to maxOf(1, ParkingRepo.packCells())
        scope.launch {
            val failed = ParkingRepo.downloadPack { d, total -> packProgress = d to total }
            if (failed > 0) UiState.say(t("Alcuni file non sono stati scaricati: riprova da Menu › Stato provider."))
            onFinish()
        }
    }

    fun search() {
        val q = query.trim()
        if (q.length < 2 || busy) return
        busy = true; message = null; results = emptyList()
        scope.launch {
            val found = Geocoder.searchPlaces(q, country)
            if (found.isEmpty()) message = t("Nessun risultato") else results = found
            busy = false
        }
    }

    fun useMyLocation() {
        if (busy) return
        busy = true; message = null
        LocationTracker.start(ctx)
        scope.launch {
            var waited = 0
            while (UiState.fix.value == null && waited < 10_000) { delay(300); waited += 300 }
            val f = UiState.fix.value
            if (f == null) message = t("Posizione non disponibile")
            else {
                val city = Geocoder.reverse(f.lat, f.lon)?.substringAfterLast(", ")
                picked = Picked(city ?: t("La mia posizione"), f.lat, f.lon); results = emptyList()
            }
            busy = false
        }
    }

    fun download(p: Picked) {
        progress = 0 to 1
        AppSettings.lastView = Triple(p.lat, p.lon, 15.0)
        scope.launch {
            ParkingRepo.prefetch(LatLon(p.lat, p.lon)) { d, total -> progress = d to total }
            onFinish()
        }
    }

    if (pickCountry) CountryDialog(country, { country = it; results = emptyList(); picked = null; message = null; pickCountry = false }) { pickCountry = false }

    PageFrame(
        t("La tua zona"),
        t("Scarica in anticipo i parcheggi per usare la mappa anche senza rete. Il resto si carica mentre ti sposti."),
    ) {
        val downloading = progress != null || packProgress != null
        val countryName = country?.let { countryName(it) }
        Text(t("Nazione"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !downloading) { pickCountry = true },
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(country?.let { flag(it) + "  " + countryName } ?: t("Scegli la nazione"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Filled.ExpandMore, null)
            }
        }
        val hasPack = country in ParkingRepo.PACK_COUNTRIES && staticInfo != null
        if (hasPack) {
            val mb = maxOf(1, (ParkingRepo.packCells() * 1.5 / 1024).toInt())
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Dati precalcolati: {country}", "country" to countryName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(t("Tutti i parcheggi precalcolati di OpenStreetMap, aggiornati ogni notte: circa {mb} MB.", "mb" to mb), style = MaterialTheme.typography.bodyMedium)
                    val pp = packProgress
                    if (pp != null) {
                        LinearProgressIndicator(progress = { pp.first.toFloat() / pp.second }, modifier = Modifier.fillMaxWidth())
                        Text("${pp.first} / ${pp.second}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = { downloadPack() }, enabled = !downloading, modifier = Modifier.fillMaxWidth()) { Text(t("Scarica tutto il paese")) }
                    }
                }
            }
            Text(t("Oppure scegli una città"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        } else if (country != null) {
            Text(
                t("Per {country} i parcheggi si caricano in tempo reale: scegli una città e ne scarico i dintorni (circa 15 km).", "country" to countryName),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !downloading,
            placeholder = { Text(t("Cerca località")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        if (LocationTracker.hasPermission(ctx)) {
            OutlinedButton(onClick = { useMyLocation() }, enabled = !downloading && !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)); Text(t("Usa la mia posizione"), Modifier.padding(start = 8.dp))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (picked == null && !downloading) {
            results.forEach { r ->
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { picked = Picked(r.name, r.lat, r.lon); results = emptyList() },
                    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(r.name, fontWeight = FontWeight.SemiBold)
                        if (r.sub.isNotBlank()) Text(r.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        picked?.let { p ->
            val precomputed = ParkingRepo.sourceAt(LatLon(p.lat, p.lon)).first
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (precomputed) t("Dati precalcolati di OpenStreetMap, aggiornati ogni notte.") else t("Dati di OpenStreetMap in tempo reale (Postpass)."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val pr = progress
            if (pr != null) {
                Text(t("Scarico i parcheggi di {name}…", "name" to p.name), textAlign = TextAlign.Start, fontSize = 14.sp)
                LinearProgressIndicator(progress = { if (pr.second == 0) 0f else pr.first.toFloat() / pr.second }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = { download(p) }, modifier = Modifier.fillMaxWidth()) { Text(t("Scarica la zona e inizia")) }
            }
        }
        if (!downloading) TextButton(onClick = onFinish, modifier = Modifier.align(Alignment.End)) { Text(t("Salta")) }
    }
}

private fun flag(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

private fun countryName(code: String): String = Locale("", code).getDisplayCountry(T.locale)

@Composable
private fun CountryDialog(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val all = remember(T.lang) {
        Locale.getISOCountries().map { it to countryName(it) }.filter { it.second.isNotBlank() && it.second != it.first }.sortedBy { it.second.lowercase() }
    }
    val shown = all.filter { filter.isBlank() || it.second.contains(filter.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Nazione")) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(t("Cerca nazione")) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                LazyColumn(Modifier.fillMaxWidth().height(360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(flag(code) + "  " + name, Modifier.weight(1f), fontWeight = if (code == current) FontWeight.Bold else FontWeight.Normal)
                            if (code == current) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Chiudi")) } },
    )
}
