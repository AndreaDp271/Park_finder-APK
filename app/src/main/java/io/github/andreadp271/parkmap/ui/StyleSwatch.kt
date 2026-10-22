package io.github.andreadp271.parkmap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreadp271.parkmap.data.t

/** Riquadro con l'anteprima dello stile (gli stessi disegni della web app). */
@Composable
private fun StyleSwatch(id: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val diag = { stops: List<Pair<Float, Color>> ->
                // bande a spigolo vivo: ogni colore va dalla fine del precedente alla propria fine
                val pts = ArrayList<Pair<Float, Color>>()
                var from = 0f
                stops.forEach { (end, c) -> pts += from to c; pts += end to c; from = end }
                Brush.linearGradient(colorStops = pts.toTypedArray(), start = Offset.Zero, end = Offset(size.width, size.height))
            }
            when (id) {
                "osm" -> drawRect(diag(listOf(.4f to Color(0xFFF2EFE9), .55f to Color(0xFFAAD3DF), .75f to Color(0xFFC8FACC), 1f to Color.White)))
                "light" -> drawRect(diag(listOf(.4f to Color(0xFFF6EFE6), .55f to Color(0xFFA5BFDD), .75f to Color(0xFFCFE8C0), 1f to Color(0xFFFDE9C0))))
                "cycle" -> drawRect(diag(listOf(.4f to Color(0xFFEEF3E4), .55f to Color(0xFFB9D7EE), .7f to Color(0xFFF5D27A), .8f to Color(0xFF2F6FDD), 1f to Color(0xFFEEF3E4))))
                "gray" -> drawRect(diag(listOf(.4f to Color(0xFFF0F0F0), .6f to Color(0xFFDCDCDC), 1f to Color(0xFFFAFAFA))))
                "dark" -> drawRect(diag(listOf(.4f to Color(0xFF1C2430), .55f to Color(0xFF0F3A52), .75f to Color(0xFF26323F), 1f to Color(0xFF3A4757))))
                "topo" -> {
                    // curve di livello: anelli concentrici attorno al punto 70% / 60%
                    val c = Offset(size.width * .7f, size.height * .6f)
                    val px = density
                    val far = kotlin.math.hypot(maxOf(c.x, size.width - c.x), maxOf(c.y, size.height - c.y))
                    drawRect(Color(0xFFE6DCC2))
                    val period = 14f * px
                    var k = (far / period).toInt() + 1
                    while (k >= 0) {
                        val base = k * period
                        drawCircle(Color(0xFFA8C98A), base + 14f * px, c)
                        drawCircle(Color(0xFFD9E9C6), base + 13f * px, c)
                        drawCircle(Color(0xFFC9B78A), base + 7f * px, c)
                        drawCircle(Color(0xFFE6DCC2), base + 6f * px, c)
                        k--
                    }
                }
                else -> drawRect(Brush.linearGradient(
                    0f to Color(0xFF3D5A2A), .4f to Color(0xFF6B7F3C), .6f to Color(0xFF2A4A5C), 1f to Color(0xFF5B4A36),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                ))
            }
        }
        if (id == "hybrid") Text("Abc", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Griglia di 4 colonne con gli stili, come nella web app: riquadro di anteprima e nome, quello scelto evidenziato. */
@Composable
fun StyleGrid(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapStyles.all.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    val on = s.id == selected
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.weight(1f)
                            .clip(shape)
                            .border(BorderStroke(2.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent), shape)
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(s.id) }
                            .padding(start = 3.dp, end = 3.dp, top = 5.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        StyleSwatch(s.id, Modifier.fillMaxWidth())
                        Text(t(s.label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
    }
}
