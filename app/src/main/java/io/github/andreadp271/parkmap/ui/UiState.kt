package io.github.andreadp271.parkmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.andreadp271.parkmap.data.Filter
import io.github.andreadp271.parkmap.data.LatLon
import io.github.andreadp271.parkmap.data.OsmElement
import kotlinx.coroutines.flow.MutableStateFlow

/** Posizione dell'utente: coordinate, precisione in metri e ora del rilevamento (vera, anche per l'ultima posizione nota). */
class Fix(val lat: Double, val lon: Double, val acc: Float, val ts: Long) {
    val latLon get() = LatLon(lat, lon)
    /** Rilevamento di più di un minuto fa (ultima posizione nota): va bene per centrare subito, poi si aspetta quello nuovo. */
    val stale get() = System.currentTimeMillis() - ts > 60_000
}

/** Destinazione cercata (o punto scelto): i parcheggi si ordinano per distanza a piedi da qui. */
class Destination(val lat: Double, val lon: Double, val name: String)

/** Richiesta di spostare la mappa; con [openKey] apre anche la scheda dell'elemento appena è caricato. */
class Focus(val lat: Double, val lon: Double, val zoom: Double? = null, val openKey: String? = null)

/** Contesto di una segnalazione su OpenStreetMap. */
class ReportContext(val lat: Double, val lon: Double, val osmRef: String?, val title: String?, val type: String, val ztl: Boolean = false)

/** Stato condiviso tra le schermate (mappa, elenco, auto, menu). */
object UiState {
    val filters = MutableStateFlow<Set<Filter>>(emptySet())
    val destination = MutableStateFlow<Destination?>(null)
    val fix = MutableStateFlow<Fix?>(null)
    val focus = MutableStateFlow<Focus?>(null)
    val selected = MutableStateFlow<OsmElement?>(null)
    /** Messaggio breve sopra la barra in basso. */
    val status = MutableStateFlow<String?>(null)
    val report = MutableStateFlow<ReportContext?>(null)
    val listTab = MutableStateFlow(0)
    /** Centro e zoom della mappa, aggiornati a ogni spostamento. */
    val mapCenter = MutableStateFlow<LatLon?>(null)
    val mapZoom = MutableStateFlow(15.0)
    /** Azione chiesta da widget, scorciatoie o notifiche, da eseguire quando l'interfaccia è pronta. */
    val pendingAction = MutableStateFlow<String?>(null)
    /** Richiesta di salvare l'auto dalla posizione GPS (da widget/scorciatoia). */
    val pendingSaveCar = MutableStateFlow(false)
    /** Link ?car=lat,lon ricevuto da un'altra persona. */
    val sharedCar = MutableStateFlow<Triple<Double, Double, String>?>(null)
    /** Ultima release trovata su GitHub in questa sessione (avviso in Menu e Impostazioni). */
    val update = MutableStateFlow<io.github.andreadp271.parkmap.data.Updater.Release?>(null)

    fun say(message: String) { status.value = message }
}

/** Posizione senza la libreria dei servizi Google: LocationManager (fused, GPS e rete), rilevamenti filtrati come nella web app. */
object LocationTracker {
    private var manager: LocationManager? = null
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = accept(loc)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun accept(loc: Location) {
        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        // Ora vera del rilevamento: l'ultima posizione nota può essere di qualche minuto fa
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        val ts = System.currentTimeMillis() - ageMs.coerceAtLeast(0)
        val last = UiState.fix.value
        if (last != null && ts < last.ts) return
        // Un fix approssimativo arrivato dopo uno più preciso non deve peggiorare il pallino
        if (last != null && acc > last.acc * 2 && ts - last.ts < 15_000) return
        UiState.fix.value = Fix(loc.latitude, loc.longitude, acc, ts)
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (running || !hasPermission(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm
        running = true
        val providers = buildList {
            // fused (Android 12+): Wi-Fi, celle e GPS insieme, la prima posizione arriva in un paio di secondi anche al chiuso
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
        }
        // Ultima posizione nota (fino a 30 minuti fa) per centrare subito la mappa: la più recente tra i provider
        providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 30 * 60_000_000_000L }
            .sortedBy { it.elapsedRealtimeNanos }
            .forEach { accept(it) }
        // A tempo (ogni 2 s) e non a distanza: anche da fermi la posizione resta fresca, così "La mia posizione" centra subito
        providers.forEach { p ->
            runCatching { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2000L, 0f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeUpdates(listener) }
    }
}
