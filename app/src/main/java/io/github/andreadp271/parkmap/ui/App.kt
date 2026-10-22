package io.github.andreadp271.parkmap.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.andreadp271.parkmap.data.AppAction
import io.github.andreadp271.parkmap.data.CarStore
import io.github.andreadp271.parkmap.data.Fmt
import io.github.andreadp271.parkmap.data.ParkingRepo
import io.github.andreadp271.parkmap.data.t
import kotlinx.coroutines.delay

/** Azioni di navigazione usate dalle schermate. */
class Nav(val c: NavHostController) {
    fun back() { if (!c.popBackStack()) tab("mappa") }
    fun go(route: String) { c.navigate(route) { launchSingleTop = true } }

    /** Cambio di scheda: ogni scheda conserva la propria pila, tranne la mappa (iniziale) che torna sempre alla radice. */
    fun tab(route: String) {
        val start = c.graph.findStartDestination()
        if (route == start.route) { c.popBackStack(start.id, inclusive = false); return }
        c.navigate(route) {
            popUpTo(start.id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private val topLevel = setOf("mappa", "vicini", "auto", "menu")

/** Scheda "naturale" di una schermata secondaria. */
private fun tabOf(route: String): String = if (route in topLevel) route else "menu"

@Composable
fun App() {
    val ctx = LocalContext.current
    val controller = rememberNavController()
    val nav = Nav(controller)
    val entry by controller.currentBackStackEntryAsState()
    var current by rememberSaveable { mutableStateOf("mappa") }
    LaunchedEffect(entry) {
        val r = entry?.destination?.route ?: return@LaunchedEffect
        current = if (r in topLevel) r else when (r) { "segnala" -> "mappa"; else -> "menu" }
    }
    val car by CarStore.car.collectAsState()
    val update by UiState.update.collectAsState()
    val now = androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }.value

    // Azioni da widget, scorciatoie e notifiche
    val action by UiState.pendingAction.collectAsState()
    LaunchedEffect(action) {
        val a = action ?: return@LaunchedEffect
        UiState.pendingAction.value = null
        when (a) {
            AppAction.SAVE_CAR -> { UiState.pendingSaveCar.value = true; nav.tab("auto") }
            AppAction.SHOW_CAR, "openCar" -> {
                CarStore.car.value?.let { UiState.focus.value = Focus(it.lat, it.lon, 17.0) }
                nav.tab("auto")
            }
            "openList" -> { UiState.listTab.value = 0; nav.tab("vicini") }
            AppAction.NEARBY -> {
                UiState.destination.value = null
                if (LocationTracker.hasPermission(ctx)) LocationTracker.start(ctx)
                UiState.fix.value?.let { UiState.focus.value = Focus(it.lat, it.lon, 16.0) }
                nav.tab("mappa")
                delay(800)
                var waited = 0
                while (!ParkingRepo.isReady() && waited < 10_000) { delay(300); waited += 300 }
                UiState.listTab.value = 0; nav.tab("vicini")
            }
        }
    }

    // Etichetta della scheda Auto: tempo residuo o "Scaduta"
    val carLabel = run {
        val e = car?.expiry
        when {
            e == null -> t("Auto")
            e - now <= 0 -> t("Scaduta")
            else -> Fmt.duration(e - now)
        }
    }
    val carWarn = car?.expiry?.let { it - now <= CarStore.WARNING_MS } == true

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                data class T(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                listOf(
                    T("mappa", t("Mappa"), Icons.Filled.Map),
                    T("vicini", t("Vicini"), Icons.AutoMirrored.Filled.FormatListBulleted),
                    T("auto", carLabel, Icons.Filled.DirectionsCar),
                    T("menu", t("Menu"), Icons.Filled.MoreHoriz),
                ).forEach { item ->
                    NavigationBarItem(
                        selected = current == item.route,
                        onClick = {
                            // di nuovo sulla scheda attiva: torna alla sua schermata principale
                            if (current == item.route && item.route != "mappa") controller.popBackStack(item.route, false) else nav.tab(item.route)
                            current = item.route
                        },
                        icon = {
                            val showBadge = (item.route == "menu" && update != null) || (item.route == "auto" && carWarn)
                            if (showBadge) BadgedBox(badge = { Badge() }) { Icon(item.icon, null) } else Icon(item.icon, null)
                        },
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            controller, startDestination = "mappa", modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner),
            // dissolvenza breve: quella predefinita rende lenti i cambi di schermata
            enterTransition = { fadeIn(tween(150)) }, exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(tween(150)) }, popExitTransition = { fadeOut(tween(100)) },
        ) {
            composable("mappa") { MapScreen(nav) }
            composable("vicini") { ListScreen(nav) }
            composable("auto") { CarScreen(nav) }
            composable("menu") { MenuScreen(nav) }
            composable("impostazioni") { SettingsScreen(nav) }
            composable("provider") { ProvidersScreen(nav) }
            composable("segnala") { ReportScreen(nav) }
            composable("aggiornamenti") { UpdateScreen(nav) }
        }
    }
}
