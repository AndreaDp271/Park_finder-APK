package io.github.andreadp271.parkmap.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp271.parkmap.BuildConfig
import io.github.andreadp271.parkmap.R
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.CarStore
import io.github.andreadp271.parkmap.data.Http
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.T
import io.github.andreadp271.parkmap.data.Updater
import io.github.andreadp271.parkmap.data.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object SetColors {
    val look = Color(0xFF7C3AED)
    val general = Color(0xFF0E8A8A)
    val bt = Color(0xFF2563EB)
    val data = Color(0xFF0369A1)
    val about = Color(0xFF475569)
}

private const val REPO = "https://github.com/${Http.REPO}"

private fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by AppSettings.theme.collectAsState()
    val palette by AppSettings.palette.collectAsState()
    val lang by AppSettings.lang.collectAsState()
    val navApp by AppSettings.navApp.collectAsState()
    val showAccuracy by AppSettings.showAccuracy.collectAsState()
    val update by UiState.update.collectAsState()
    val center by UiState.mapCenter.collectAsState()
    var locateOnStart by remember { mutableStateOf(AppSettings.locateOnStart) }
    var autoUpdate by remember { mutableStateOf(AppSettings.autoUpdateCheck) }
    var checking by remember { mutableStateOf(false) }
    var checkedNoUpdate by remember { mutableStateOf(false) }
    var cacheDialog by remember { mutableStateOf(false) }
    var cacheTick by remember { mutableIntStateOf(0) }
    val cacheInfo = remember(cacheTick) { ParkingRepo.cacheStats() }
    val (precomputed, osmTs) = center?.let { ParkingRepo.sourceAt(it) } ?: (false to null)

    Column(Modifier.fillMaxSize()) {
        Bar(t("Impostazioni"), onBack = nav::back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppCard()

            if (update != null) {
                androidx.compose.material3.Surface(onClick = { nav.go("aggiornamenti") }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Aggiornamento disponibile"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(t("Versione {v} · tocca per installarla", "v" to update!!.version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Chevron()
                    }
                }
            }

            Group(t("Aspetto")) {
                Item(Icons.Filled.DarkMode, SetColors.look, t("Tema"), t("Chiaro, scuro o come il sistema"))
                Choice(AppSettings.Theme.entries, theme, { t(it.label) }) { AppSettings.setTheme(it) }
                Divider()
                Item(
                    Icons.Filled.Palette, SetColors.look, t("Colori"),
                    if (palette == AppSettings.Palette.DYNAMIC && Build.VERSION.SDK_INT >= 31) t("Presi dallo sfondo del telefono (Material You)")
                    else if (palette == AppSettings.Palette.DYNAMIC) t("Disponibile da Android 12: si usa il blu ParkMap")
                    else t("Blu ParkMap, come la versione web"),
                )
                Choice(AppSettings.Palette.entries, palette, { t(it.label) }) { AppSettings.setPalette(it) }
                Divider()
                Item(Icons.Filled.Language, SetColors.look, t("Lingua"))
                Choice(listOf("auto", "it", "en"), lang, { when (it) { "it" -> "Italiano"; "en" -> "English"; else -> t("Automatica") } }) {
                    AppSettings.setLang(it)
                    T.apply(it)
                    (ctx as? Activity)?.recreate()
                }
            }

            Group(t("Navigazione")) {
                Item(Icons.Filled.Navigation, SetColors.general, t("App per \"Vai a\""))
                Choice(AppSettings.NavApp.entries, navApp, { t(it.label) }) { AppSettings.setNavApp(it) }
            }

            Group(t("Posizione")) {
                Item(Icons.Filled.MyLocation, SetColors.general, t("Centra sulla mia posizione all'avvio"), trailing = {
                    Switch(locateOnStart, { AppSettings.locateOnStart = it; locateOnStart = it })
                }, onClick = { AppSettings.locateOnStart = !locateOnStart; locateOnStart = !locateOnStart })
                Divider()
                Item(Icons.Filled.RadioButtonUnchecked, SetColors.general, t("Mostra la precisione del GPS"), trailing = {
                    Switch(showAccuracy, { AppSettings.setShowAccuracy(it) })
                }, onClick = { AppSettings.setShowAccuracy(!showAccuracy) })
            }

            BluetoothGroup()

            Group(t("Dati")) {
                Item(
                    Icons.Filled.Dns, SetColors.data, t("Fonte in questa zona"),
                    if (precomputed) t("Precalcolati") + (osmTs?.let { " · " + t("OSM del {date}", "date" to it.take(10)) } ?: "") else t("Postpass (in tempo reale)"),
                )
                Divider()
                Item(
                    Icons.Filled.DeleteSweep, SetColors.data, t("Svuota cache"),
                    if (cacheInfo.first > 0) t("{n} zone", "n" to cacheInfo.first) + " · " + android.text.format.Formatter.formatShortFileSize(ctx, cacheInfo.second) else t("nessuna zona"),
                    onClick = { cacheDialog = true },
                )
            }

            Group(t("Aggiornamenti")) {
                val fromStore = remember { Updater.fromStore(ctx) }
                Item(
                    Icons.Filled.PhoneAndroid, SetColors.about, if (fromStore) t("Versione installata") else t("Versione installata"),
                    when {
                        fromStore -> BuildConfig.VERSION_NAME + " · " + t("aggiornamenti dal tuo negozio di app")
                        update != null -> t("Disponibile la versione {v}", "v" to update!!.version)
                        checkedNoUpdate -> t("Hai l'ultima versione ({v})", "v" to BuildConfig.VERSION_NAME)
                        else -> BuildConfig.VERSION_NAME
                    },
                )
                if (!fromStore) {
                    Divider()
                    Item(Icons.Filled.SystemUpdate, SetColors.about, t("Controlla su GitHub"), trailing = if (checking) { { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } } else null, onClick = {
                        if (update != null) nav.go("aggiornamenti")
                        else scope.launch {
                            checking = true
                            runCatching { Updater.latest() }.getOrNull()?.let { r -> if (Updater.isNewer(r.version)) UiState.update.value = r else checkedNoUpdate = true }
                            AppSettings.lastUpdateCheck = System.currentTimeMillis()
                            checking = false
                        }
                    })
                    Divider()
                    Item(Icons.Filled.SystemUpdate, SetColors.about, t("Controlla aggiornamenti all'avvio"), trailing = {
                        Switch(autoUpdate, { AppSettings.autoUpdateCheck = it; autoUpdate = it })
                    }, onClick = { AppSettings.autoUpdateCheck = !autoUpdate; autoUpdate = !autoUpdate })
                }
            }

            Group(t("Informazioni")) {
                Item(Icons.Filled.Code, SetColors.about, t("Codice sorgente"), REPO.removePrefix("https://"), onClick = { openUrl(ctx, REPO) })
                Divider()
                Item(Icons.Filled.BugReport, SetColors.about, t("Segnala un problema"), onClick = { openUrl(ctx, "https://github.com/${Http.APK_REPO}/issues/new") })
                Divider()
                Item(Icons.Filled.Map, SetColors.about, t("Dati © OpenStreetMap contributors"), onClick = { openUrl(ctx, "https://www.openstreetmap.org/copyright") })
            }
            Muted(
                t("Parcheggi: dati © OpenStreetMap contributors (ODbL), precalcolati ogni notte; in tempo reale da Postpass e Overpass. Ricerca luoghi: Nominatim.") + "\n" +
                    t("I dati su tariffe e orari li inseriscono i volontari di OpenStreetMap: controlla sempre i cartelli sul posto."),
                Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }

    if (cacheDialog) AlertDialog(
        onDismissRequest = { cacheDialog = false },
        title = { Text(t("Svuota cache")) },
        text = { Text(t("Parcheggi e tessere della mappa verranno scaricati di nuovo quando servono. Impostazioni, preferiti e auto restano.")) },
        confirmButton = {
            TextButton(onClick = {
                cacheDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ParkingRepo.clearCache()
                        ctx.cacheDir.listFiles()?.filter { it.name != "tiles" && it.name != "http" }?.forEach { it.deleteRecursively() }
                        runCatching { Http.client.cache?.evictAll() }
                    }
                    cacheTick++
                }
            }) { Text(t("Svuota")) }
        },
        dismissButton = { TextButton(onClick = { cacheDialog = false }) { Text(t("Annulla")) } },
    )
}

/** Intestazione con icona, nome e versione dell'app. */
@Composable
private fun AppCard() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF1D4ED8), Color(0xFF3B82F6))))) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.mipmap.ic_launcher_round), null, Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
            Column(Modifier.padding(start = 16.dp)) {
                Text("ParkMap", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(t("Versione {v}", "v" to BuildConfig.VERSION_NAME), color = Color.White.copy(alpha = 0.9f))
                Text(t("Parcheggi da OpenStreetMap · senza pubblicità"), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/** Auto via Bluetooth: scegli il dispositivo dell'auto e concedi i permessi per salvare la posizione con l'app chiusa. */
@Composable
private fun BluetoothGroup() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<List<Triple<String, String, Boolean>>?>(null) }
    var deviceName by remember { mutableStateOf(CarStore.btName ?: CarStore.btAddress) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val adapter = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    val hasDevice = CarStore.btAddress != null
    @Suppress("UNUSED_EXPRESSION") tick
    val btOk = Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
    val locOk = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    val bgOk = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun loadDevices() {
        val list = try {
            adapter?.bondedDevices.orEmpty().map { d ->
                val cls = d.bluetoothClass?.deviceClass ?: 0
                Triple(d.address, d.name ?: d.address, cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
            }.sortedWith(compareBy({ !it.third }, { it.second }))
        } catch (_: SecurityException) { emptyList() }
        picker = list
    }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        tick++
        if (r.values.any { it } && Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> tick++; if (ok) loadDevices() }

    Group(t("Auto via Bluetooth")) {
        if (adapter == null) {
            Item(Icons.Filled.Bluetooth, SetColors.bt, t("Bluetooth non disponibile su questo telefono."))
            return@Group
        }
        Item(
            Icons.Filled.Bluetooth, SetColors.bt, t("Salva l'auto quando mi scollego"),
            if (hasDevice) deviceName else null,
            trailing = {
                Switch(hasDevice, { on ->
                    if (!on) { CarStore.btAddress = null; CarStore.btName = null; deviceName = null; tick++ }
                    else if (!btOk) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else loadDevices()
                })
            },
        )
        if (hasDevice) {
            Divider()
            Item(Icons.Filled.Bluetooth, SetColors.bt, t("Cambia"), onClick = { if (btOk) loadDevices() else btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        }
        if (hasDevice && (!btOk || !locOk || !bgOk)) {
            Divider()
            Muted(
                if (!btOk) t("Manca il permesso «Dispositivi nelle vicinanze»: senza, ParkMap non sa quando ti scolleghi.")
                else t("Per salvare la posizione con l'app chiusa serve la posizione «Consenti sempre»."),
                Modifier.padding(16.dp),
            )
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!btOk) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else if (!locOk) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp)); Text(t("Concedi la posizione"), Modifier.padding(start = 6.dp)) }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(t("Impostazioni di Android")) }
            }
        } else if (hasDevice) {
            Divider()
            Muted(
                t("Attivo: quando ti scolleghi da {name} salvo dove hai lasciato l'auto, quando ti ricolleghi chiudo la sosta.", "name" to (deviceName ?: "")),
                Modifier.padding(16.dp),
            )
        }
        Muted(
            t("Quando il telefono si scollega dal Bluetooth dell'auto, ParkMap salva da solo dove l'hai lasciata e ti avvisa con una notifica. Quando ti ricolleghi la sosta finisce da sola: l'auto va nello storico e i promemoria vengono annullati."),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }

    picker?.let { devices ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(t("Scegli il Bluetooth della tua auto:")) },
            text = {
                if (devices.isEmpty()) Text(t("Nessun dispositivo associato: collega prima il telefono al Bluetooth dell'auto."))
                else LazyColumn {
                    items(devices.size) { i ->
                        val (address, name, isCar) = devices[i]
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                CarStore.btAddress = address; CarStore.btName = name; deviceName = name; picker = null; tick++
                                // dopo la scelta: posizione, poi «Consenti sempre»
                                if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                else if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (isCar) iconFor("car") else Icons.Filled.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                            Text(name, Modifier.padding(start = 14.dp).weight(1f))
                            if (isCar) Text(t("auto"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text(t("Chiudi")) } },
        )
    }
}

// ─── Aggiornamenti ───

@Composable
fun UpdateScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var rel by remember { mutableStateOf(UiState.update.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }

    fun check() = scope.launch {
        checking = true; error = null
        runCatching { Updater.latest() }
            .onSuccess { r -> if (Updater.isNewer(r.version)) { rel = r; UiState.update.value = r } else { upToDate = true; rel = null } }
            .onFailure { error = it.message ?: t("Errore connessione. Riprova più tardi.") }
        checking = false
    }
    LaunchedEffect(Unit) { if (rel == null) check() }
    DisposableEffect(Unit) {
        Updater.onFinished = { e -> progress = -1f; error = e }
        onDispose { Updater.onFinished = null }
    }

    Column(Modifier.fillMaxSize()) {
        Bar(t("Aggiornamenti"), t("Versione {v}", "v" to BuildConfig.VERSION_NAME), onBack = nav::back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel {
                when {
                    checking -> { Text(t("Controllo in corso…")); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                    rel != null -> {
                        Text(t("Disponibile la versione {v}", "v" to rel!!.version), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (rel!!.notes.isNotBlank()) Text(rel!!.notes, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        if (progress >= 0f) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 12.dp))
                        Button(
                            enabled = progress < 0f,
                            onClick = {
                                if (!Updater.canInstall(ctx)) {
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                                    return@Button
                                }
                                scope.launch {
                                    error = null; progress = 0f
                                    runCatching {
                                        val f = Updater.download(ctx, rel!!) { progress = it }
                                        Updater.install(ctx, f)
                                    }.onFailure { error = it.message; progress = -1f }
                                }
                            },
                            modifier = Modifier.padding(top = 12.dp).height(48.dp),
                        ) { Text(if (Updater.canInstall(ctx)) t("Scarica e installa") else t("Consenti l'installazione")) }
                        if (!Updater.canInstall(ctx)) Muted(t("Android chiede di autorizzare ParkMap a installare i propri aggiornamenti: attiva l'opzione e torna qui."), Modifier.padding(top = 6.dp))
                    }
                    upToDate -> Text(t("Hai già l'ultima versione."), fontWeight = FontWeight.SemiBold)
                }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
            OutlinedButton(onClick = { check() }, enabled = !checking) { Text(t("Controlla di nuovo")) }
            Muted(t("Gli aggiornamenti vengono scaricati dalla pagina ufficiale delle release su GitHub e installati solo se firmati come questa app."))
        }
    }
}
