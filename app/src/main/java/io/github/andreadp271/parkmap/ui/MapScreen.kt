package io.github.andreadp271.parkmap.ui

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.BBox
import io.github.andreadp271.parkmap.data.CarStore
import io.github.andreadp271.parkmap.data.Filter
import io.github.andreadp271.parkmap.data.Fmt
import io.github.andreadp271.parkmap.data.Geocoder
import io.github.andreadp271.parkmap.data.LatLon
import io.github.andreadp271.parkmap.data.Layer
import io.github.andreadp271.parkmap.data.Library
import io.github.andreadp271.parkmap.data.OsmElement
import io.github.andreadp271.parkmap.data.OsmRules
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.RecentSearch
import io.github.andreadp271.parkmap.data.SearchOutcome
import io.github.andreadp271.parkmap.data.SearchResult
import io.github.andreadp271.parkmap.data.distanceM
import io.github.andreadp271.parkmap.data.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Calendar
import kotlin.math.max

/** La mappa è creata una volta sola e riusata tra le schede: tornare alla mappa è immediato. */
object MapHolder {
    var view: MapView? = null
    var owner: Context? = null
    lateinit var parking: ParkingOverlay
    lateinit var markers: MarkersOverlay
    var labels: TilesOverlay? = null
    var appliedStyle: String? = null
    var started = false
    /** Il primo caricamento dei dati aspetta (poco) la posizione, per non scaricare la vista iniziale che verrebbe subito abbandonata. */
    var loadEnabled = false
    var centerOnNextFix = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotify = 0L
    val longPress = androidx.compose.runtime.mutableStateOf<LatLon?>(null)

    fun get(ctx: Context): MapView {
        view?.takeIf { owner === ctx }?.let { return it }
        val map = MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            val v = AppSettings.lastView
            controller.setZoom(v?.third ?: 15.0)
            controller.setCenter(GeoPoint(v?.first ?: 45.4642, v?.second ?: 9.19))
        }
        parking = ParkingOverlay(ctx)
        markers = MarkersOverlay(ctx)
        // La rilevazione della pressione prolungata sta in fondo: i tocchi li gestiscono prima i livelli sopra
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean { p?.let { longPress.value = LatLon(it.latitude, it.longitude) }; return p != null }
        }))
        map.overlays.add(parking)
        map.overlays.add(markers)
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { schedule(map); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { schedule(map); return false }
        })
        view = map; owner = ctx; appliedStyle = null; labels = null
        return map
    }

    /** Durante il movimento al massimo ogni 300 ms; a movimento fermo, una volta sola. */
    private fun schedule(map: MapView) {
        val now = SystemClock.uptimeMillis()
        if (now - lastNotify >= 300) { lastNotify = now; notifyView(map, false) }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ notifyView(map, true) }, 150)
    }

    fun notifyView(map: MapView, settled: Boolean) {
        if (map.width == 0 || map.height == 0) return
        if (!loadEnabled) return
        val bb = map.boundingBox
        val c = map.mapCenter
        val z = map.zoomLevelDouble
        UiState.mapCenter.value = LatLon(c.latitude, c.longitude)
        UiState.mapZoom.value = z
        if (settled) AppSettings.lastView = Triple(c.latitude, c.longitude, z)
        ParkingRepo.onViewChanged(BBox(bb.latSouth, bb.lonWest, bb.latNorth, bb.lonEast), LatLon(c.latitude, c.longitude), z)
    }

    fun release() {
        view?.onDetach(); view = null; owner = null; started = false; loadEnabled = false; labels = null; appliedStyle = null
    }
}

@Composable
fun rememberNow(intervalMs: Long): Long = produceState(System.currentTimeMillis()) {
    while (true) { delay(intervalMs); value = System.currentTimeMillis() }
}.value

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val map = remember { MapHolder.get(ctx) }
    val elements by ParkingRepo.elements.collectAsState()
    val layers by AppSettings.layers.collectAsState()
    val filters by UiState.filters.collectAsState()
    val style by AppSettings.mapStyle.collectAsState()
    val showAccuracy by AppSettings.showAccuracy.collectAsState()
    val car by CarStore.car.collectAsState()
    val fix by UiState.fix.collectAsState()
    val dest by UiState.destination.collectAsState()
    val selected by UiState.selected.collectAsState()
    val status by UiState.status.collectAsState()
    val loading by ParkingRepo.loading.collectAsState()
    val centerEmpty by ParkingRepo.centerEmpty.collectAsState()
    val recents by Library.recents.collectAsState()
    val sharedCar by UiState.sharedCar.collectAsState()
    val longPress = MapHolder.longPress.value
    val dark = isDark()
    val now = rememberNow(30_000)

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var recentShown by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var sharedOpen by remember { mutableStateOf(false) }

    // ─── Stile, livelli, marker ───
    LaunchedEffect(style) {
        if (MapHolder.appliedStyle != style) {
            MapHolder.labels = MapStyles.apply(ctx, map, MapStyles.byId(style), MapHolder.labels)
            MapHolder.appliedStyle = style
        }
    }

    LaunchedEffect(elements, layers, filters, now / 60_000) {
        val shown = withContext(Dispatchers.Default) {
            val cal = Calendar.getInstance()
            val list = elements.filter { OsmRules.isShown(it.tags, layers, filters, cal) }
            MapHolder.parking.prepare(list)
        }
        MapHolder.parking.setPrepared(shown)
        map.invalidate()
    }

    LaunchedEffect(selected) {
        MapHolder.parking.selectedKey = selected?.key
        map.invalidate()
    }

    LaunchedEffect(fix, showAccuracy, car, dest, sharedCar) {
        val m = MapHolder.markers
        m.user = fix?.let { GeoPoint(it.lat, it.lon) to it.acc }
        m.showAccuracy = showAccuracy
        m.car = car?.let { GeoPoint(it.lat, it.lon) }
        m.dest = dest?.let { GeoPoint(it.lat, it.lon) }
        m.sharedCar = sharedCar?.let { GeoPoint(it.first, it.second) }
        map.invalidate()
    }

    DisposableEffect(Unit) {
        MapHolder.parking.onTap = { el -> UiState.selected.value = el }
        MapHolder.markers.onCarTap = { nav.tab("auto") }
        MapHolder.markers.onSharedCarTap = { sharedOpen = true }
        MapHolder.markers.onDestTap = { nav.tab("vicini") }
        onDispose { MapHolder.parking.onTap = null; MapHolder.markers.onCarTap = null; MapHolder.markers.onSharedCarTap = null; MapHolder.markers.onDestTap = null }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> {} }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause() }
    }

    // ─── Posizione ───
    fun centerOn(lat: Double, lon: Double, zoom: Double? = null) {
        val z = zoom ?: map.zoomLevelDouble
        map.controller.animateTo(GeoPoint(lat, lon), z, 500L)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) { LocationTracker.start(ctx) } else { MapHolder.centerOnNextFix = false; UiState.say(t("Permesso posizione negato")) }
    }
    val locationPerms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun locate() {
        if (!LocationTracker.hasPermission(ctx)) { MapHolder.centerOnNextFix = true; permLauncher.launch(locationPerms); return }
        LocationTracker.start(ctx)
        val f = UiState.fix.value
        if (f != null && System.currentTimeMillis() - f.ts < 30_000) centerOn(f.lat, f.lon, max(map.zoomLevelDouble, 17.0))
        else { MapHolder.centerOnNextFix = true; UiState.say(t("Cerco la tua posizione...")) }
    }

    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        if (MapHolder.centerOnNextFix) {
            MapHolder.centerOnNextFix = false
            centerOn(f.lat, f.lon, max(map.zoomLevelDouble, 17.0))
            UiState.say(t("Posizione trovata"))
        }
    }

    // Avvio: centra sulla posizione (se consentito) e solo poi scarica, per non sprecare una richiesta sulla vista iniziale
    LaunchedEffect(Unit) {
        if (MapHolder.started) return@LaunchedEffect
        MapHolder.started = true
        val hasLink = UiState.focus.value != null || UiState.sharedCar.value != null
        if (AppSettings.locateOnStart && !hasLink) {
            val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            if (LocationTracker.hasPermission(ctx)) { LocationTracker.start(ctx); MapHolder.centerOnNextFix = true }
            else if (!prefs.getBoolean("asked_location", false)) {
                prefs.edit().putBoolean("asked_location", true).apply()
                MapHolder.centerOnNextFix = true
                permLauncher.launch(locationPerms)
            }
            if (MapHolder.centerOnNextFix) withTimeoutOrNull(1500) { UiState.fix.filterNotNull().first() }
        }
        MapHolder.loadEnabled = true
        MapHolder.notifyView(map, true)
    }
    DisposableEffect(map) {
        val l = MapView.OnFirstLayoutListener { _, _, _, _, _ -> MapHolder.notifyView(map, true) }
        map.addOnFirstLayoutListener(l)
        onDispose {}
    }
    LaunchedEffect(Unit) { ParkingRepo.errors.collect { UiState.say(t("Errore connessione. Riprova più tardi.")) } }

    // Spostamenti richiesti da altre schermate (elenco, auto, link)
    val focus by UiState.focus.collectAsState()
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        UiState.focus.value = null
        centerOn(f.lat, f.lon, f.zoom ?: max(map.zoomLevelDouble, 17.0))
        f.openKey?.let { key ->
            // L'elemento potrebbe non essere ancora caricato (preferito lontano): lo si attende un po'
            repeat(30) {
                delay(300)
                val el = ParkingRepo.elements.value.firstOrNull { it.key == key }
                if (el != null) { UiState.selected.value = el; return@LaunchedEffect }
            }
        }
    }

    // Messaggio breve: sparisce da solo
    LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        delay(if (s.length > 40) 4000 else 2200)
        if (UiState.status.value == s) UiState.status.value = null
    }

    // ─── Ricerca ───
    fun center(): LatLon = UiState.mapCenter.value ?: LatLon(map.mapCenter.latitude, map.mapCenter.longitude)

    fun goTo(r: SearchResult) {
        results = emptyList(); recentShown = false
        Library.rememberSearch(RecentSearch(r.name, r.sub, r.lat, r.lon, r.place, r.zoom))
        focusManager.clearFocus()
        if (r.place) {
            UiState.destination.value = null
            centerOn(r.lat, r.lon, r.zoom ?: 16.0)
            UiState.say(t("Posizione aggiornata"))
        } else {
            UiState.destination.value = Destination(r.lat, r.lon, r.name)
            centerOn(r.lat, r.lon, 17.0)
            scope.launch {
                // Appena i parcheggi della zona sono arrivati si apre l'elenco ordinato per distanza a piedi
                delay(800)
                var waited = 0
                while (!ParkingRepo.isReady() && waited < 10_000) { delay(300); waited += 300 }
                if (UiState.destination.value != null) { UiState.listTab.value = 0; nav.tab("vicini") }
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        focusManager.clearFocus(); results = emptyList(); recentShown = false
        UiState.say(t("Cerco località..."))
        scope.launch {
            when (val o = Geocoder.search(q, center())) {
                is SearchOutcome.Results -> { results = o.items; UiState.say(o.message) }
                is SearchOutcome.Jump -> { centerOn(o.lat, o.lon, o.zoom); UiState.say(o.message) }
                is SearchOutcome.NotFound -> UiState.say(o.message)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ (map.parent as? ViewGroup)?.removeView(map); map }, Modifier.fillMaxSize())

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding())

        // Barra in alto: ricerca in una pillola, risultati e filtri sotto
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchPill(
                query = query,
                onQuery = { query = it; if (it.isNotEmpty()) { if (recentShown) { results = emptyList(); recentShown = false } } else if (focused && recents.isNotEmpty()) recentShown = true },
                onFocus = { focused = it; if (it && query.isEmpty() && recents.isNotEmpty()) recentShown = true },
                onClear = { query = ""; results = emptyList(); recentShown = false; UiState.destination.value = null },
                onSearch = { search() },
            )
            val list: List<SearchResult> = if (recentShown) recents.map { SearchResult(it.name, it.sub, it.lat, it.lon, it.place, it.zoom, it.name) } else results
            if (list.isNotEmpty()) {
                Surface(Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(max = 420.dp), shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 10.dp) {
                    Column {
                        if (recentShown) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t("Ricerche recenti").uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.ExtraBold)
                            TextButton(onClick = { Library.clearRecents(); recentShown = false }) { Text(t("Cancella")) }
                        }
                        LazyColumn(Modifier.padding(6.dp)) {
                            items(list) { r ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { goTo(r) }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                                        Icon(if (recentShown) Icons.Filled.History else if (r.place) Icons.Filled.Place else Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (r.sub.isNotEmpty()) Muted(r.sub)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            FilterBar(car, now, dest, filters, fix)
        }

        // Pulsanti sul lato destro
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End,
        ) {
            MapButton(Icons.Filled.MyLocation, t("La mia posizione"), tint = if (fix != null) MaterialTheme.colorScheme.primary else null) { locate() }
            MapButton(Icons.Filled.Layers, t("Livelli")) { layersOpen = true }
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column {
                    IconButton(onClick = { map.controller.zoomIn() }) { Icon(Icons.Filled.Add, t("Avvicina")) }
                    HorizontalDivider(Modifier.width(40.dp).align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.outlineVariant)
                    IconButton(onClick = { map.controller.zoomOut() }) { Icon(Icons.Filled.Remove, t("Allontana")) }
                }
            }
        }

        // Messaggio di stato e attribuzione
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp, start = 12.dp, end = 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val text = status ?: if (centerEmpty) t("Caricamento parcheggi...") else null
            if (text != null) {
                Surface(shape = RoundedCornerShape(30.dp), color = Color(0xE60F172A), contentColor = Color.White, shadowElevation = 8.dp) {
                    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.size(8.dp))
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)) {
                Text(
                    "© OpenStreetMap contributors · " + MapStyles.byId(style).attribution.removePrefix("© OpenStreetMap").removePrefix(", ").removePrefix(" | © OpenStreetMap").ifEmpty { "ParkMap" },
                    Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // ─── Fogli ───
    selected?.let { el ->
        ModalBottomSheet(onDismissRequest = { UiState.selected.value = null }, sheetState = rememberModalBottomSheetState()) {
            ElementCard(el) {
                UiState.selected.value = null
                val a = el.anchor
                if (a != null) {
                    UiState.report.value = ReportContext(a.lat, a.lon, "${el.type}/${el.id}", OsmRules.title(el.tags), "wrong")
                    nav.go("segnala")
                }
            }
        }
    }

    if (layersOpen) ModalBottomSheet(onDismissRequest = { layersOpen = false }, sheetState = rememberModalBottomSheetState()) { LayersSheet(style, layers) }

    longPress?.let { p ->
        ModalBottomSheet(onDismissRequest = { MapHolder.longPress.value = null }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.5f, %.5f".format(p.lat, p.lon), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ContextAction(Icons.Filled.Place, t("Ho parcheggiato qui")) {
                    MapHolder.longPress.value = null
                    CarStore.saveAt(p.lat, p.lon, "map"); UiState.say(t("Posizione dell'auto salvata")); nav.tab("auto")
                }
                ContextAction(Icons.Filled.Flag, t("Parcheggi vicino a questo punto")) {
                    MapHolder.longPress.value = null
                    UiState.destination.value = Destination(p.lat, p.lon, t("punto scelto")); centerOn(p.lat, p.lon, 17.0)
                    scope.launch {
                        delay(800)
                        var waited = 0
                        while (!ParkingRepo.isReady() && waited < 10_000) { delay(300); waited += 300 }
                        if (UiState.destination.value != null) { UiState.listTab.value = 0; nav.tab("vicini") }
                    }
                }
                ContextAction(Icons.Filled.AddLocationAlt, t("Segnala parcheggio mancante")) {
                    MapHolder.longPress.value = null
                    UiState.report.value = ReportContext(p.lat, p.lon, null, null, "missing"); nav.go("segnala")
                }
                ContextAction(Icons.Filled.Navigation, t("Naviga fin qui")) { MapHolder.longPress.value = null; navigateTo(ctx, p.lat, p.lon) }
            }
        }
    }

    if (sharedOpen) sharedCar?.let { sc ->
        ModalBottomSheet(onDismissRequest = { sharedOpen = false }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Auto condivisa"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (sc.third.isNotEmpty()) Text(sc.third)
                ContextAction(Icons.Filled.Navigation, t("Portami all'auto")) { sharedOpen = false; navigateTo(ctx, sc.first, sc.second, walking = true) }
                ContextAction(Icons.Filled.Place, t("Salva come la mia auto")) {
                    sharedOpen = false
                    CarStore.saveAt(sc.first, sc.second, "shared", note = sc.third)
                    UiState.sharedCar.value = null; nav.tab("auto")
                }
            }
        }
    }
}

@Composable
private fun ContextAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(20.dp)); Text(label, Modifier.padding(start = 10.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label, tint = tint ?: MaterialTheme.colorScheme.onSurface) }
    }
}

/** Pillola di ricerca: marchio, testo, cancella e pulsante di ricerca. */
@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, onClear: () -> Unit, onSearch: () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(27.dp), tonalElevation = 6.dp, shadowElevation = 10.dp,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8)))),
                contentAlignment = Alignment.Center,
            ) { Text("P", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium) }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (query.isEmpty()) Text(t("Cerca luogo o indirizzo"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.Medium),
                    cursorBrush = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, t("Cancella ricerca"), Modifier.size(18.dp)) }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, onClick = onSearch, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Search, t("Cerca"), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

/** Chip sotto la ricerca: auto, destinazione, azzera e i filtri. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(car: io.github.andreadp271.parkmap.data.Car?, now: Long, dest: Destination?, filters: Set<Filter>, fix: Fix?) {
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (car != null) item("car") {
            val expiry = car.expiry
            val left = expiry?.minus(now)
            val warn = left != null && left in 1..CarStore.WARNING_MS
            val expired = left != null && left <= 0
            val text = when {
                left != null && left <= 0 -> t("Sosta scaduta da {d}", "d" to Fmt.duration(-left))
                left != null -> t("Sosta: {d}", "d" to Fmt.duration(left))
                fix != null -> t("Auto a {d}", "d" to Fmt.distance(distanceM(fix.lat, fix.lon, car.lat, car.lon)))
                else -> t("Auto")
            }
            val color = if (expired) MaterialTheme.colorScheme.error else if (warn) Color(0xFFD97706) else MaterialTheme.colorScheme.primary
            AssistChip(
                onClick = { UiState.pendingAction.value = "openCar" }, label = { Text(text, fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(iconFor("car"), null, Modifier.size(18.dp), tint = color) },
                colors = AssistChipDefaults.assistChipColors(containerColor = surface, labelColor = color),
                border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                elevation = AssistChipDefaults.assistChipElevation(elevation = 3.dp),
            )
        }
        if (dest != null) item("dest") {
            AssistChip(
                onClick = { UiState.pendingAction.value = "openList" }, label = { Text(t("Vicino a {name}", "name" to dest.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Flag, null, Modifier.size(18.dp), tint = Color(0xFFBE123C)) },
                trailingIcon = { Icon(Icons.Filled.Close, t("Chiudi"), Modifier.size(18.dp).clickable { UiState.destination.value = null }) },
                colors = AssistChipDefaults.assistChipColors(containerColor = surface),
                elevation = AssistChipDefaults.assistChipElevation(elevation = 3.dp),
            )
        }
        if (filters.isNotEmpty()) item("reset") {
            AssistChip(
                onClick = { UiState.filters.value = emptySet() }, label = { Text(t("Azzera filtri") + " · ${filters.size}") },
                leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = surface),
                elevation = AssistChipDefaults.assistChipElevation(elevation = 3.dp),
            )
        }
        items(Filter.entries) { f ->
            val on = f in filters
            FilterChip(
                selected = on, onClick = { UiState.filters.value = if (on) filters - f else filters + f },
                label = { Text(t(f.label)) },
                leadingIcon = { Icon(filterIcon(f), null, Modifier.size(18.dp)) },
                colors = FilterChipDefaults.filterChipColors(containerColor = surface, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = FilterChipDefaults.filterChipElevation(elevation = 3.dp),
            )
        }
    }
}

private fun filterIcon(f: Filter) = when (f) {
    Filter.FREE_NOW -> iconFor("clock")
    Filter.FREE -> iconFor("circle-check")
    Filter.DISC -> iconFor("timer")
    Filter.DISABLED -> iconFor("accessible")
    Filter.EV -> iconFor("zap")
    Filter.MOTO, Filter.BIKE -> iconFor("bike")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayersSheet(style: String, layers: Set<Layer>) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("Livelli"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SectionTitle(t("Stile della mappa"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapStyles.all.forEach { s ->
                FilterChip(
                    selected = s.id == style, onClick = { AppSettings.setMapStyle(s.id) }, label = { Text(t(s.label)) },
                    leadingIcon = { Box(Modifier.size(18.dp).clip(CircleShape).background(swatch(s.id))) },
                )
            }
        }
        SectionTitle(t("Livelli visibili"))
        Panel(padding = 0.dp) {
            Layer.entries.forEachIndexed { i, l ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().clickable { AppSettings.setLayer(l, l !in layers) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(if (l == Layer.AREAS || l == Layer.SPACES) RoundedCornerShape(4.dp) else CircleShape).background(layerColor(l)))
                    Text(t(l.label), Modifier.weight(1f).padding(start = 14.dp))
                    Switch(checked = l in layers, onCheckedChange = { AppSettings.setLayer(l, it) })
                }
            }
        }
    }
}

private fun swatch(id: String) = when (id) {
    "osm" -> Color(0xFFE8EBD8); "light" -> Color(0xFFDCE8F0); "cycle" -> Color(0xFFD7E4C8); "topo" -> Color(0xFFC9D8C0)
    "gray" -> Color(0xFFD9D9D9); "dark" -> Color(0xFF2A2F3A); "satellite" -> Color(0xFF3F5A3C); else -> Color(0xFF4A5E50)
}

private fun layerColor(l: Layer) = when (l) {
    Layer.AREAS -> Color(0xFF60A5FA); Layer.SPACES -> Color(0xFFFDE047); Layer.METERS -> Color(0xFFF97316)
    Layer.CHARGING -> Color(0xFF059669); Layer.MOTO -> Color(0xFFFCA5A5); Layer.BIKES -> Color(0xFFBEF264)
}
