package io.github.andreadp271.parkmap.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.Car
import io.github.andreadp271.parkmap.data.CarReminders
import io.github.andreadp271.parkmap.data.CarStore
import io.github.andreadp271.parkmap.data.Fmt
import io.github.andreadp271.parkmap.data.Geocoder
import io.github.andreadp271.parkmap.data.Http
import io.github.andreadp271.parkmap.data.Stop
import io.github.andreadp271.parkmap.data.distanceM
import io.github.andreadp271.parkmap.data.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** Salva la posizione dell'auto dal GPS: usa l'ultimo fix recente o ne aspetta uno nuovo (fino a 12 s). */
suspend fun saveCarFromGps(ctx: android.content.Context): Boolean {
    var f = UiState.fix.value
    if (f == null || System.currentTimeMillis() - f.ts > 120_000 || f.acc > 150) {
        if (!LocationTracker.hasPermission(ctx)) { UiState.say(t("Permesso posizione negato")); return false }
        UiState.say(t("Cerco la tua posizione..."))
        LocationTracker.start(ctx)
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 12_000) {
            delay(300)
            val n = UiState.fix.value
            if (n != null && n.ts >= started - 30_000 && n.acc < 150) { f = n; break }
        }
    }
    val fx = f
    if (fx == null) { UiState.say(t("Posizione non disponibile: usa il centro della mappa")); return false }
    CarStore.saveAt(fx.lat, fx.lon, "gps")
    UiState.say(t("Posizione dell'auto salvata"))
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val car by CarStore.car.collectAsState()
    val history by CarStore.history.collectAsState()
    val fix by UiState.fix.collectAsState()
    val photoVersion by CarStore.photoVersion.collectAsState()
    val now = rememberNow(30_000)
    var showAll by remember { mutableStateOf(false) }
    var photoOpen by remember { mutableStateOf(false) }
    var stopOpen by remember { mutableStateOf<Stop?>(null) }
    var notifyWarning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Indirizzo (Nominatim, una richiesta per salvataggio)
    LaunchedEffect(car?.ts) {
        val c = car ?: return@LaunchedEffect
        if (c.address.isEmpty()) Geocoder.reverse(c.lat, c.lon)?.let { CarStore.setAddress(c, it) }
    }
    // Richiesta da widget/scorciatoia: "Ho parcheggiato" salva subito
    val pendingSave by UiState.pendingSaveCar.collectAsState()
    LaunchedEffect(pendingSave) {
        if (pendingSave) { UiState.pendingSaveCar.value = false; saveCarFromGps(ctx) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifyWarning = !CarReminders.notificationsAllowed(ctx) }
    fun setExpiry(at: Long?) {
        CarStore.setExpiry(at)
        if (at != null) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            notifyWarning = !CarReminders.notificationsAllowed(ctx)
        } else notifyWarning = false
    }

    val photoUri = remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri.value
        if (ok && uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { CarStore.setPhoto(uri) }
            if (!saved) UiState.say(t("Foto non salvata"))
        }
    }
    fun launchCamera() {
        val f = File(ctx.cacheDir, "update").apply { mkdirs() }.let { File(it, "photo.jpg") }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        photoUri.value = uri
        takePhoto.launch(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Bar(t("La mia auto"))
        LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (car == null) {
                item {
                    Panel {
                        Text(t("Salva dove hai lasciato l'auto: la ritrovi sulla mappa, con la strada a piedi per tornarci e un promemoria per il ticket o il disco orario."))
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !saving, onClick = { scope.launch { saving = true; saveCarFromGps(ctx); saving = false } }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp)); Text(t("Salva la mia posizione"), Modifier.padding(start = 8.dp), maxLines = 1)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val c = UiState.mapCenter.value
                                if (c != null) { CarStore.saveAt(c.lat, c.lon, "map"); UiState.say(t("Posizione dell'auto salvata")) }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Icon(Icons.Filled.Place, null, Modifier.size(18.dp)); Text(t("Usa il centro della mappa"), Modifier.padding(start = 8.dp)) }
                    }
                }
                item { Muted(t("Puoi anche tenere premuto un punto della mappa e scegliere \"Ho parcheggiato qui\".") + " " + t("Oppure attiva nelle Impostazioni il salvataggio automatico via Bluetooth."), Modifier.padding(horizontal = 8.dp)) }
            } else {
                val c = car!!
                val dist = fix?.let { distanceM(it.lat, it.lon, c.lat, c.lon) }
                val left = c.expiry?.minus(now)
                item {
                    Panel(padding = 0.dp) {
                        InfoLine(iconFor("car"), t("Parcheggiata {when}", "when" to Fmt.parkedAt(c.ts)))
                        if (c.address.isNotEmpty()) InfoLine(Icons.Filled.Place, c.address)
                        if (c.source == "bluetooth") InfoLine(Icons.Filled.Bluetooth, t("Salvata da sola scollegandoti da {name}", "name" to (c.device ?: "Bluetooth")))
                        if (dist != null) InfoLine(Icons.AutoMirrored.Filled.DirectionsWalk, t("A {d} da te · circa {n} min a piedi", "d" to Fmt.distance(dist), "n" to Fmt.walkingMinutes(dist)))
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { navigateTo(ctx, c.lat, c.lon, walking = true) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp)); Text(t("Portami all'auto"), Modifier.padding(start = 6.dp), maxLines = 1)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { UiState.focus.value = Focus(c.lat, c.lon, 17.0); nav.tab("mappa") }, modifier = Modifier.weight(1f)) {
                                Text(t("Mostra sulla mappa"), maxLines = 1)
                            }
                            OutlinedButton(onClick = {
                                val text = t("La mia auto è qui") + (if (c.address.isNotEmpty()) ": ${c.address}" else "") + (if (c.note.isNotEmpty()) " (${c.note})" else "")
                                val url = Http.SITE + "?car=" + "%.6f,%.6f".format(java.util.Locale.US, c.lat, c.lon) + (if (c.note.isNotEmpty()) "&note=" + Uri.encode(c.note.take(200)) else "")
                                shareText(ctx, text, url)
                            }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(t("Condividi"), Modifier.padding(start = 6.dp)) }
                        }
                    }
                }
                item {
                    Group(t("Note e foto")) {
                        var note by remember(c.ts) { mutableStateOf(c.note) }
                        OutlinedTextField(
                            value = note, onValueChange = { note = it.take(300); CarStore.setNote(note) },
                            placeholder = { Text(t("Es. piano -2, colonna B14")) }, minLines = 2, modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                        if (c.photo) {
                            val bmp = remember(photoVersion, c.ts) { runCatching { BitmapFactory.decodeFile(CarStore.photoFile.path) }.getOrNull() }
                            if (bmp != null) Image(
                                bmp.asImageBitmap(), t("Foto dell'auto"),
                                Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).clickable { photoOpen = true },
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { launchCamera() }) {
                                Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp)); Text(if (c.photo) t("Cambia foto") else t("Scatta una foto"), Modifier.padding(start = 6.dp))
                            }
                            if (c.photo) OutlinedButton(onClick = { CarStore.removePhoto() }) {
                                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Text(t("Elimina foto"), Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                item {
                    Group(t("Promemoria sosta")) {
                        Text(
                            when {
                                c.expiry == null -> t("Nessun promemoria impostato.")
                                left!! > 0 -> t("Scade alle {time} · tra {d}", "time" to Fmt.clock(c.expiry), "d" to Fmt.duration(left))
                                else -> t("Scaduta alle {time} ({d} fa)", "time" to Fmt.clock(c.expiry), "d" to Fmt.duration(-left))
                            },
                            Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                        FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30 to "30 min", 60 to t("1 ora"), 120 to t("2 ore"), 180 to t("3 ore")).forEach { (m, label) ->
                                FilterChip(selected = false, onClick = { setExpiry(System.currentTimeMillis() + m * 60_000L) }, label = { Text(label) })
                            }
                            FilterChip(selected = false, onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = if (c.expiry != null && left!! > 0) c.expiry else System.currentTimeMillis() + 3_600_000 }
                                TimePickerDialog(ctx, { _, h, mi ->
                                    val d = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                                    if (d.timeInMillis <= System.currentTimeMillis()) d.add(Calendar.DAY_OF_YEAR, 1) // orario già passato: domani
                                    setExpiry(d.timeInMillis)
                                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                            }, label = { Text(t("Oppure fino alle") + "…") })
                        }
                        if (c.expiry != null) TextButton(onClick = { setExpiry(null) }, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Icon(Icons.Filled.NotificationsOff, null, Modifier.size(18.dp)); Text(t("Togli promemoria"), Modifier.padding(start = 6.dp))
                        }
                        Muted(
                            (if (notifyWarning) t("Le notifiche sono disattivate per ParkMap: abilitale nelle impostazioni di Android per ricevere il promemoria.") + " " else "") +
                                t("Ti avviso 10 minuti prima e alla scadenza."),
                            Modifier.padding(14.dp),
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = { CarStore.leave(); notifyWarning = false }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(18.dp)); Text(t("Ho lasciato il parcheggio"), Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (history.isNotEmpty()) item {
                val items = if (showAll) history else history.take(5)
                Group(t("Ultime soste")) {
                    items.forEachIndexed { i, h ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(Modifier.fillMaxWidth().clickable { stopOpen = h }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(h.address.ifEmpty { "%.5f, %.5f".format(h.lat, h.lon) }, fontWeight = FontWeight.SemiBold)
                            Muted(Fmt.parkedAt(h.ts) + " · " + Fmt.duration(h.end - h.ts) + (if (h.note.isNotEmpty()) " · ${h.note}" else ""))
                        }
                    }
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        if (history.size > items.size) TextButton(onClick = { showAll = true }) { Text(t("Mostra tutte ({n})", "n" to history.size)) }
                        TextButton(onClick = { CarStore.clearHistory() }) { Text(t("Cancella storico"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (photoOpen) AlertDialog(
        onDismissRequest = { photoOpen = false }, confirmButton = { TextButton(onClick = { photoOpen = false }) { Text(t("Chiudi")) } },
        text = {
            val bmp = remember(photoVersion) { runCatching { BitmapFactory.decodeFile(CarStore.photoFile.path) }.getOrNull() }
            if (bmp != null) Image(bmp.asImageBitmap(), t("Foto dell'auto"), Modifier.fillMaxWidth())
        },
    )

    stopOpen?.let { h ->
        AlertDialog(
            onDismissRequest = { stopOpen = null },
            title = { Text(t("Sosta di {when}", "when" to Fmt.parkedAt(h.ts))) },
            text = { Text(listOf(h.address, t("durata {d}", "d" to Fmt.duration(h.end - h.ts)), h.note).filter { it.isNotEmpty() }.joinToString(" · ")) },
            confirmButton = { TextButton(onClick = { stopOpen = null; navigateTo(ctx, h.lat, h.lon) }) { Text(t("Naviga fin qui")) } },
            dismissButton = { TextButton(onClick = { stopOpen = null; UiState.focus.value = Focus(h.lat, h.lon, 17.0); nav.tab("mappa") }) { Text(t("Mostra sulla mappa")) } },
        )
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, Modifier.padding(start = 12.dp))
    }
}
