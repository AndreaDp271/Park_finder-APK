package io.github.andreadp271.parkmap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import io.github.andreadp271.parkmap.R
import io.github.andreadp271.parkmap.data.Kind
import io.github.andreadp271.parkmap.data.LatLon
import io.github.andreadp271.parkmap.data.OsmElement
import io.github.andreadp271.parkmap.data.OsmRules
import io.github.andreadp271.parkmap.data.distanceM
import io.github.andreadp271.parkmap.data.distanceToSegmentM
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/** Metri per pixel a una latitudine e a uno zoom (proiezione Web Mercator, tessere da 256 px). */
fun metersPerPixel(lat: Double, zoom: Double): Double = 156543.03392 * cos(lat * PI / 180) / 2.0.pow(zoom)

/**
 * Tutti i parcheggi in un unico livello disegnato a mano: con migliaia di elementi è molto più leggero di un
 * overlay per elemento. Gestisce anche il tocco: sceglie l'elemento sotto il dito (marker e colonnine sopra le aree).
 */
class ParkingOverlay(private val ctx: Context) : Overlay() {
    enum class Shape { POLYGON, LINE, DOT, EV }

    class Item(
        val el: OsmElement, val z: Int, val shape: Shape, val fill: Int, val stroke: Int,
        val pts: DoubleArray?, val lat: Double, val lon: Double,
        val south: Double, val west: Double, val north: Double, val east: Double,
        val radiusDp: Float, val strokeDp: Float, val fillAlpha: Int,
    )

    private val density = ctx.resources.displayMetrics.density
    @Volatile private var items: List<Item> = emptyList()
    var selectedKey: String? = null
    var onTap: ((OsmElement) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF111827.toInt() }
    private val path = Path()
    private val pt = Point()
    private val geo = GeoPoint(0.0, 0.0)
    private val bolt = ContextCompat.getDrawable(ctx, R.drawable.ic_bolt)

    /** Prepara le forme degli elementi da mostrare (da chiamare fuori dal thread principale). */
    fun prepare(elements: List<OsmElement>): List<Item> {
        val out = ArrayList<Item>(elements.size)
        elements.forEach { el -> build(el)?.let { out += it } }
        out.sortBy { it.z }
        return out
    }

    fun setPrepared(list: List<Item>) { items = list }

    private fun build(el: OsmElement): Item? {
        val tags = el.tags
        val kind = el.kind
        val (fill, stroke) = OsmRules.mapColors(tags)
        val g = el.g
        val isSpaceStyle = kind == Kind.SPACE || (kind == Kind.MOTO && tags["amenity"] == "parking_space")
        val z = when {
            kind == Kind.PARKING -> 410
            kind == Kind.MOTO || kind == Kind.BIKE -> if (isSpaceStyle) 420 else 415
            kind == Kind.SPACE -> 420
            kind == Kind.METER -> 430
            kind == Kind.CHARGING -> 440
            else -> return null
        }
        if (kind == Kind.CHARGING) {
            val c = el.center ?: return null
            return Item(el, z, Shape.EV, 0xFF059669.toInt(), 0xFFFFFFFF.toInt(), null, c.lat, c.lon, c.lat, c.lon, c.lat, c.lon, 10f, 2f, 255)
        }
        if (g != null && g.size >= 4) {
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            for (i in 0 until g.size / 2) {
                val la = g[2 * i]; val lo = g[2 * i + 1]
                if (la < s) s = la; if (la > n) n = la; if (lo < w) w = lo; if (lo > e) e = lo
            }
            val closed = el.isClosed
            return when (kind) {
                Kind.PARKING -> Item(el, z, if (closed) Shape.POLYGON else Shape.LINE, fill, stroke, g, g[0], g[1], s, w, n, e, 0f, if (closed) 2f else 3f, 102)
                Kind.SPACE -> Item(el, z, Shape.POLYGON, fill, stroke, g, g[0], g[1], s, w, n, e, 0f, 2f, 204)
                Kind.MOTO, Kind.BIKE ->
                    if (isSpaceStyle) Item(el, z, Shape.POLYGON, fill, stroke, g, g[0], g[1], s, w, n, e, 0f, 2f, 204)
                    else if (g.size / 2 > 2 && closed) Item(el, z, Shape.POLYGON, fill, stroke, g, g[0], g[1], s, w, n, e, 0f, 2f, 153)
                    else Item(el, z, Shape.LINE, fill, stroke, g, g[0], g[1], s, w, n, e, 0f, 4f, 255)
                else -> null // i parcometri sono sempre puntuali
            }
        }
        val c = el.anchor ?: return null
        val radius = when (kind) { Kind.PARKING -> 7f; Kind.SPACE -> 8f; Kind.METER -> 5f; Kind.MOTO -> if (isSpaceStyle) 8f else 5f; Kind.BIKE -> 5f; else -> 5f }
        val alpha = when (kind) { Kind.PARKING -> 178; Kind.SPACE -> 230; else -> 242 }
        return Item(el, z, Shape.DOT, fill, stroke, null, c.lat, c.lon, c.lat, c.lon, c.lat, c.lon, radius, 2f, alpha)
    }

    override fun draw(c: Canvas, pj: Projection) {
        val list = items
        if (list.isEmpty()) return
        val bb = pj.boundingBox
        val padLat = (bb.latNorth - bb.latSouth) * 0.1; val padLon = (bb.lonEast - bb.lonWest) * 0.1
        val n = bb.latNorth + padLat; val s = bb.latSouth - padLat; val e = bb.lonEast + padLon; val w = bb.lonWest - padLon
        for (it in list) {
            if (it.south > n || it.north < s || it.west > e || it.east < w) continue
            val selected = it.el.key == selectedKey
            when (it.shape) {
                Shape.POLYGON, Shape.LINE -> {
                    val g = it.pts!!
                    path.reset()
                    for (i in 0 until g.size / 2) {
                        geo.setCoords(g[2 * i], g[2 * i + 1])
                        pj.toPixels(geo, pt)
                        if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                    }
                    if (it.shape == Shape.POLYGON) {
                        path.close()
                        fillPaint.color = it.fill; fillPaint.alpha = it.fillAlpha
                        c.drawPath(path, fillPaint)
                    }
                    strokePaint.color = it.stroke; strokePaint.alpha = 255
                    strokePaint.strokeWidth = it.strokeDp * density
                    c.drawPath(path, strokePaint)
                    if (selected) { selectPaint.strokeWidth = (it.strokeDp + 2.5f) * density; c.drawPath(path, selectPaint); strokePaint.strokeWidth = it.strokeDp * density; c.drawPath(path, strokePaint) }
                }
                Shape.DOT -> {
                    geo.setCoords(it.lat, it.lon)
                    pj.toPixels(geo, pt)
                    val r = it.radiusDp * density
                    fillPaint.color = it.fill; fillPaint.alpha = it.fillAlpha
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fillPaint)
                    strokePaint.color = it.stroke; strokePaint.alpha = 255; strokePaint.strokeWidth = it.strokeDp * density
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, strokePaint)
                    if (selected) { selectPaint.strokeWidth = 2.5f * density; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 4 * density, selectPaint) }
                }
                Shape.EV -> {
                    geo.setCoords(it.lat, it.lon)
                    pj.toPixels(geo, pt)
                    val r = it.radiusDp * density
                    fillPaint.color = it.fill; fillPaint.alpha = 255
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fillPaint)
                    strokePaint.color = it.stroke; strokePaint.alpha = 255; strokePaint.strokeWidth = 2f * density
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, strokePaint)
                    bolt?.let { d ->
                        val h = (r * 0.78f).toInt()
                        d.setBounds(pt.x - h, pt.y - h, pt.x + h, pt.y + h)
                        d.draw(c)
                    }
                    if (selected) { selectPaint.strokeWidth = 2.5f * density; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 4 * density, selectPaint) }
                }
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val list = items
        if (list.isEmpty()) return false
        val p = map.projection.fromPixels(e.x.toInt(), e.y.toInt())
        val tap = LatLon(p.latitude, p.longitude)
        val mpp = metersPerPixel(tap.lat, map.zoomLevelDouble)
        val tolM = 22 * density * mpp
        var best: Item? = null
        var bestD = Double.MAX_VALUE
        for (it in list) {
            // fuori dalla tolleranza in latitudine/longitudine: scarto rapido
            val degTol = tolM / 111_000.0 * 1.5
            if (tap.lat < it.south - degTol || tap.lat > it.north + degTol || tap.lon < it.west - degTol * 2 || tap.lon > it.east + degTol * 2) continue
            val d = distanceTo(it, tap, mpp)
            if (d > tolM) continue
            // chi è colpito davvero (dentro l'area o sul segno) batte chi è solo vicino; poi vince la z maggiore, poi il più vicino
            val better = best == null ||
                (d == 0.0) != (bestD == 0.0) && d == 0.0 ||
                (d == 0.0) == (bestD == 0.0) && (it.z > best.z || (it.z == best.z && d < bestD))
            if (better) { best = it; bestD = d }
        }
        val hit = best ?: return false
        onTap?.invoke(hit.el)
        return true
    }

    private fun distanceTo(it: Item, tap: LatLon, mpp: Double): Double = when (it.shape) {
        Shape.DOT, Shape.EV -> max(0.0, distanceM(tap.lat, tap.lon, it.lat, it.lon) - it.radiusDp * density * mpp)
        Shape.LINE -> minEdge(it.pts!!, tap, false)
        Shape.POLYGON -> if (inside(it.pts!!, tap)) 0.0 else minEdge(it.pts, tap, true)
    }

    private fun minEdge(g: DoubleArray, tap: LatLon, close: Boolean): Double {
        var best = Double.MAX_VALUE
        val n = g.size / 2
        for (i in 0 until n - 1) best = minOf(best, distanceToSegmentM(tap, LatLon(g[2 * i], g[2 * i + 1]), LatLon(g[2 * i + 2], g[2 * i + 3])))
        if (close && n > 2) best = minOf(best, distanceToSegmentM(tap, LatLon(g[2 * n - 2], g[2 * n - 1]), LatLon(g[0], g[1])))
        return best
    }

    /** Punto dentro il poligono (regola pari-dispari). */
    private fun inside(g: DoubleArray, p: LatLon): Boolean {
        var c = false
        val n = g.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = g[2 * i]; val xi = g[2 * i + 1]; val yj = g[2 * j]; val xj = g[2 * j + 1]
            if ((yi > p.lat) != (yj > p.lat) && p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
}

/** Posizione dell'utente, auto parcheggiata (con linea tratteggiata) e destinazione cercata. */
class MarkersOverlay(private val ctx: Context) : Overlay() {
    private val density = ctx.resources.displayMetrics.density
    var user: Pair<GeoPoint, Float>? = null
    var showAccuracy = true
    var car: GeoPoint? = null
    var sharedCar: GeoPoint? = null
    var dest: GeoPoint? = null
    var onCarTap: (() -> Unit)? = null
    var onSharedCarTap: (() -> Unit)? = null
    var onDestTap: (() -> Unit)? = null

    private val carIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_car)
    private val flagIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_flag)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = Point()
    private val pt2 = Point()
    private val path = Path()

    override fun draw(c: Canvas, pj: Projection) {
        val u = user
        val carPt = car
        if (u != null && carPt != null && distanceM(u.first.latitude, u.first.longitude, carPt.latitude, carPt.longitude) < 50_000) {
            pj.toPixels(u.first, pt); pj.toPixels(carPt, pt2)
            paint.style = Paint.Style.STROKE; paint.color = 0x991D4ED8.toInt(); paint.strokeWidth = 3 * density; paint.strokeCap = Paint.Cap.ROUND
            paint.pathEffect = DashPathEffect(floatArrayOf(2 * density, 8 * density), 0f)
            path.reset(); path.moveTo(pt.x.toFloat(), pt.y.toFloat()); path.lineTo(pt2.x.toFloat(), pt2.y.toFloat())
            c.drawPath(path, paint)
            paint.pathEffect = null
        }
        dest?.let { d ->
            pj.toPixels(d, pt)
            // goccia rossa con bandierina bianca, la punta sul luogo cercato
            val r = 15 * density
            val cx = pt.x.toFloat(); val cy = pt.y - r * 2.1f
            paint.style = Paint.Style.FILL; paint.color = 0xFFBE123C.toInt()
            path.reset(); path.moveTo(cx - r * 0.8f, cy + r * 0.6f); path.lineTo(cx, pt.y.toFloat()); path.lineTo(cx + r * 0.8f, cy + r * 0.6f); path.close()
            c.drawPath(path, paint)
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE; paint.color = 0xFFFFFFFF.toInt(); paint.strokeWidth = 2.5f * density
            c.drawCircle(cx, cy, r, paint)
            flagIcon?.let { it.setBounds((cx - r * 0.6f).toInt(), (cy - r * 0.6f).toInt(), (cx + r * 0.6f).toInt(), (cy + r * 0.6f).toInt()); it.draw(c) }
        }
        u?.let { (p, acc) ->
            pj.toPixels(p, pt)
            if (showAccuracy && acc > 0) {
                val mppx = metersPerPixel(p.latitude, pj.zoomLevel)
                val rr = (acc / mppx).toFloat()
                paint.style = Paint.Style.FILL; paint.color = 0x1F3B82F6
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rr, paint)
                paint.style = Paint.Style.STROKE; paint.color = 0x592563EB; paint.strokeWidth = density
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rr, paint)
            }
            paint.style = Paint.Style.FILL; paint.color = 0x332563EB
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 14 * density, paint)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8.5f * density, paint)
            paint.color = 0xFF2563EB.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6 * density, paint)
        }
        sharedCar?.let { drawCar(c, pj, it, 0xFF0E8A8A.toInt()) }
        carPt?.let { drawCar(c, pj, it, 0xFF1D4ED8.toInt()) }
    }

    private fun drawCar(c: Canvas, pj: Projection, p: GeoPoint, color: Int) {
        pj.toPixels(p, pt)
        val r = 18 * density
        paint.style = Paint.Style.FILL; paint.color = color
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
        paint.style = Paint.Style.STROKE; paint.color = 0xFFFFFFFF.toInt(); paint.strokeWidth = 3 * density
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
        carIcon?.let { val h = (r * 0.62f).toInt(); it.setBounds(pt.x - h, pt.y - h, pt.x + h, pt.y + h); it.draw(c) }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val pj = map.projection
        val reach = 26 * density
        fun near(p: GeoPoint?, dy: Float = 0f): Boolean {
            p ?: return false
            pj.toPixels(p, pt)
            val dx = e.x - pt.x; val ddy = e.y - (pt.y + dy)
            return dx * dx + ddy * ddy <= reach * reach
        }
        if (near(car)) { onCarTap?.invoke(); return true }
        if (near(sharedCar)) { onSharedCarTap?.invoke(); return true }
        if (near(dest, -30 * density)) { onDestTap?.invoke(); return true }
        return false
    }
}
