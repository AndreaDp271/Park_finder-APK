package io.github.andreadp271.parkmap.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.andreadp271.parkmap.data.AppSettings

/** Blu ParkMap (lo stesso della web app e dell'icona) con neutri leggermente freddi. */
private val Light = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFF4F6FA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEDF0F6),
    surfaceContainerHigh = Color(0xFFE6EAF2),
    surfaceContainerHighest = Color(0xFFDEE3EC),
    onSurface = Color(0xFF1B1F27),
    onSurfaceVariant = Color(0xFF566074),
    outline = Color(0xFF8590A3),
    outlineVariant = Color(0xFFD6DBE5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FB3FF),
    onPrimary = Color(0xFF0B2A6B),
    primaryContainer = Color(0xFF1D3E91),
    onPrimaryContainer = Color(0xFFDBE7FF),
    secondary = Color(0xFFC3CBD9),
    secondaryContainer = Color(0xFF2C3340),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background = Color(0xFF101318),
    surface = Color(0xFF101318),
    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF171A20),
    surfaceContainer = Color(0xFF1C2027),
    surfaceContainerHigh = Color(0xFF252A32),
    surfaceContainerHighest = Color(0xFF2F353F),
    onSurface = Color(0xFFE4E7EE),
    onSurfaceVariant = Color(0xFFAEB6C4),
    outline = Color(0xFF7C8697),
    outlineVariant = Color(0xFF353C48),
)

/** Tema scuro secondo la scelta nelle impostazioni (o il sistema). */
@Composable
fun isDark(): Boolean {
    val t by AppSettings.theme.collectAsState()
    return when (t) {
        AppSettings.Theme.SYSTEM -> isSystemInDarkTheme()
        AppSettings.Theme.LIGHT -> false
        AppSettings.Theme.DARK -> true
    }
}

@Composable
fun ParkMapTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val palette by AppSettings.palette.collectAsState()
    val ctx = LocalContext.current
    val scheme = when {
        palette == AppSettings.Palette.DYNAMIC && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Colori dei chip informativi nelle schede (tono → sfondo, testo). */
object ChipTones {
    fun colors(tone: String, dark: Boolean): Pair<Color, Color> = when (tone) {
        "ok" -> if (dark) Color(0xFF14391F) to Color(0xFF86EFAC) else Color(0xFFDCFCE7) to Color(0xFF166534)
        "paid" -> if (dark) Color(0xFF1B2F5E) to Color(0xFF93C5FD) else Color(0xFFDBEAFE) to Color(0xFF1D4ED8)
        "time" -> if (dark) Color(0xFF2E2352) to Color(0xFFC4B5FD) else Color(0xFFEDE9FE) to Color(0xFF6D28D9)
        "ev" -> if (dark) Color(0xFF0F3B2E) to Color(0xFF6EE7B7) else Color(0xFFD1FAE5) to Color(0xFF047857)
        "warn" -> if (dark) Color(0xFF4A2A10) to Color(0xFFFDBA74) else Color(0xFFFFEDD5) to Color(0xFFC2410C)
        else -> if (dark) Color(0xFF2B313B) to Color(0xFFCBD5E1) else Color(0xFFEEF1F6) to Color(0xFF475569)
    }
}
