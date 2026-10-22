package io.github.andreadp271.parkmap.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Preferenze dell'app (aspetto, mappa, posizione, aggiornamenti), salvate sul dispositivo. */
object AppSettings {
    enum class Theme(val label: String) { SYSTEM("Automatico"), LIGHT("Chiaro"), DARK("Scuro") }
    enum class Palette(val label: String) { BRAND("ParkMap"), DYNAMIC("Dallo sfondo") }
    enum class NavApp(val label: String) { GOOGLE("Google Maps"), WAZE("Waze"), SYSTEM("Predefinita") }

    private val defaultLayers = setOf(Layer.AREAS, Layer.SPACES, Layer.METERS, Layer.CHARGING, Layer.MOTO)

    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(Theme.SYSTEM)
    val theme: StateFlow<Theme> = _theme
    private val _palette = MutableStateFlow(Palette.BRAND)
    val palette: StateFlow<Palette> = _palette
    private val _lang = MutableStateFlow("auto")
    val lang: StateFlow<String> = _lang
    private val _navApp = MutableStateFlow(NavApp.GOOGLE)
    val navApp: StateFlow<NavApp> = _navApp
    private val _mapStyle = MutableStateFlow("osm")
    val mapStyle: StateFlow<String> = _mapStyle
    private val _layers = MutableStateFlow(defaultLayers)
    val layers: StateFlow<Set<Layer>> = _layers
    private val _showAccuracy = MutableStateFlow(true)
    val showAccuracy: StateFlow<Boolean> = _showAccuracy

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        _theme.value = enumOr(prefs.getString("theme", null), Theme.SYSTEM)
        _palette.value = enumOr(prefs.getString("palette", null), Palette.BRAND)
        _lang.value = prefs.getString("lang", "auto") ?: "auto"
        _navApp.value = enumOr(prefs.getString("nav_app", null), NavApp.GOOGLE)
        _mapStyle.value = prefs.getString("map_style", "osm") ?: "osm"
        _showAccuracy.value = prefs.getBoolean("show_accuracy", true)
        _layers.value = prefs.getStringSet("layers", null)
            ?.mapNotNull { n -> runCatching { enumValueOf<Layer>(n) }.getOrNull() }?.toSet() ?: defaultLayers
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E) =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(default)

    fun setTheme(v: Theme) { _theme.value = v; prefs.edit().putString("theme", v.name).apply() }
    fun setPalette(v: Palette) { _palette.value = v; prefs.edit().putString("palette", v.name).apply() }
    fun setLang(v: String) { _lang.value = v; prefs.edit().putString("lang", v).apply() }
    fun setNavApp(v: NavApp) { _navApp.value = v; prefs.edit().putString("nav_app", v.name).apply() }
    fun setMapStyle(v: String) { _mapStyle.value = v; prefs.edit().putString("map_style", v).apply() }
    fun setShowAccuracy(v: Boolean) { _showAccuracy.value = v; prefs.edit().putBoolean("show_accuracy", v).apply() }
    fun setLayer(layer: Layer, on: Boolean) {
        val next = if (on) _layers.value + layer else _layers.value - layer
        _layers.value = next
        prefs.edit().putStringSet("layers", next.map { it.name }.toSet()).apply()
    }

    /** Il setup iniziale (lingua, permessi, zona) è stato completato o saltato. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    /** Centra la mappa sulla posizione all'avvio. */
    var locateOnStart: Boolean
        get() = prefs.getBoolean("locate_on_start", true)
        set(v) = prefs.edit().putBoolean("locate_on_start", v).apply()

    /** Controllo degli aggiornamenti su GitHub all'avvio (al massimo una volta al giorno). */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("auto_update_check", true)
        set(v) = prefs.edit().putBoolean("auto_update_check", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()

    /** Ultima vista della mappa: (lat, lon, zoom) o null al primo avvio. */
    var lastView: Triple<Double, Double, Double>?
        get() = if (prefs.contains("view_lat")) Triple(
            java.lang.Double.longBitsToDouble(prefs.getLong("view_lat", 0)),
            java.lang.Double.longBitsToDouble(prefs.getLong("view_lon", 0)),
            prefs.getFloat("view_zoom", 15f).toDouble(),
        ) else null
        set(v) {
            if (v == null) return
            prefs.edit().putLong("view_lat", java.lang.Double.doubleToLongBits(v.first))
                .putLong("view_lon", java.lang.Double.doubleToLongBits(v.second)).putFloat("view_zoom", v.third.toFloat()).apply()
        }
}

class Favorite(val id: String, val name: String, val summary: String, val lat: Double, val lon: Double, val savedAt: Long, val tags: Map<String, String>)

class RecentSearch(val name: String, val sub: String, val lat: Double, val lon: Double, val place: Boolean, val zoom: Double?)

/** Preferiti e ricerche recenti, solo sul dispositivo. */
object Library {
    private lateinit var prefs: SharedPreferences
    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites
    private val _recents = MutableStateFlow<List<RecentSearch>>(emptyList())
    val recents: StateFlow<List<RecentSearch>> = _recents

    // Solo i tag che servono a colori, titolo e riassunto di un preferito lontano dai dati caricati
    private val keep = listOf("amenity", "parking_space", "fee", "access", "vehicle", "fee:conditional", "parking:condition", "maxstay", "charge")

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
        _favorites.value = runCatching {
            val a = JSONArray(prefs.getString("favorites", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val tg = o.optJSONObject("tags")
                Favorite(
                    o.getString("id"), o.getString("name"), o.optString("summary"), o.getDouble("lat"), o.getDouble("lon"), o.optLong("savedAt"),
                    buildMap { tg?.keys()?.forEach { k -> put(k, tg.getString(k)) } },
                )
            }
        }.getOrDefault(emptyList())
        _recents.value = runCatching {
            val a = JSONArray(prefs.getString("recents", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RecentSearch(o.getString("name"), o.optString("sub"), o.getDouble("lat"), o.getDouble("lon"), o.optBoolean("place"), if (o.has("zoom")) o.getDouble("zoom") else null)
            }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(id: String) = _favorites.value.any { it.id == id }

    /** Aggiunge o toglie dai preferiti; restituisce true se ora è tra i preferiti. */
    fun toggleFavorite(el: OsmElement): Boolean {
        val anchor = el.anchor ?: return false
        return if (isFavorite(el.key)) {
            _favorites.value = _favorites.value.filter { it.id != el.key }
            saveFavorites()
            false
        } else {
            val tags = keep.mapNotNull { k -> el.tags[k]?.let { k to it } }.toMap()
            _favorites.value = _favorites.value + Favorite(el.key, OsmRules.title(el.tags), OsmRules.summary(el.tags), anchor.lat, anchor.lon, System.currentTimeMillis(), tags)
            saveFavorites()
            true
        }
    }

    fun removeFavorite(id: String) {
        _favorites.value = _favorites.value.filter { it.id != id }
        saveFavorites()
    }

    private fun saveFavorites() {
        val a = JSONArray()
        _favorites.value.forEach { f ->
            a.put(
                JSONObject().put("id", f.id).put("name", f.name).put("summary", f.summary).put("lat", f.lat).put("lon", f.lon)
                    .put("savedAt", f.savedAt).put("tags", JSONObject(f.tags)),
            )
        }
        prefs.edit().putString("favorites", a.toString()).apply()
    }

    fun rememberSearch(r: RecentSearch) {
        _recents.value = (listOf(r) + _recents.value.filter { abs(it.lat - r.lat) > 1e-4 || abs(it.lon - r.lon) > 1e-4 }).take(6)
        saveRecents()
    }

    fun clearRecents() {
        _recents.value = emptyList()
        saveRecents()
    }

    private fun saveRecents() {
        val a = JSONArray()
        _recents.value.forEach { r ->
            a.put(JSONObject().put("name", r.name).put("sub", r.sub).put("lat", r.lat).put("lon", r.lon).put("place", r.place).apply { r.zoom?.let { put("zoom", it) } })
        }
        prefs.edit().putString("recents", a.toString()).apply()
    }
}
