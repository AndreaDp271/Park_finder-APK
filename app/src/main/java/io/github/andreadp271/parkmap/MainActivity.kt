package io.github.andreadp271.parkmap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import io.github.andreadp271.parkmap.data.AppAction
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.CarReminders
import io.github.andreadp271.parkmap.data.CarStore
import io.github.andreadp271.parkmap.data.Http
import io.github.andreadp271.parkmap.data.Library
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.T
import io.github.andreadp271.parkmap.data.Updater
import io.github.andreadp271.parkmap.ui.App
import io.github.andreadp271.parkmap.ui.Focus
import io.github.andreadp271.parkmap.ui.LocationTracker
import io.github.andreadp271.parkmap.ui.MapHolder
import io.github.andreadp271.parkmap.ui.ParkMapTheme
import io.github.andreadp271.parkmap.ui.UiState
import io.github.andreadp271.parkmap.ui.isDark
import io.github.andreadp271.parkmap.widget.ParkMapWidget
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(AppAction.EXTRA)?.let { UiState.pendingAction.value = it; intent.removeExtra(AppAction.EXTRA) }
        val data: Uri = intent.data ?: return
        when (data.scheme) {
            // Link condivisi dalla web app: ?lat=..&lon=..&z=.. oppure ?car=lat,lon&note=..
            "https" -> {
                val car = data.getQueryParameter("car")?.split(',')?.mapNotNull { it.toDoubleOrNull() }
                val lat = data.getQueryParameter("lat")?.toDoubleOrNull(); val lon = data.getQueryParameter("lon")?.toDoubleOrNull()
                if (car != null && car.size == 2) {
                    UiState.sharedCar.value = Triple(car[0], car[1], data.getQueryParameter("note").orEmpty().take(200))
                    UiState.focus.value = Focus(car[0], car[1], 18.0)
                } else if (lat != null && lon != null) {
                    UiState.focus.value = Focus(lat, lon, data.getQueryParameter("z")?.toDoubleOrNull() ?: 17.0)
                }
            }
            "geo" -> {
                val ll = data.schemeSpecificPart.substringBefore('?').split(',').mapNotNull { it.toDoubleOrNull() }
                if (ll.size == 2 && (ll[0] != 0.0 || ll[1] != 0.0)) UiState.focus.value = Focus(ll[0], ll[1], 17.0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        T.init(this, AppSettings.lang.value)
        Http.init(this)
        Library.init(this)
        CarStore.init(this)
        ParkingRepo.init(this)
        CarReminders.createChannel(this)
        // osmdroid: User-Agent identificativo (richiesto dalla tile policy OSM) e cache nello spazio privato dell'app
        Configuration.getInstance().apply {
            userAgentValue = Http.userAgent
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            // icone della barra di stato chiare o scure secondo il tema scelto
            val dark = isDark()
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            ParkMapTheme { App() }
        }
    }

    override fun onStart() {
        super.onStart()
        // il pallino della posizione segue solo finché l'app è visibile
        if (MapHolder.started) LocationTracker.start(this)
        // nuove versioni su GitHub: al massimo una volta al giorno (non se è installata da un negozio di app)
        val now = System.currentTimeMillis()
        if (AppSettings.autoUpdateCheck && now - AppSettings.lastUpdateCheck > 24 * 3600_000L && !Updater.fromStore(this)) {
            lifecycleScope.launch {
                runCatching { Updater.latest() }.getOrNull()?.let { r ->
                    AppSettings.lastUpdateCheck = System.currentTimeMillis()
                    if (Updater.isNewer(r.version)) UiState.update.value = r
                }
            }
        }
        ParkMapWidget.updateAll(this)
    }

    override fun onStop() {
        super.onStop()
        LocationTracker.stop()
    }

    override fun onDestroy() {
        // la mappa è conservata tra le schede: va rilasciata con l'Activity che la ospita
        MapHolder.release()
        super.onDestroy()
    }
}
