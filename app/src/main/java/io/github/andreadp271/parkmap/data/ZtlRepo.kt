package io.github.andreadp271.parkmap.data

import android.content.Context
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import kotlin.math.floor

/**
 * Zona a traffico limitato (boundary=limited_traffic_zone) o strada con accesso solo con permesso / vietato a certe ore
 * ([street]: dove la ZTL non è disegnata come zona ma è mappata strada per strada, come a Bassano).
 * [rings] = anelli esterni (lat, lon, lat, lon...); se [area] è falso è una linea lungo la strada.
 */
@Serializable
class Ztl(val id: String, val tags: Map<String, String>, val rings: List<DoubleArray>, val area: Boolean, val street: Boolean = false) {
    val name: String get() = tags["name"] ?: tags["official_name"] ?: tags["alt_name"] ?: ""

    /** Ingombro approssimato (gradi²): tra zone una dentro l'altra vince la più piccola. */
    val size: Double by lazy(LazyThreadSafetyMode.NONE) {
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        rings.forEach { g -> for (i in 0 until g.size / 2) { s = minOf(s, g[2 * i]); n = maxOf(n, g[2 * i]); w = minOf(w, g[2 * i + 1]); e = maxOf(e, g[2 * i + 1]) } }
        (n - s) * (e - w)
    }

    /** Il punto è dentro la zona (regola pari-dispari su tutti gli anelli). */
    fun contains(lat: Double, lon: Double): Boolean {
        if (!area) return false
        var c = false
        rings.forEach { g ->
            val n = g.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val yi = g[2 * i]; val xi = g[2 * i + 1]; val yj = g[2 * j]; val xj = g[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
                j = i
            }
        }
        return c
    }

    /** Centro del riquadro: dove mettere la segnalazione di una ZTL sbagliata. */
    val center: LatLon get() {
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        rings.forEach { g -> for (i in 0 until g.size / 2) { s = minOf(s, g[2 * i]); n = maxOf(n, g[2 * i]); w = minOf(w, g[2 * i + 1]); e = maxOf(e, g[2 * i + 1]) } }
        return LatLon((s + n) / 2, (w + e) / 2)
    }

    /**
     * La ZTL è attiva in questo momento per le auto? null se OSM non dice né chi può entrare né quando (va trattata come attiva).
     * Si valutano access → vehicle → motor_vehicle → motorcar: vince la chiave più specifica che ha un valore adesso.
     */
    fun activeAt(cal: Calendar): Boolean? {
        var known = false
        var effective: String? = null
        for (k in HIERARCHY) {
            val base = tags[k]
            val cond = tags["$k:conditional"]
            if (base == null && cond == null) continue
            known = true
            var v = base
            cond?.let { c ->
                OsmRules.splitRules(c).forEach { rule ->
                    val w = rule.substringAfter('@', "").trim().removeSurrounding("(", ")")
                    if (w.isNotEmpty() && timeMatches(w, cal)) v = rule.substringBefore('@').trim()
                }
            }
            if (v != null) effective = v
        }
        return if (known) effective in BLOCKING else null
    }

    /** Distanza in metri dalla linea (solo per le ZTL mappate come strada). */
    fun distanceM(p: LatLon): Double {
        var best = Double.MAX_VALUE
        if (area) return best
        rings.forEach { g -> for (i in 0 until g.size / 2 - 1) best = minOf(best, distanceToSegmentM(p, LatLon(g[2 * i], g[2 * i + 1]), LatLon(g[2 * i + 2], g[2 * i + 3]))) }
        return best
    }

    /** Orari e regole di accesso, come (chi, regola) già in parole, dai tag ...:conditional e da quelli semplici. */
    fun rules(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        RULE_KEYS.forEach { (k, label) ->
            tags[k]?.takeIf { it != "yes" || tags["$k:conditional"] == null }?.let { out += label to humanize(it) }
            tags["$k:conditional"]?.let { out += label to humanize(it) }
        }
        return out
    }

    companion object {
        private val RULE_KEYS = listOf(
            "access" to "Tutti", "vehicle" to "Veicoli", "motor_vehicle" to "Veicoli a motore", "motorcar" to "Auto",
            "motorcycle" to "Moto", "goods" to "Merci", "hgv" to "Mezzi pesanti",
        )
        /** Chiavi che rendono una strada "a traffico limitato" (vedi [restricts]). */
        val STREET_KEYS = listOf("motor_vehicle", "vehicle", "motorcar", "access")
        private val HIERARCHY = listOf("access", "vehicle", "motor_vehicle", "motorcar")
        private val BLOCKING = setOf("permit", "no", "private", "destination", "delivery", "customers", "agricultural", "forestry")
        private const val MONTH = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
        private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val MONTHS_RE = Regex("""^($MONTH(?:\s*-\s*$MONTH)?(?:\s*,\s*$MONTH(?:\s*-\s*$MONTH)?)*)\s*:?\s*(.*)$""")

        /** Orario OSM come "Apr-Oct: Mo-Fr 20:30-24:00; Su": i mesi davanti si controllano qui, il resto con [OsmRules.conditionMatches]. */
        fun timeMatches(w: String, cal: Calendar): Boolean = w.split(';').any { raw ->
            val part = raw.trim()
            val m = MONTHS_RE.matchEntire(part)
            if (m == null) OsmRules.conditionMatches(part, cal)
            else monthsMatch(m.groupValues[1], cal.get(Calendar.MONTH)) && (m.groupValues[2].isBlank() || OsmRules.conditionMatches(m.groupValues[2], cal))
        }

        private fun monthsMatch(spec: String, month: Int): Boolean = spec.split(',').any { seg ->
            val p = seg.split('-').map { MONTH_NAMES.indexOf(it.trim()) }
            val a = p[0]; val b = p.getOrElse(1) { a }
            if (a < 0 || b < 0) false else if (a <= b) month in a..b else month >= a || month <= b
        }
        private val VALUES = mapOf(
            "permit" to "solo con permesso", "no" to "vietato", "yes" to "consentito", "destination" to "solo per chi è diretto lì",
            "private" to "privato", "delivery" to "carico e scarico", "designated" to "consentito", "customers" to "solo clienti",
        )
        private val DAYS = mapOf("Mo" to "lun", "Tu" to "mar", "We" to "mer", "Th" to "gio", "Fr" to "ven", "Sa" to "sab", "Su" to "dom", "PH" to "festivi")
        private val MONTHS = mapOf(
            "Jan" to "gen", "Feb" to "feb", "Mar" to "mar", "Apr" to "apr", "May" to "mag", "Jun" to "giu",
            "Jul" to "lug", "Aug" to "ago", "Sep" to "set", "Oct" to "ott", "Nov" to "nov", "Dec" to "dic",
        )
        private val YEAR = Regex("""\b(19|20)\d\d\b""")
        private val COND = Regex("""^\s*(permit|no)\s*@""")
        private val SPLIT = Regex(""";\s*(?![^()]*\))""")
        private val TOKEN = Regex("""\b(PH|[A-Z][a-z]{1,2})\b""")

        /** La strada chiede il permesso o è vietata a certe ore (ignorando le chiusure temporanee con una data: lavori, eventi). */
        fun restricts(tags: Map<String, String>): Boolean = STREET_KEYS.any { k ->
            tags[k] == "permit" || tags["$k:conditional"]?.split(SPLIT)?.any { c -> COND.containsMatchIn(c) && !YEAR.containsMatchIn(c) } == true
        }

        /** "permit @ (Apr-Oct: Mo-Fr 20:30-24:00)" → "solo con permesso: apr-ott: lun-ven 20:30-24:00". */
        fun humanize(v: String): String = v.split(SPLIT).joinToString("\n") { part ->
            val value = part.substringBefore('@').trim()
            val cond = part.substringAfter('@', "").trim().removeSurrounding("(", ")")
                .replace(Regex(""";\s*"""), "\n").replace(Regex(""",(?=\S)"""), ", ")
            val word = VALUES[value]?.let { t(it) } ?: value
            if (cond.isEmpty()) word
            else "$word: " + if (T.lang == "it") cond.replace(TOKEN) { m -> DAYS[m.value] ?: MONTHS[m.value] ?: m.value } else cond
        }
    }
}

@Serializable
private class ZtlCell(val ts: Long, val z: List<Ztl>, val v: Int = 1)

/**
 * ZTL da OpenStreetMap via Postpass, a celle grandi (0,5°, ~50 km): sono poche e cambiano di rado, quindi
 * ogni cella si scarica una volta e resta su disco per una settimana. Dove nessuno le ha mappate non c'è nulla.
 */
object ZtlRepo {
    private const val CELL_DEG = 0.5
    private const val FRESH_MS = 7 * 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 60 * 1000L
    const val MIN_ZOOM = 11.0
    /** Da alzare quando cambia cosa si scarica: le celle salvate con un formato vecchio si riscaricano. */
    private const val CELL_VERSION = 2
    private const val SKIP_HIGHWAYS = "'footway','path','cycleway','steps','pedestrian','track','service','bridleway','corridor','platform','construction','proposed'"
    private const val POSTPASS_URL = "https://postpass.geofabrik.de/api/interpreter"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dir: File

    private val cells = HashMap<String, ZtlCell>()
    private val inflight = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private var lastKeys: List<String> = emptyList()

    private val _zones = MutableStateFlow<List<Ztl>>(emptyList())
    /** ZTL delle celle attorno alla vista attuale. */
    val zones: StateFlow<List<Ztl>> = _zones

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.cacheDir, "ztl").apply { mkdirs() }
    }

    /** ZTL che contiene il punto, tra quelle caricate (la più piccola, se sono una dentro l'altra). */
    fun at(lat: Double, lon: Double, zones: List<Ztl> = _zones.value): Ztl? = zones.filter { it.contains(lat, lon) }.minByOrNull { it.size }

    /** ZTL che contiene il punto o, se non c'è, la strada a traffico limitato più vicina entro [maxM] metri. */
    fun atOrNear(lat: Double, lon: Double, maxM: Double, zones: List<Ztl> = _zones.value): Ztl? =
        at(lat, lon, zones) ?: LatLon(lat, lon).let { p -> zones.filter { !it.area }.map { it to it.distanceM(p) }.filter { it.second <= maxM }.minByOrNull { it.second }?.first }

    private fun cellOf(lat: Double, lon: Double) = "${floor(lat / CELL_DEG).toInt()}_${floor(lon / CELL_DEG).toInt()}"
    private fun fileOf(key: String) = File(dir, "$key.json")

    fun onViewChanged(bbox: BBox, zoom: Double) {
        if (!::dir.isInitialized || zoom < MIN_ZOOM) return
        val keys = LinkedHashSet<String>()
        for (lat in listOf(bbox.south, bbox.north)) for (lon in listOf(bbox.west, bbox.east)) keys += cellOf(lat, lon)
        lastKeys = keys.toList()
        scope.launch {
            val now = System.currentTimeMillis()
            lastKeys.filter { it !in cells }.forEach { k ->
                withContext(Dispatchers.IO) { readFromDisk(k) }?.let { cells[k] = it }
            }
            publish()
            lastKeys.filter { k ->
                k !in inflight && (cells[k]?.let { now - it.ts > FRESH_MS } ?: true) && (failedAt[k]?.let { now - it > RETRY_MS } ?: true)
            }.forEach { fetch(it) }
        }
    }

    private fun publish() {
        val seen = HashSet<String>()
        val all = lastKeys.flatMap { k -> cells[k]?.z.orEmpty() }.filter { seen.add(it.id) }
        // Le strade dentro una zona già disegnata non aggiungono nulla e coprirebbero la mappa
        val areas = all.filter { it.area }
        _zones.value = all.filter { z ->
            if (!z.street) return@filter true
            val g = z.rings[0]; val m = (g.size / 4) * 2
            areas.none { it.contains(g[m], g[m + 1]) }
        }
    }

    private fun readFromDisk(key: String): ZtlCell? = runCatching {
        fileOf(key).takeIf { it.exists() }?.let { json.decodeFromString<ZtlCell>(it.readText()) }?.takeIf { it.v == CELL_VERSION }
    }.getOrNull()

    private fun fetch(key: String) {
        inflight += key
        val (i, j) = key.split('_').map { it.toInt() }
        val s = i * CELL_DEG; val w = j * CELL_DEG
        // Zone ZTL e, dove la ZTL è mappata strada per strada, le strade con accesso solo con permesso o vietato a certe ore
        val streetCond = Ztl.STREET_KEYS.joinToString(" OR ") { k -> "tags->>'$k' = 'permit' OR tags->>'$k:conditional' ~ '^\\s*(permit|no)\\s*@'" }
        val sql = "SELECT osm_type, osm_id, tags, geom FROM postpass_pointlinepolygon WHERE (tags->>'boundary' = 'limited_traffic_zone' OR " +
            "(tags ? 'highway' AND tags->>'highway' NOT IN ($SKIP_HIGHWAYS) AND ($streetCond))) " +
            "AND geom && ST_MakeEnvelope($w,$s,${w + CELL_DEG},${s + CELL_DEG},4326)"
        scope.launch {
            try {
                val cell = withContext(Dispatchers.IO) {
                    val text = withTimeout(20_000) {
                        val req = okhttp3.Request.Builder().url(POSTPASS_URL).post(FormBody.Builder().add("data", sql).build()).build()
                        Http.client.fetchText(req)
                    }
                    ZtlCell(System.currentTimeMillis(), parse(text), CELL_VERSION).also { c ->
                        runCatching { fileOf(key).writeText(json.encodeToString(ZtlCell.serializer(), c)) }
                    }
                }
                cells[key] = cell
                failedAt -= key
                publish()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                failedAt[key] = System.currentTimeMillis() // si riprova al prossimo spostamento, dopo un minuto
            } finally {
                inflight -= key
            }
        }
    }

    /**
     * Postpass restituisce ogni relazione due volte (come area e come contorno) e anche le way che la compongono:
     * si tengono le aree, le way chiuse e le way aperte con un nome (ZTL mappate come strada); il resto è contorno.
     */
    internal fun parse(text: String): List<Ztl> {
        val fc = json.parseToJsonElement(text).jsonObject
        val out = LinkedHashMap<String, Ztl>()
        fc["features"]?.jsonArray.orEmpty().forEach { f ->
            val p = f.jsonObject["properties"]?.jsonObject ?: return@forEach
            val g = f.jsonObject["geometry"] as? JsonObject ?: return@forEach
            val id = p["osm_id"]?.jsonPrimitive?.longOrNull ?: return@forEach
            val type = when ((p["osm_type"] as? JsonPrimitive)?.contentOrNull) { "R" -> "relation"; "N" -> return@forEach; else -> "way" }
            val tags = (p["tags"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            val coords = g["coordinates"] as? JsonArray ?: return@forEach
            val key = "$type:$id"
            when ((g["type"] as? JsonPrimitive)?.contentOrNull) {
                "MultiPolygon" -> out[key] = Ztl(key, tags, coords.mapNotNull { ring(it.jsonArray[0].jsonArray) }, true)
                "Polygon" -> ring(coords[0].jsonArray)?.let { out[key] = Ztl(key, tags, listOf(it), true) }
                "MultiLineString", "LineString" -> {
                    if (type == "relation" || key in out) return@forEach
                    if (tags["highway"] != null && tags["boundary"] != "limited_traffic_zone") {
                        if (!Ztl.restricts(tags)) return@forEach
                        val lines = if (g["type"]!!.jsonPrimitive.content == "LineString") listOf(coords) else coords.map { it.jsonArray }
                        val rings = lines.mapNotNull { ring(it) }
                        if (rings.isNotEmpty()) out[key] = Ztl(key, tags, rings, area = false, street = true)
                        return@forEach
                    }
                    val lines = if (g["type"]!!.jsonPrimitive.content == "LineString") listOf(coords) else coords.map { it.jsonArray }
                    val rings = lines.mapNotNull { ring(it) }
                    if (rings.isEmpty()) return@forEach
                    val closed = rings.all { r -> r.size >= 8 && r[0] == r[r.size - 2] && r[1] == r[r.size - 1] }
                    if (closed || tags["name"] != null) out[key] = Ztl(key, tags, rings, closed)
                }
            }
        }
        return out.values.filter { it.rings.isNotEmpty() }
    }

    private fun ring(a: JsonArray): DoubleArray? {
        if (a.size < 2) return null
        val out = DoubleArray(a.size * 2)
        a.forEachIndexed { k, c ->
            val cc = c.jsonArray
            out[2 * k] = cc[1].jsonPrimitive.let { it.doubleOrNull ?: it.content.toDouble() }
            out[2 * k + 1] = cc[0].jsonPrimitive.let { it.doubleOrNull ?: it.content.toDouble() }
        }
        return out
    }
}
