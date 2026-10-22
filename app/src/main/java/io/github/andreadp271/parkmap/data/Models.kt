package io.github.andreadp271.parkmap.data

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Che cos'è un elemento OSM, per colori, filtri, scheda ed elenco. */
enum class Kind { PARKING, SPACE, METER, CHARGING, MOTO, BIKE, OTHER }

/** Filtri della barra sotto la ricerca: un elemento è visibile se soddisfa almeno uno dei filtri attivi. */
enum class Filter(val label: String) {
    FREE_NOW("Gratis adesso"), FREE("Gratuiti"), DISC("Disco orario"), DISABLED("Disabili"),
    EV("Ricarica EV"), MOTO("Moto"), BIKE("Bici")
}

/** Livelli visibili (Livelli → "Livelli visibili"). */
enum class Layer(val label: String) {
    AREAS("Aree di sosta"), SPACES("Stalli speciali"), METERS("Parcometri"),
    CHARGING("Colonnine di ricarica"), MOTO("Parcheggi moto"), BIKES("Parcheggi bici")
}

/** Elemento OSM (nodo o way) con i soli tag usati dall'app. [g] = lat, lon, lat, lon... */
@Serializable
class OsmElement(
    val type: String,
    val id: Long,
    val tags: Map<String, String>,
    val lat: Double? = null,
    val lon: Double? = null,
    val g: DoubleArray? = null,
) {
    val key: String get() = "$type:$id"
    val kind: Kind get() = kindOf(tags)

    /** Centro: il nodo stesso o la media dei punti della way. */
    val center: LatLon? by lazy(LazyThreadSafetyMode.NONE) {
        if (lat != null && lon != null) LatLon(lat, lon)
        else g?.takeIf { it.size >= 2 }?.let { pts ->
            var a = 0.0; var o = 0.0
            val n = pts.size / 2
            for (i in 0 until n) { a += pts[2 * i]; o += pts[2 * i + 1] }
            LatLon(a / n, o / n)
        }
    }

    /** Punto usato per navigare e per i preferiti: primo punto della geometria o il nodo. */
    val anchor: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else g?.takeIf { it.size >= 2 }?.let { LatLon(it[0], it[1]) }

    val isClosed: Boolean get() = g != null && g.size >= 8 && g[0] == g[g.size - 2] && g[1] == g[g.size - 1]
}

fun kindOf(tags: Map<String, String>): Kind = when (tags["amenity"]) {
    "parking" -> Kind.PARKING
    // Lo stallo moto è un parcheggio moto come gli altri (livello, filtro, legenda)
    "parking_space" -> if (tags["parking_space"] == "motorcycle") Kind.MOTO else Kind.SPACE
    "vending_machine" -> Kind.METER
    "charging_station" -> Kind.CHARGING
    "motorcycle_parking" -> Kind.MOTO
    "bicycle_parking" -> Kind.BIKE
    else -> Kind.OTHER
}

data class LatLon(val lat: Double, val lon: Double)

/** Distanza in metri tra due punti (haversine). */
fun distanceM(a: LatLon, b: LatLon): Double = distanceM(a.lat, a.lon, b.lat, b.lon)

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371008.8
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Distanza da un punto a un segmento, in metri (proiezione piana locale: basta per i tocchi sulla mappa). */
fun distanceToSegmentM(p: LatLon, a: LatLon, b: LatLon): Double {
    val k = cos(Math.toRadians(p.lat))
    val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
    val bx = (b.lon - p.lon) * k; val by = b.lat - p.lat
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val tt = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + tt * dx; val cy = ay + tt * dy
    return sqrt(cx * cx + cy * cy) * 111_320.0
}

fun nearlyEqual(a: Double, b: Double) = abs(a - b) < 1e-4
