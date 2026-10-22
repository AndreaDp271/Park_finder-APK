package io.github.andreadp271.parkmap.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.parkmap.data.AppSettings
import io.github.andreadp271.parkmap.data.t

/** Icone simboliche dei dati (nomi usati da OsmRules e Describe) → icone Material. */
fun iconFor(name: String?): ImageVector = when (name) {
    "parking" -> Icons.Filled.LocalParking
    "ev" -> Icons.Filled.EvStation
    "zap" -> Icons.Filled.ElectricBolt
    "bike" -> Icons.Filled.PedalBike
    "ticket" -> Icons.Filled.ConfirmationNumber
    "accessible" -> Icons.AutoMirrored.Filled.Accessible
    "shield" -> Icons.Filled.Shield
    "truck" -> Icons.Filled.LocalShipping
    "baby" -> Icons.Filled.ChildCare
    "bus" -> Icons.Filled.DirectionsBus
    "clock" -> Icons.Filled.Schedule
    "circle-check" -> Icons.Filled.CheckCircle
    "coins" -> Icons.Filled.AttachMoney
    "circle-help" -> Icons.AutoMirrored.Filled.HelpOutline
    "timer" -> Icons.Filled.Timer
    "hourglass" -> Icons.Filled.HourglassBottom
    "car" -> Icons.Filled.DirectionsCar
    "gauge" -> Icons.Filled.Speed
    "plug" -> Icons.Filled.Power
    "lock" -> Icons.Filled.Lock
    "umbrella" -> Icons.Filled.Umbrella
    "battery" -> Icons.Filled.BatteryChargingFull
    else -> Icons.Filled.Block
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Indietro")) } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

val screenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Gruppo di impostazioni: titolo colorato e riquadro con angoli morbidi. */
@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** Riga di impostazioni: icona in un riquadro colorato, titolo, sottotitolo e controllo a destra. */
@Composable
fun Item(
    icon: ImageVector, color: Color, title: String, subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    val tint = if (isDark()) lerp(color, Color.White, 0.35f) else color
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = o == selected, onClick = { onSelect(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {}) {
                Text(label(o), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/** Riquadro con angoli morbidi: contenitore delle schede e delle liste. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
    )
}

// ─── Azioni di sistema ───

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, t("Nessuna app per aprire il collegamento"), Toast.LENGTH_SHORT).show()
    }
}

/** Apre la navigazione verso un punto con l'app scelta nelle impostazioni (a piedi: Waze non la ha, si usa Google Maps). */
fun navigateTo(ctx: Context, lat: Double, lon: Double, walking: Boolean = false) {
    val dest = "$lat,$lon"
    val url = when {
        AppSettings.navApp.value == AppSettings.NavApp.WAZE && !walking -> "https://waze.com/ul?ll=$dest&navigate=yes"
        AppSettings.navApp.value == AppSettings.NavApp.SYSTEM -> "geo:$dest?q=$dest"
        else -> "https://www.google.com/maps/dir/?api=1&destination=$dest&travelmode=${if (walking) "walking" else "driving"}"
    }
    openUrl(ctx, url)
}

fun shareText(ctx: Context, text: String, url: String? = null) {
    val body = if (url == null) text else if (text.isEmpty()) url else "$text\n$url"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).putExtra(Intent.EXTRA_SUBJECT, "ParkMap")
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun osmEditUrl(el: io.github.andreadp271.parkmap.data.OsmElement, zoom: Int = 19): String {
    val c = el.anchor
    return "https://www.openstreetmap.org/edit?editor=id&${el.type}=${el.id}#map=$zoom/${c?.lat}/${c?.lon}"
}
