package io.github.andreadp271.parkmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/** Un risultato di ricerca: una località ([place] = si centra la mappa) o un indirizzo/luogo (diventa la destinazione). */
class SearchResult(val name: String, val sub: String, val lat: Double, val lon: Double, val place: Boolean, val zoom: Double?, val display: String)

sealed interface SearchOutcome {
    class Results(val items: List<SearchResult>, val message: String) : SearchOutcome
    class Jump(val lat: Double, val lon: Double, val zoom: Double, val message: String) : SearchOutcome
    class NotFound(val message: String) : SearchOutcome
}

/**
 * Ricerca località in tutto il mondo. Prima si cercano i nodi OSM reali vicini alla zona visualizzata
 * (Postpass, 0,2 s), poi Nominatim. I risultati vicini vengono prima: "Valencia" cercata dall'Italia porta
 * comunque in Spagna, ma tra due omonimi vince quello più vicino.
 */
object Geocoder {
    private const val POSTPASS = "https://postpass.geofabrik.de/api/interpreter"
    private val json = Json { ignoreUnknownKeys = true }

    private val placeTypeLabels = mapOf(
        "city" to "Città", "town" to "Comune", "village" to "Paese", "hamlet" to "Frazione",
        "suburb" to "Quartiere", "municipality" to "Comune", "borough" to "Quartiere", "locality" to "Località",
    )
    private val italianLower = setOf("di", "del", "della", "dei", "delle", "dello", "d", "a", "al", "allo", "alla", "agli", "alle", "sul", "sulla", "sui", "e", "in", "l")

    private fun sql(s: String) = "'" + s.replace("'", "''") + "'"

    private fun titleCase(q: String): String = q.trim().split(Regex("\\s+")).mapIndexed { i, w ->
        if (i > 0 && w.lowercase() in italianLower) w.lowercase() else w.replaceFirstChar { it.uppercase() } .let { it.take(1) + it.drop(1).lowercase() }
    }.joinToString(" ")

    private class Point(val lat: Double, val lon: Double, val tags: Map<String, String>)

    private suspend fun postpassPoints(where: String, center: LatLon, deg: Double, limit: Int, timeoutMs: Long): List<Point> {
        val w = (center.lon - deg).coerceAtLeast(-180.0); val e = (center.lon + deg).coerceAtMost(180.0)
        val s = (center.lat - deg).coerceAtLeast(-90.0); val n = (center.lat + deg).coerceAtMost(90.0)
        val query = "SELECT osm_type, osm_id, tags, geom FROM postpass_point WHERE $where AND geom && ST_MakeEnvelope($w,$s,$e,$n,4326) LIMIT $limit"
        val text = withTimeout(timeoutMs) {
            Http.client.newCall(Request.Builder().url(POSTPASS).post(FormBody.Builder().add("data", query).build()).build()).await().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.string()
            }
        }
        return withContext(Dispatchers.Default) {
            json.parseToJsonElement(text).jsonObject["features"]?.jsonArray.orEmpty().mapNotNull { f ->
                val g = f.jsonObject["geometry"] as? JsonObject ?: return@mapNotNull null
                if (g["type"]?.jsonPrimitive?.contentOrNull != "Point") return@mapNotNull null
                val c = g["coordinates"]!!.jsonArray
                val tags = (f.jsonObject["properties"]?.jsonObject?.get("tags") as? JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
                Point(c[1].jsonPrimitive.content.toDouble(), c[0].jsonPrimitive.content.toDouble(), tags)
            }
        }
    }

    private suspend fun nominatim(params: String): JsonArray {
        val url = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&$params"
        val text = Http.client.fetchText(Http.get(url, "Accept-Language" to Locale.getDefault().toLanguageTag()))
        return withContext(Dispatchers.Default) { json.parseToJsonElement(text).jsonArray }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject

    /** Codice postale: il nodo OSM reale più vicino, non il centroide di Nominatim; fuori zona, Nominatim in tutto il mondo. */
    private suspend fun postcode(code: String, center: LatLon): SearchOutcome? = coroutineScope {
        val worldwide = async { runCatching { nominatim("postalcode=${enc(code)}&limit=10") }.getOrDefault(JsonArray(emptyList())) }
        val nearby = async {
            runCatching {
                postpassPoints("(tags->>'postal_code' = ${sql(code)} OR tags->>'addr:postcode' = ${sql(code)})", center, 3.0, 5, 5000)
            }.getOrDefault(emptyList())
        }
        // Il nodo reale vicino ha la precedenza, ma non lo si aspetta oltre 1,5 s dalla risposta di Nominatim
        val world = worldwide.await()
        val near = withTimeoutOrNull(1500) { nearby.await() } ?: emptyList()
        if (near.isNotEmpty()) {
            val p = near.minBy { distanceM(center.lat, center.lon, it.lat, it.lon) }
            return@coroutineScope SearchOutcome.Jump(p.lat, p.lon, 15.0, t("CAP trovato"))
        }
        val results = world.mapNotNull { e ->
            val r = e.jsonObject
            val a = r.obj("address")
            val city = a?.str("city") ?: a?.str("town") ?: a?.str("village") ?: a?.str("county")
            val country = a?.str("country")
            val lat = r.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = r.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            SearchResult(code, listOfNotNull(city, country).joinToString(", "), lat, lon, true, 15.0, listOfNotNull(code, city, country).joinToString(", "))
        }.sortedBy { distanceM(center.lat, center.lon, it.lat, it.lon) }
        when {
            results.isEmpty() -> null
            results.size == 1 -> SearchOutcome.Jump(results[0].lat, results[0].lon, 15.0, t("CAP trovato"))
            else -> SearchOutcome.Results(results.take(6), t("Seleziona un risultato"))
        }
    }

    /** Nodo OSM reale place=* con il nome esatto, entro ~600 km dalla zona visualizzata. */
    private suspend fun exactPlaceNode(q: String, center: LatLon): List<SearchResult> = try {
        val names = setOf(q.trim(), titleCase(q)).joinToString(",") { sql(it) }
        postpassPoints("tags ? 'place' AND tags->>'name' IN ($names)", center, 6.0, 10, 5000)
            .sortedBy { distanceM(center.lat, center.lon, it.lat, it.lon) }
            .map { p ->
                val label = placeTypeLabels[p.tags["place"]]?.let { t(it) }
                SearchResult(p.tags["name"].orEmpty(), label.orEmpty(), p.lat, p.lon, true, null, p.tags["name"].orEmpty() + (label?.let { " ($it)" } ?: ""))
            }
    } catch (_: Exception) {
        emptyList()
    }

    /** Nominatim: località e anche indirizzi e luoghi, che diventano la destinazione attorno a cui cercare parcheggio. */
    private suspend fun placeWorldwide(q: String, center: LatLon): List<SearchResult> = try {
        val qLower = q.trim().lowercase()
        nominatim("q=${enc(q)}&limit=25").mapNotNull { e ->
            val r = e.jsonObject
            val a = r.obj("address")
            val country = a?.str("country")
            val lat = r.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = r.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            val cls = r.str("class"); val type = r.str("type")
            val displayName = r.str("display_name").orEmpty()
            val isPlace = (cls == "place" && type in placeTypeLabels) || (cls == "boundary" && type == "administrative")
            if (isPlace) {
                val name = displayName.substringBefore(',').trim()
                val label = placeTypeLabels[type] ?: r.str("addresstype")?.let { placeTypeLabels[it] }
                SearchResult(
                    name, listOfNotNull(label?.let { t(it) }?.takeIf { it.isNotEmpty() }, country).joinToString(" · "), lat, lon, true,
                    if (type in setOf("city", "town", "municipality") || cls == "boundary") 15.0 else 16.0,
                    (if (label != null) "$name (${t(label)})" else name) + (country?.let { ", $it" } ?: ""),
                )
            } else {
                val road = a?.str("road")
                val street = if (road != null) road + (a.str("house_number")?.let { " $it" } ?: "") else ""
                val name = r.str("name") ?: street.ifEmpty { displayName.substringBefore(',').trim() }
                val city = a?.str("city") ?: a?.str("town") ?: a?.str("village") ?: a?.str("suburb") ?: a?.str("county")
                val detail = listOfNotNull(if (r.str("name") != null && street.isNotEmpty() && street != name) street else null, city).joinToString(", ")
                SearchResult(name, listOf(detail, country.orEmpty()).filter { it.isNotEmpty() }.joinToString(", "), lat, lon, false, null,
                    name + (if (detail.isNotEmpty()) " · $detail" else "") + (country?.let { ", $it" } ?: ""))
            }
        }
            // Una strada lunga arriva a pezzi con lo stesso nome: se ne tiene uno
            .distinctBy { it.display }
            // Il nome identico alla ricerca prima delle frazioni omonime, poi i più vicini
            .sortedWith(compareBy<SearchResult> { if (it.name.lowercase() == qLower) 0 else 1 }.thenBy { distanceM(center.lat, center.lon, it.lat, it.lon) })
            .take(6)
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun search(q: String, center: LatLon): SearchOutcome {
        if (Regex("^\\d{4,6}$").matches(q)) return postcode(q, center) ?: SearchOutcome.NotFound(t("CAP non trovato su OSM"))
        // Vicino (Postpass) e mondo (Nominatim) in parallelo: si mostra il vicino se c'è, senza attendere due volte
        return coroutineScope {
            val worldwide = async { placeWorldwide(q, center) }
            var results = exactPlaceNode(q, center)
            if (results.isEmpty()) results = worldwide.await() else worldwide.cancel()
            if (results.isEmpty()) SearchOutcome.NotFound(t("Nessun risultato")) else SearchOutcome.Results(results.take(6), t("Seleziona un risultato"))
        }
    }

    /** Via e città di un punto (Nominatim, una richiesta per salvataggio dell'auto). */
    suspend fun reverse(lat: Double, lon: Double): String? = try {
        val text = Http.client.fetchText(
            Http.get("https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&addressdetails=1&lat=$lat&lon=$lon", "Accept-Language" to T.lang),
        )
        val o = withContext(Dispatchers.Default) { json.parseToJsonElement(text).jsonObject }
        val a = o.obj("address")
        // Alcune way hanno nomi tecnici (es. "1_33051"): si scartano quelli senza lettere
        fun named(v: String?) = v?.takeIf { Regex("\\p{L}{3}").containsMatchIn(it) }
        val road = named(a?.str("road")) ?: named(a?.str("pedestrian")) ?: named(a?.str("square")) ?: named(a?.str("footway")) ?: named(o.str("name"))
        val street = if (road != null) road + (a?.str("house_number")?.takeIf { named(a.str("road")) != null }?.let { " $it" } ?: "") else ""
        listOfNotNull(street.takeIf { it.isNotEmpty() }, a?.str("city") ?: a?.str("town") ?: a?.str("village") ?: a?.str("suburb")).joinToString(", ").ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

/** Segnalazioni su OpenStreetMap: note anonime tramite l'API ufficiale, che i mappatori volontari vedono sulla mappa. */
object OsmNotes {
    private const val API = "https://api.openstreetmap.org/api/0.6/notes.json"

    /** Invia la nota; restituisce il suo numero (o null se non indicato). */
    suspend fun send(lat: Double, lon: Double, text: String): Long? {
        val body = FormBody.Builder().add("lat", "%.6f".format(Locale.US, lat)).add("lon", "%.6f".format(Locale.US, lon)).add("text", text).build()
        val resp = Http.client.newCall(Request.Builder().url(API).post(body).build()).await().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }
        return runCatching { Json.parseToJsonElement(resp).jsonObject["properties"]?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLong() }.getOrNull()
    }
}
