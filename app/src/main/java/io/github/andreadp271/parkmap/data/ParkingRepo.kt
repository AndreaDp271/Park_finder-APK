package io.github.andreadp271.parkmap.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Riquadro geografico. */
data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Come LatLngBounds.pad di Leaflet: allarga ogni lato di [ratio] volte la dimensione. */
    fun pad(ratio: Double): BBox {
        val h = (north - south) * ratio; val w = (east - west) * ratio
        return BBox(south - h, west - w, north + h, east + w)
    }
}

/** Esito dell'ultima richiesta a un provider dati. */
enum class ProviderState { IDLE, LOADING, OK, FAILED }

class ProviderStatus(val state: ProviderState = ProviderState.IDLE, val reachable: Boolean? = null)

/** Quanto è coperta la zona dai dati statici precalcolati. */
class StaticInfo(val covered: Set<String>, val data: Set<String>, val osmTimestamp: String?)

@Serializable
private class TileFile(val ts: Long, val e: List<OsmElement>)

/**
 * Parcheggi a tessere. L'area è divisa in una griglia fissa di tessere da 0,01° (~1,1 × 0,8 km): ogni tessera
 * scaricata resta in memoria e su disco; spostando la mappa si scaricano solo le tessere nuove, quelle già
 * viste compaiono all'istante e quelle vecchie vengono mostrate subito e aggiornate in background.
 *
 * Fonte: in Italia i file statici precalcolati ogni notte e pubblicati con il sito (scripts/build_tiles.py
 * in Park_finder), nel resto del mondo (o se i file non ci sono) Postpass, con Overpass come riserva.
 * Tutto lo stato si muta sul thread principale; rete e disco girano su Dispatchers.IO.
 */
object ParkingRepo {
    private const val TILE_DEG = 0.01
    private const val TILE_FRESH_MS = 6 * 60 * 60 * 1000L
    private const val TILE_PERSIST_MAX = 400
    private const val MAX_TILES_SPAN = 7
    private const val BLOCK_SIZE = 3
    private const val MAX_PARALLEL_BLOCKS = 2
    const val MIN_DATA_ZOOM = 12.0
    private const val TILES_PER_CELL = 5
    private const val MAX_AUTO_RETRIES = 3
    private const val STATIC_BASE = Http.SITE + "data/"

    private const val POSTPASS_URL = "https://postpass.geofabrik.de/api/interpreter"
    const val POSTPASS_LABEL = "postpass.geofabrik.de"
    private const val POSTPASS_TIMEOUT_MS = 12_000L
    private val OVERPASS_MIRRORS = listOf(
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.monicz.dev/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
    )
    private const val OVERPASS_TIMEOUT_MS = 20_000L
    private const val OVERPASS_GRACE_MS = 1200L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dir: File
    /** Pacchetto offline: le celle precalcolate scaricate in blocco (comprese, in filesDir: non le tocca "Svuota cache"). */
    private lateinit var offlineDir: File
    /** Nazioni (ISO 3166) con dati precalcolati scaricabili in blocco: oggi solo l'Italia. */
    val PACK_COUNTRIES = setOf("IT")
    private const val OFFLINE_FRESH_MS = 7 * 24 * 60 * 60 * 1000L

    private class Tile(var ts: Long, val elements: List<OsmElement>)

    private val tiles = ConcurrentHashMap<String, Tile>()
    private val inflight = HashSet<String>()
    private var blockQueue = ArrayDeque<List<TileRef>>()
    private var activeBlocks = 0
    private var activeCells = 0
    private var generation = 0
    private var consecutiveFailures = 0
    private var retryJob: Job? = null
    private var viewJob: Job? = null
    private var staticFetchNoCache = false
    private var lastView: Triple<BBox, LatLon, Double>? = null
    private var lastSignature = ""

    private val _elements = MutableStateFlow<List<OsmElement>>(emptyList())
    /** Elementi delle tessere attorno alla vista attuale (deduplicati). */
    val elements: StateFlow<List<OsmElement>> = _elements

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _staticInfo = MutableStateFlow<StaticInfo?>(null)
    val staticInfo: StateFlow<StaticInfo?> = _staticInfo
    private var staticSettled = false

    private val _providers = MutableStateFlow<Map<String, ProviderStatus>>(emptyMap())
    val providers: StateFlow<Map<String, ProviderStatus>> = _providers

    /** Errore di caricamento da mostrare all'utente (solo se la zona al centro è ancora vuota). */
    val errors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Il caricamento è in corso e la zona al centro non ha ancora dati: mostra "Caricamento parcheggi...". */
    private val _centerEmpty = MutableStateFlow(false)
    val centerEmpty: StateFlow<Boolean> = _centerEmpty

    val providerLabels: List<String> = listOf(POSTPASS_LABEL) + OVERPASS_MIRRORS.map { shortLabel(it) }

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }
        offlineDir = File(ctx.filesDir, "offline").apply { mkdirs() }
        scope.launch { loadStaticIndex() }
    }

    private fun shortLabel(url: String) = url.removePrefix("https://").substringBefore('/').removePrefix("overpass.")

    private fun setProvider(label: String, state: ProviderState? = null, reachable: Boolean? = null) {
        val cur = _providers.value[label] ?: ProviderStatus()
        _providers.value = _providers.value + (label to ProviderStatus(state ?: cur.state, reachable ?: cur.reachable))
    }

    // ─── Tessere ───

    private class TileRef(val i: Int, val j: Int, val dist: Int) { val key get() = key(i, j) }

    private fun key(i: Int, j: Int) = "$i,$j"
    private fun latToTile(lat: Double) = floor(lat / TILE_DEG).toInt()
    private fun lonToTile(lon: Double) = floor(lon / TILE_DEG).toInt()
    private fun cellKeyOfTile(i: Int, j: Int) = "${Math.floorDiv(i, TILES_PER_CELL)}_${Math.floorDiv(j, TILES_PER_CELL)}"
    private fun isFresh(t: Tile?) = t != null && System.currentTimeMillis() - t.ts < TILE_FRESH_MS
    private fun fileOf(key: String) = File(dir, key.replace(',', '_') + ".json")

    private fun tilesForView(bbox: BBox, center: LatLon): List<TileRef> {
        val b = bbox.pad(0.75)
        val ci = latToTile(center.lat); val cj = lonToTile(center.lon)
        val half = MAX_TILES_SPAN / 2
        val i0 = max(latToTile(b.south), ci - half); val i1 = min(latToTile(b.north), ci + half)
        val j0 = max(lonToTile(b.west), cj - half); val j1 = min(lonToTile(b.east), cj + half)
        val out = mutableListOf<TileRef>()
        for (i in i0..i1) for (j in j0..j1) out += TileRef(i, j, abs(i - ci) + abs(j - cj))
        return out.sortedBy { it.dist }
    }

    private fun readFromDisk(key: String): Tile? = runCatching {
        val f = fileOf(key)
        if (!f.exists()) return null
        val t = json.decodeFromString<TileFile>(f.readText())
        Tile(t.ts, t.e)
    }.getOrNull()

    private fun writeToDisk(entries: Map<String, Tile>) {
        runCatching {
            entries.forEach { (k, t) -> fileOf(k).writeText(json.encodeToString(TileFile.serializer(), TileFile(t.ts, t.elements))) }
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            files.take(max(0, files.size - TILE_PERSIST_MAX)).forEach { it.delete() }
        }
    }

    /** Disegna subito, senza rete, tutto ciò che è in cache per la vista; poi avvia i download mancanti. */
    fun onViewChanged(bbox: BBox, center: LatLon, zoom: Double) {
        lastView = Triple(bbox, center, zoom)
        if (zoom < MIN_DATA_ZOOM) { blockQueue.clear(); updateLoading(); return }
        viewJob?.cancel()
        viewJob = scope.launch {
            val refs = tilesForView(bbox, center)
            val onDisk = withContext(Dispatchers.IO) {
                refs.filter { !tiles.containsKey(it.key) }.mapNotNull { r -> readFromDisk(r.key)?.let { r.key to it } }
            }
            onDisk.forEach { (k, t) -> tiles.putIfAbsent(k, t) }
            publish(refs)
            loadVisible(refs)
        }
    }

    private fun publish(refs: List<TileRef>) {
        val sig = refs.joinToString(";") { r -> r.key + "@" + (tiles[r.key]?.let { it.elements.size.toString() + ":" + it.ts } ?: "-") }
        if (sig != lastSignature) {
            lastSignature = sig
            val seen = HashSet<String>()
            val out = ArrayList<OsmElement>()
            refs.forEach { r -> tiles[r.key]?.elements?.forEach { if (seen.add(it.key)) out += it } }
            _elements.value = out
        }
        updateLoading()
    }

    private fun updateLoading() {
        val loading = activeBlocks + activeCells > 0
        _loading.value = loading
        val c = lastView?.second
        _centerEmpty.value = loading && (c == null || tiles[key(latToTile(c.lat), lonToTile(c.lon))] == null)
    }

    /** Tutte le tessere della vista sono fresche e nulla è in corso (usato per aprire l'elenco dopo una ricerca). */
    fun isReady(): Boolean {
        val c = lastView?.second ?: return false
        return isFresh(tiles[key(latToTile(c.lat), lonToTile(c.lon))]) && activeBlocks + activeCells == 0
    }

    private fun loadVisible(refs: List<TileRef>) {
        if (!staticSettled) return // richiamata da sola appena l'indice è arrivato
        var missing = refs.filter { it.key !in inflight && !isFresh(tiles[it.key]) }

        // Tessere coperte dai dati statici: una richiesta per cella, tutte in parallelo (file da CDN)
        val info = _staticInfo.value
        if (info != null) {
            val cells = LinkedHashSet<String>()
            missing = missing.filter { r ->
                val ck = cellKeyOfTile(r.i, r.j)
                if (ck !in info.covered) true else { cells += ck; false }
            }
            cells.forEach { fetchStaticCell(it, info) }
        }

        // Tessera al centro mai vista: si chiede da sola e per prima (query più leggera, primi dati prima)
        val first = mutableListOf<List<TileRef>>()
        val rest = missing.toMutableList()
        if (rest.size > 1 && rest[0].dist == 0 && tiles[rest[0].key] == null) first += listOf(rest.removeAt(0))

        val blocks = LinkedHashMap<String, MutableList<TileRef>>()
        rest.forEach { r -> blocks.getOrPut("${Math.floorDiv(r.i, BLOCK_SIZE)}:${Math.floorDiv(r.j, BLOCK_SIZE)}") { mutableListOf() } += r }
        // La coda si ricostruisce a ogni spostamento: i blocchi usciti dalla vista si scartano
        blockQueue = ArrayDeque(first + blocks.values.sortedBy { b -> b.minOf { it.dist } })
        pump()
        updateLoading()
    }

    private fun pump() {
        while (activeBlocks < MAX_PARALLEL_BLOCKS && blockQueue.isNotEmpty()) fetchBlock(blockQueue.removeFirst())
    }

    /** "Aggiorna": le tessere restano sulla mappa ma segnate come vecchie, finché quelle nuove non le sostituiscono. */
    fun refresh() {
        generation++
        inflight.clear()
        blockQueue.clear()
        consecutiveFailures = 0
        tiles.values.forEach { it.ts = 0 }
        staticFetchNoCache = true
        lastSignature = ""
        lastView?.let { (b, c, z) -> onViewChanged(b, c, z) }
    }

    /** Riscarica l'indice dei dati precalcolati (data OSM, zone coperte) e le celle della vista, saltando la cache HTTP. */
    fun reloadStatic() {
        scope.launch {
            loadStaticIndex()
            refresh()
        }
    }

    /**
     * Scarica in anticipo un quadrato di [span]×[span] tessere attorno a un punto (setup iniziale), così la zona è pronta anche
     * senza rete. [onProgress] riceve (tessere pronte, totali).
     */
    suspend fun prefetch(center: LatLon, span: Int = 15, onProgress: (Int, Int) -> Unit) {
        val ci = latToTile(center.lat); val cj = lonToTile(center.lon)
        val half = span / 2
        val refs = ArrayList<TileRef>()
        for (i in ci - half..ci + half) for (j in cj - half..cj + half) refs += TileRef(i, j, abs(i - ci) + abs(j - cj))
        refs.sortBy { it.dist }
        var waited = 0
        while (!staticSettled && waited < 8_000) { delay(200); waited += 200 }
        withContext(Dispatchers.IO) {
            refs.filter { !tiles.containsKey(it.key) }.forEach { r -> readFromDisk(r.key)?.let { tiles.putIfAbsent(r.key, it) } }
        }
        loadVisible(refs)
        val start = System.currentTimeMillis()
        while (true) {
            val done = refs.count { isFresh(tiles[it.key]) }
            onProgress(done, refs.size)
            if (done >= refs.size || (activeBlocks + activeCells == 0 && blockQueue.isEmpty()) || System.currentTimeMillis() - start > 90_000) break
            delay(400)
        }
    }

    fun clearCache() {
        generation++
        inflight.clear(); blockQueue.clear()
        tiles.clear()
        dir.listFiles()?.forEach { it.delete() }
        lastSignature = ""
        _elements.value = emptyList()
        updateLoading()
    }

    /** (numero di zone, byte) salvati sul dispositivo. */
    fun cacheStats(): Pair<Int, Long> {
        val files = dir.listFiles().orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /** Etichetta della fonte dati per il centro della mappa, se noto: (precalcolati?, data OSM). */
    fun sourceAt(center: LatLon): Pair<Boolean, String?> {
        val info = _staticInfo.value ?: return false to null
        return (cellKeyOfTile(latToTile(center.lat), lonToTile(center.lon)) in info.covered) to info.osmTimestamp
    }

    // ─── Dati statici ───

    private fun expandRuns(runs: JsonArray): Set<String> {
        val set = HashSet<String>()
        runs.forEach { r ->
            val a = r.jsonArray
            val i = a[0].jsonPrimitive.content.toInt(); val j0 = a[1].jsonPrimitive.content.toInt(); val j1 = a[2].jsonPrimitive.content.toInt()
            for (j in j0..j1) set += "${i}_$j"
        }
        return set
    }

    private suspend fun loadStaticIndex() {
        try {
            val text = withContext(Dispatchers.IO) {
                val req = Http.get(STATIC_BASE + "index.json").newBuilder().cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
                Http.client.fetchText(req)
            }
            val idx = withContext(Dispatchers.Default) { json.parseToJsonElement(text).jsonObject }
            if (idx["v"]?.jsonPrimitive?.longOrNull == 1L && idx["tilesPerCell"]?.jsonPrimitive?.longOrNull == TILES_PER_CELL.toLong()) {
                _staticInfo.value = withContext(Dispatchers.Default) {
                    StaticInfo(
                        expandRuns(idx["covered"]!!.jsonArray), expandRuns(idx["data"]!!.jsonArray),
                        (idx["osmTimestamp"] ?: idx["generated"])?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        } catch (_: Exception) {
            // niente dati statici: si usa solo Postpass/Overpass
        } finally {
            staticSettled = true
            lastView?.let { (b, c, z) -> if (z >= MIN_DATA_ZOOM) loadVisible(tilesForView(b, c)) }
        }
    }

    private fun primitiveString(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    /** [tipo, id, tag, lat, lon] per i nodi, [tipo, id, tag, [lat, lon, lat, lon...]] per le way. */
    internal fun expandStatic(records: JsonArray): List<OsmElement> = records.mapNotNull { rec ->
        val r = rec.jsonArray
        val tags = r[2].jsonObject.mapNotNull { (k, v) -> primitiveString(v)?.let { k to it } }.toMap()
        val id = r[1].jsonPrimitive.long()
        if (r[0].jsonPrimitive.content == "n") OsmElement("node", id, tags, r[3].jsonPrimitive.double(), r[4].jsonPrimitive.double())
        else {
            val flat = r[3].jsonArray
            OsmElement("way", id, tags, g = DoubleArray(flat.size) { flat[it].jsonPrimitive.double() })
        }
    }

    private fun JsonPrimitive.double() = doubleOrNull ?: content.toDouble()
    private fun JsonPrimitive.long() = longOrNull ?: content.toLong()

    private fun offlineFile(cellKey: String) = File(offlineDir, "$cellKey.json.gz")
    private fun packMarker() = File(offlineDir, ".pack")

    private fun parseCell(text: String): List<OsmElement> = expandStatic(json.parseToJsonElement(text).jsonObject["e"]!!.jsonArray)

    private fun readOffline(f: File): String? = runCatching { GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } }.getOrNull()

    private fun writeOffline(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            GZIPOutputStream(tmp.outputStream()).use { it.write(text.toByteArray()) }
            tmp.renameTo(f)
        }
    }

    /** Cella precalcolata: dal pacchetto offline se recente, altrimenti dalla rete (e senza rete, dal pacchetto anche se vecchio). */
    private suspend fun loadStaticCell(cellKey: String, noCache: Boolean): List<OsmElement> {
        val f = offlineFile(cellKey)
        if (f.exists() && !noCache && System.currentTimeMillis() - f.lastModified() < OFFLINE_FRESH_MS) {
            readOffline(f)?.let { return parseCell(it) }
        }
        try {
            val req = Http.get(STATIC_BASE + "cells/$cellKey.json").let { if (noCache) it.newBuilder().cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build() else it }
            val text = Http.client.fetchText(req)
            // con il pacchetto installato la cella si rinnova da sola mentre si usa la mappa
            if (packMarker().exists()) writeOffline(f, text)
            return parseCell(text)
        } catch (e: IOException) {
            readOffline(f)?.let { return parseCell(it) }
            throw e
        }
    }

    /** Stato del pacchetto offline: (celle salvate, byte) o null se non scaricato. */
    fun offlinePack(): Pair<Int, Long>? {
        if (!packMarker().exists()) return null
        val files = offlineDir.listFiles { x -> x.name.endsWith(".json.gz") }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /** Numero di celle con dati nell'area precalcolata (quante ne scarica il pacchetto), o 0 se l'indice non è arrivato. */
    fun packCells(): Int = _staticInfo.value?.data?.size ?: 0

    fun clearOfflinePack() {
        offlineDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Scarica tutte le celle precalcolate (tutta l'area coperta, oggi l'Italia) per usare la mappa anche senza rete.
     * Le celle già scaricate da meno di 7 giorni si saltano, quindi si può richiamare per aggiornare o riprendere.
     * [onProgress] riceve (celle fatte, totali); restituisce quante celle sono fallite.
     */
    suspend fun downloadPack(onProgress: (Int, Int) -> Unit): Int {
        var waited = 0
        while (!staticSettled && waited < 8_000) { delay(200); waited += 200 }
        val info = _staticInfo.value ?: return -1
        val cells = info.data.toList()
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val gate = Semaphore(12)
        withContext(Dispatchers.IO) {
            coroutineScope {
                cells.map { key ->
                    async {
                        gate.withPermit {
                            val f = offlineFile(key)
                            if (!(f.exists() && System.currentTimeMillis() - f.lastModified() < OFFLINE_FRESH_MS)) {
                                var ok = false
                                for (attempt in 0..1) {
                                    try {
                                        val req = Http.get(STATIC_BASE + "cells/$key.json").newBuilder().header("Cache-Control", "no-store").build()
                                        writeOffline(f, Http.client.fetchText(req))
                                        ok = true; break
                                    } catch (e: IOException) { delay(300) }
                                }
                                if (!ok) failed.incrementAndGet()
                            }
                            val d = done.incrementAndGet()
                            if (d % 25 == 0 || d == cells.size) withContext(Dispatchers.Main) { onProgress(d, cells.size) }
                        }
                    }
                }.awaitAll()
            }
        }
        packMarker().writeText(System.currentTimeMillis().toString())
        return failed.get()
    }

    private fun fetchStaticCell(cellKey: String, info: StaticInfo) {
        val gen = generation
        val (ci, cj) = cellKey.split('_').map { it.toInt() }
        val rect = Rect(ci * TILES_PER_CELL, ci * TILES_PER_CELL + TILES_PER_CELL - 1, cj * TILES_PER_CELL, cj * TILES_PER_CELL + TILES_PER_CELL - 1)
        val keys = rect.keys()
        inflight += keys
        activeCells++
        updateLoading()
        val noCache = staticFetchNoCache
        scope.launch {
            try {
                val elements = if (cellKey in info.data) withContext(Dispatchers.IO) { loadStaticCell(cellKey, noCache) } else emptyList()
                if (gen == generation) applyBlock(elements, rect, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen == generation) reportError()
            } finally {
                inflight -= keys.toSet()
                activeCells--
                updateLoading()
            }
        }
    }

    // ─── Postpass e Overpass ───

    private class Rect(val i0: Int, val i1: Int, val j0: Int, val j1: Int) {
        fun keys(): List<String> = buildList { for (i in i0..i1) for (j in j0..j1) add(key(i, j)) }
    }

    internal fun parkingSql(s: Double, w: Double, n: Double, e: Double) =
        "SELECT osm_type, osm_id, tags, geom FROM postpass_pointlinepolygon WHERE (" +
            "tags->>'amenity' = 'parking' OR " +
            "(tags->>'amenity' = 'parking_space' AND tags->>'parking_space' <> 'normal') OR " +
            "(tags->>'amenity' = 'vending_machine' AND tags->>'vending' = 'parking_tickets') OR " +
            "tags->>'amenity' IN ('charging_station', 'motorcycle_parking', 'bicycle_parking')" +
            ") AND geom && ST_MakeEnvelope($w,$s,$e,$n,4326)"

    internal fun overpassQuery(s: Double, w: Double, n: Double, e: Double) = """[out:json][timeout:25][bbox:$s,$w,$n,$e];(
        way["amenity"="parking"];
        relation["amenity"="parking"];
        node["amenity"="parking"];
        node["amenity"="parking_space"]["parking_space"]["parking_space"!="normal"];
        way["amenity"="parking_space"]["parking_space"]["parking_space"!="normal"];
        node["amenity"="vending_machine"]["vending"="parking_tickets"];
        way["amenity"="vending_machine"]["vending"="parking_tickets"];
        relation["amenity"="vending_machine"]["vending"="parking_tickets"];
        nw["amenity"~"^(charging_station|motorcycle_parking|bicycle_parking)$"];
      );out body geom qt;"""

    private suspend fun postForm(url: String, data: String, timeoutMs: Long): String = withTimeout(timeoutMs) {
        val req = okhttp3.Request.Builder().url(url).post(FormBody.Builder().add("data", data).build()).build()
        Http.client.newCall(req).await().use { r ->
            setProviderMain(shortLabelOf(url), reachable = true)
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }
    }

    private fun shortLabelOf(url: String) = if (url == POSTPASS_URL) POSTPASS_LABEL else shortLabel(url)

    private fun tagMap(o: JsonObject?): Map<String, String> =
        o?.mapNotNull { (k, v) -> primitiveString(v)?.let { k to it } }?.toMap() ?: emptyMap()

    /** GeoJSON di Postpass → elementi. Dei multipoligoni si disegna l'anello esterno più grande. */
    internal fun parsePostpass(text: String): List<OsmElement> {
        val fc = json.parseToJsonElement(text).jsonObject
        return fc["features"]?.jsonArray.orEmpty().mapNotNull { f ->
            val p = f.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
            val g = f.jsonObject["geometry"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject ?: return@mapNotNull null
            val id = p["osm_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val type = when (primitiveString(p["osm_type"])) { "N" -> "node"; "R" -> "relation"; else -> "way" }
            val tags = tagMap(p["tags"] as? JsonObject)
            val coords = g["coordinates"] ?: return@mapNotNull null
            fun ring(a: JsonArray): DoubleArray? {
                if (a.size < 2) return null
                val out = DoubleArray(a.size * 2)
                a.forEachIndexed { k, c -> val cc = c.jsonArray; out[2 * k] = cc[1].jsonPrimitive.double(); out[2 * k + 1] = cc[0].jsonPrimitive.double() }
                return out
            }
            when (primitiveString(g["type"])) {
                "Point" -> OsmElement(type, id, tags, coords.jsonArray[1].jsonPrimitive.double(), coords.jsonArray[0].jsonPrimitive.double())
                "LineString" -> ring(coords.jsonArray)?.let { OsmElement(type, id, tags, g = it) }
                "Polygon" -> ring(coords.jsonArray[0].jsonArray)?.let { OsmElement(type, id, tags, g = it) }
                "MultiPolygon" -> coords.jsonArray.map { it.jsonArray[0].jsonArray }.maxByOrNull { it.size }?.let { r -> ring(r)?.let { OsmElement(type, id, tags, g = it) } }
                else -> null
            }
        }
    }

    internal fun parseOverpass(text: String): List<OsmElement> {
        val root = json.parseToJsonElement(text).jsonObject
        return root["elements"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val type = primitiveString(o["type"]) ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val tags = tagMap(o["tags"] as? JsonObject)
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull; val lon = o["lon"]?.jsonPrimitive?.doubleOrNull
            val geom = (o["geometry"] as? JsonArray)?.let { a ->
                DoubleArray(a.size * 2).also { out ->
                    a.forEachIndexed { k, p -> val po = p.jsonObject; out[2 * k] = po["lat"]!!.jsonPrimitive.double(); out[2 * k + 1] = po["lon"]!!.jsonPrimitive.double() }
                }
            }
            if (lat == null && (geom == null || geom.size < 2)) null else OsmElement(type, id, tags, lat, lon, geom)
        }
    }

    /**
     * Mirror Overpass interrogati in parallelo: appena il primo risponde i dati vanno sulla mappa; dopo una breve
     * finestra ([OVERPASS_GRACE_MS]) si prende il migliore (più elementi) senza aspettare il mirror più lento.
     */
    private class Outcome(val els: List<OsmElement>?)

    private suspend fun overpassRace(query: String, onUpdate: (List<OsmElement>) -> Unit): List<OsmElement> = coroutineScope {
        val results = Channel<Outcome>(Channel.UNLIMITED)
        OVERPASS_MIRRORS.forEach { url ->
            val label = shortLabel(url)
            setProvider(label, ProviderState.LOADING)
            launch(Dispatchers.IO) {
                try {
                    val els = parseOverpass(postForm(url, query, OVERPASS_TIMEOUT_MS))
                    setProviderMain(label, ProviderState.OK)
                    results.send(Outcome(els))
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    setProviderMain(label, ProviderState.FAILED, reachable = if (e is IOException && e.message?.startsWith("HTTP") != true) false else null)
                    results.send(Outcome(null))
                }
            }
        }
        var best: List<OsmElement>? = null
        var pending = OVERPASS_MIRRORS.size
        var deadline = 0L
        while (pending > 0) {
            val r = if (deadline == 0L) results.receive()
            else withTimeoutOrNull(max(1L, deadline - System.currentTimeMillis())) { results.receive() } ?: break
            pending--
            val els = r.els
            if (els != null) {
                if (best == null || els.size > best.size) { best = els; withContext(Dispatchers.Main.immediate) { onUpdate(els) } }
                if (deadline == 0L) deadline = System.currentTimeMillis() + OVERPASS_GRACE_MS
            }
        }
        coroutineContext[Job]?.children?.forEach { it.cancel() }
        best ?: throw IOException("Tutti i mirror Overpass hanno fallito o sono scaduti")
    }

    private fun setProviderMain(label: String, state: ProviderState? = null, reachable: Boolean? = null) {
        scope.launch { setProvider(label, state, reachable) }
    }

    private fun fetchBlock(block: List<TileRef>) {
        val gen = generation
        val rect = Rect(block.minOf { it.i }, block.maxOf { it.i }, block.minOf { it.j }, block.maxOf { it.j })
        val keys = rect.keys()
        inflight += keys
        activeBlocks++
        updateLoading()
        val s = rect.i0 * TILE_DEG; val w = rect.j0 * TILE_DEG
        val n = (rect.i1 + 1) * TILE_DEG; val e = (rect.j1 + 1) * TILE_DEG
        fun r4(x: Double) = Math.round(x * 10000) / 10000.0

        scope.launch {
            try {
                var fromPostpass: List<OsmElement>? = null
                try {
                    setProvider(POSTPASS_LABEL, ProviderState.LOADING)
                    fromPostpass = withContext(Dispatchers.IO) { parsePostpass(postForm(POSTPASS_URL, parkingSql(r4(s), r4(w), r4(n), r4(e)), POSTPASS_TIMEOUT_MS)) }
                    setProvider(POSTPASS_LABEL, ProviderState.OK)
                } catch (ex: Exception) {
                    if (ex is CancellationException && ex !is TimeoutCancellationException) throw ex
                    setProvider(POSTPASS_LABEL, ProviderState.FAILED, if (ex is IOException) false else null)
                }
                if (fromPostpass != null) {
                    if (gen == generation) { applyBlock(fromPostpass, rect, true); consecutiveFailures = 0 }
                    return@launch
                }
                var lastRendered: List<OsmElement>? = null
                var settled = false
                val data = overpassRace(overpassQuery(r4(s), r4(w), r4(n), r4(e))) { partial ->
                    // Mostra subito il primo dato utile; uno migliore, arrivato in ritardo, lo sostituisce
                    if (gen == generation) { applyBlock(partial, rect, settled); lastRendered = partial }
                }
                settled = true
                if (gen == generation) {
                    if (data !== lastRendered) applyBlock(data, rect, true) else persist(keys)
                    consecutiveFailures = 0
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                if (gen == generation) {
                    // I mirror pubblici sono spesso sovraccarichi per brevi periodi: si riprova da soli, con attesa crescente
                    if (consecutiveFailures < MAX_AUTO_RETRIES) {
                        consecutiveFailures++
                        retryJob?.cancel()
                        val wait = 2000L * consecutiveFailures
                        retryJob = scope.launch { kotlinx.coroutines.delay(wait); lastView?.let { (b, c, z) -> onViewChanged(b, c, z) } }
                    } else reportError()
                }
            } finally {
                inflight -= keys.toSet()
                activeBlocks--
                pump()
                updateLoading()
            }
        }
    }

    private fun positionOf(el: OsmElement): LatLon? = el.anchor

    /** Distribuisce gli elementi nelle tessere del rettangolo (anche quelle vuote: "qui non c'è nulla" è un dato) e salva. */
    private fun applyBlock(elements: List<OsmElement>, rect: Rect, persist: Boolean) {
        val buckets = LinkedHashMap<String, MutableList<OsmElement>>()
        rect.keys().forEach { buckets[it] = mutableListOf() }
        elements.forEach { el ->
            val pos = positionOf(el)
            // Gli elementi a cavallo del bordo finiscono nella tessera più vicina del rettangolo
            val i = min(rect.i1, max(rect.i0, pos?.let { latToTile(it.lat) } ?: rect.i0))
            val j = min(rect.j1, max(rect.j0, pos?.let { lonToTile(it.lon) } ?: rect.j0))
            buckets[key(i, j)]!!.add(el)
        }
        val ts = System.currentTimeMillis()
        val fresh = buckets.mapValues { Tile(ts, it.value) }
        fresh.forEach { (k, t) -> tiles[k] = t }
        if (persist) scope.launch(Dispatchers.IO) { writeToDisk(fresh) }
        lastView?.let { (b, c, _) -> publish(tilesForView(b, c)) }
    }

    private fun persist(keys: List<String>) {
        val entries = keys.mapNotNull { k -> tiles[k]?.let { k to it } }.toMap()
        scope.launch(Dispatchers.IO) { writeToDisk(entries) }
    }

    private fun reportError() {
        val c = lastView?.second
        if (c != null && tiles[key(latToTile(c.lat), lonToTile(c.lon))] != null) return // si continua a mostrare la cache, anche se vecchia
        errors.tryEmit(Unit)
    }
}
