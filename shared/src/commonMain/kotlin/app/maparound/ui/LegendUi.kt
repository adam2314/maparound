package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maparound.i18n.T
import app.maparound.map.legend.MapLegend
import app.maparound.map.legend.OnlineLegends
import kotlinx.coroutines.CancellationException

/**
 * The legend of the map on screen: for an offline style, its own drawings of roads, paths, water,
 * land and symbols ([sections], made by [MapLegend]); for an online map, its makers' legend
 * ([online]). [symbol] draws one of the style's icons ("symbols/peak.svg"), or null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegendSheet(
    mapName: String,
    sections: List<MapLegend.Section>?,
    online: OnlineLegends.Info?,
    trailOverlay: Boolean,
    symbol: suspend (String) -> ImageBitmap?,
    onDismiss: () -> Unit,
    /** What's under the map, when it isn't plain (satellite imagery, a raster map), and what that leaves of it. */
    under: String? = null,
    /** The style drawn over satellite imagery: its samples over a ground like the imagery's. */
    overSatellite: Boolean = false,
) {
    val platform = LocalPlatform.current
    val t = T.legend
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("${t.legend} · $mapName", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            }
            when (online) {
                is OnlineLegends.Info.Link -> item {
                    Note(if (online.standardStyle) t.standardKeyNote else t.onlineLegend(mapName))
                    OutlinedButton(onClick = { platform.openUrl(online.url) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t.openLegend)
                    }
                }
                OnlineLegends.Info.Imagery -> item { Note(t.imageryNote) }
                OnlineLegends.Info.None -> item { Note(t.noLegend) }
                null -> Unit
            }
            under?.let { item { Note(it) } }
            if (trailOverlay) item { Note(t.trailOverlayNote) }
            if (sections != null) {
                item { Note(t.drawnLikeThis) }
                // The website's: every feature with its tags, colours and zooms, and what the maps keep.
                item {
                  Column {
                    Note(t.onTheWebsite)
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { platform.openUrl(Guides.map("legend")) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t.fullLegend)
                        }
                        OutlinedButton(onClick = { platform.openUrl(Guides.map("schema")) }) {
                            Text(t.whatMapsContain)
                        }
                    }
                  }
                }
                for (section in sections) {
                    item(key = "h-${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall.bigger(),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(section.entries, key = { "e-${section.title}-${it.label}" }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            LegendSample(entry.sample, symbol, overSatellite)
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium.bigger())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * How the style draws a feature, small: its area, its lines (casing first), its dot, its icon; on
 * the map's own ground, or [overSatellite] on one like the imagery's (dark earth and green).
 */
@Composable
private fun LegendSample(sample: MapLegend.Sample, symbol: suspend (String) -> ImageBitmap?, overSatellite: Boolean = false) {
    val icon by produceState<ImageBitmap?>(null, sample.symbol) {
        value = sample.symbol?.let { path ->
            try {
                symbol(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    // The map's own background under it, so light lines (white trails) show.
    val ground = Color(0xFFF4F1E8)
    Box(Modifier.size(width = SAMPLE_W.dp, height = SAMPLE_H.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = SAMPLE_W.dp, height = SAMPLE_H.dp)) {
            val corner = CornerRadius(4.dp.toPx())
            val px = 1.dp.toPx() * WIDTH_SCALE
            if (sample.hillshade) {
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFFF7F3EA), Color(0xFF8A7E6C)), Offset.Zero, Offset(size.width, size.height)), cornerRadius = corner)
                return@Canvas
            }
            if (overSatellite) {
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFF7A6E50), Color(0xFF3F4A30)), Offset.Zero, Offset(size.width, size.height)), cornerRadius = corner)
            } else {
                drawRoundRect(ground, cornerRadius = corner)
            }
            for (a in sample.areas) {
                a.fill?.let { drawRoundRect(Color(it.toInt()), cornerRadius = corner) }
                if (a.stroke != null && a.strokeWidth > 0f) {
                    val w = (a.strokeWidth * px).coerceIn(1f, 4.dp.toPx())
                    drawRoundRect(Color(a.stroke.toInt()), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), corner, style = Stroke(w))
                }
            }
            for (l in sample.lines) {
                val w = (l.width * px).coerceIn(1.dp.toPx() * 0.8f, size.height * 0.7f)
                val y = size.height / 2 + l.offset * px
                val dash = l.dash?.let { d -> PathEffect.dashPathEffect(d.map { (it * px).coerceAtLeast(1f) }.toFloatArray()) }
                drawLine(
                    Color(l.color.toInt()), Offset(4.dp.toPx(), y), Offset(size.width - 4.dp.toPx(), y),
                    strokeWidth = w, cap = if (dash != null) StrokeCap.Butt else StrokeCap.Round, pathEffect = dash,
                )
            }
            for (c in sample.circles) {
                val r = (c.radius * px).coerceIn(2.dp.toPx(), size.height / 2)
                c.fill?.let { drawCircle(Color(it.toInt()), r, center) }
                if (c.stroke != null && c.strokeWidth > 0f) drawCircle(Color(c.stroke.toInt()), r, center, style = Stroke((c.strokeWidth * px).coerceAtLeast(1f)))
            }
        }
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(ICON.dp)) }
    }
}

/** The legend's entries and titles: 2 points bigger than the app's text, to read beside the samples. */
private fun TextStyle.bigger() = copy(
    fontSize = (fontSize.value + LEGEND_TEXT_PLUS).sp,
    lineHeight = if (lineHeight.isSp) (lineHeight.value + LEGEND_TEXT_PLUS).sp else lineHeight,
)

private const val LEGEND_TEXT_PLUS = 2f
private const val SAMPLE_W = 64
private const val SAMPLE_H = 34
private const val ICON = 22

/** The theme's pixels in dp, for the samples (mapsforge widths are small: 1–4). */
private const val WIDTH_SCALE = 1.6f
