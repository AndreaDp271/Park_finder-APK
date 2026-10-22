package io.github.andreadp271.parkmap.ui

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

/** Sorgente di tessere da un modello di URL con {z} {x} {y} e, facoltativamente, {s} (sottodomini a, b, c). */
class UrlTileSource(name: String, maxZoom: Int, private val template: String, private val subdomains: List<String>, attribution: String) :
    OnlineTileSourceBase(name, 0, maxZoom, 256, ".png", arrayOf(template), attribution) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val x = MapTileIndex.getX(pMapTileIndex); val y = MapTileIndex.getY(pMapTileIndex); val z = MapTileIndex.getZoom(pMapTileIndex)
        val s = if (subdomains.isEmpty()) "" else subdomains[(x + y).mod(subdomains.size)]
        return template.replace("{s}", s).replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
    }
}

/** Stile della mappa: una sorgente di tessere, un filtro colore e, per l'ibrida, i nomi sopra il satellite. */
class MapStyle(
    val id: String, val label: String, val attribution: String,
    val source: () -> ITileSource,
    val filter: ColorMatrixColorFilter? = null,
    val labels: (() -> ITileSource)? = null,
)

object MapStyles {
    private const val OSM = "© OpenStreetMap"
    private val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    // Notte: colori invertiti con un po' di blu, come lo stile scuro della web app
    private val night = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -0.9f, 0f, 0f, 0f, 235f,
                0f, -0.9f, 0f, 0f, 238f,
                0f, 0f, -0.85f, 0f, 245f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    private val esriImagery = { UrlTileSource("EsriImagery", 19, "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", emptyList(), "Esri, Maxar, Earthstar Geographics") }

    val all: List<MapStyle> = listOf(
        MapStyle("osm", "Standard", OSM, { TileSourceFactory.MAPNIK }),
        MapStyle("light", "Umanitario", "$OSM, HOT", {
            UrlTileSource("HOT", 19, "https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png", listOf("a", "b", "c"), "© OpenStreetMap, Humanitarian OSM Team")
        }),
        MapStyle("cycle", "Ciclabile", "$OSM, CyclOSM", {
            UrlTileSource("CyclOSM", 19, "https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png", listOf("a", "b", "c"), "© OpenStreetMap, CyclOSM")
        }),
        MapStyle("topo", "Rilievo", "Esri, HERE, Garmin, USGS | $OSM", {
            UrlTileSource("EsriTopo", 19, "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}", emptyList(), "Esri, HERE, Garmin, USGS")
        }),
        MapStyle("gray", "Grigio", OSM, { TileSourceFactory.MAPNIK }, grayscale),
        MapStyle("dark", "Notte", OSM, { TileSourceFactory.MAPNIK }, night),
        MapStyle("satellite", "Satellite", "Esri, Maxar, Earthstar Geographics | $OSM", esriImagery),
        MapStyle("hybrid", "Ibrida", "Esri, Maxar, Earthstar Geographics | $OSM", esriImagery, labels = {
            UrlTileSource("EsriLabels", 19, "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}", emptyList(), "Esri")
        }),
    )

    fun byId(id: String): MapStyle = all.firstOrNull { it.id == id } ?: all[0]

    /** Applica lo stile alla mappa; [labelsOverlay] è il livello dei nomi dell'ibrida da una chiamata alla successiva. */
    fun apply(ctx: Context, map: MapView, style: MapStyle, labelsOverlay: TilesOverlay?): TilesOverlay? {
        if (labelsOverlay != null) map.overlays.remove(labelsOverlay)
        map.setTileSource(style.source())
        map.overlayManager.tilesOverlay.setColorFilter(style.filter)
        map.setBackgroundColor(if (style.id == "dark") Color.rgb(20, 24, 31) else Color.rgb(228, 232, 240))
        var added: TilesOverlay? = null
        style.labels?.let { src ->
            val provider = MapTileProviderBasic(ctx.applicationContext, src())
            added = TilesOverlay(provider, ctx).apply {
                loadingBackgroundColor = Color.TRANSPARENT
                loadingLineColor = Color.TRANSPARENT
            }
            map.overlays.add(0, added)
        }
        map.invalidate()
        return added
    }
}
