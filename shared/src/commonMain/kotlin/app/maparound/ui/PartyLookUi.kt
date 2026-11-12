package app.maparound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maparound.party.PartyIcon
import app.maparound.party.PartyIcons
import app.maparound.party.PartyPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A member as in the list and on the map: their icon (or initial) on a disc of their colour. */
@Composable
fun PartyAvatar(name: String, color: Int, icon: Int, size: Dp, modifier: Modifier = Modifier, sos: Boolean = false) {
    val look = PartyIcon.of(icon)
    val on = Color(PartyPalette.onColor(color))
    Box(
        modifier.size(size).background(Color(color), CircleShape)
            .then(if (sos) Modifier.border(3.dp, MaterialTheme.colorScheme.error, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (look == PartyIcon.LETTER) {
            Text(name.take(1).uppercase(), color = on, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        } else {
            PartyIconShape(look, on, Modifier.size(size * 0.7f))
        }
    }
}

/** The icon's shape (the same polygons the maps draw). */
@Composable
fun PartyIconShape(icon: PartyIcon, tint: Color, modifier: Modifier = Modifier) {
    val paths = remember(icon) { PartyIcons.paths(icon) }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val ox = (size.width - 24 * s) / 2
        val oy = (size.height - 24 * s) / 2
        for (p in paths) {
            val path = Path().apply { fillType = if (p.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero }
            for (sub in p.subpaths) {
                path.moveTo(ox + sub[0] * s, oy + sub[1] * s)
                for (k in 2 until sub.size step 2) path.lineTo(ox + sub[k] * s, oy + sub[k + 1] * s)
                path.close()
            }
            drawPath(path, tint)
        }
    }
}

/** Picks a member's icon (by group) and colour (presets, or mixed with hue and shade). */
@Composable
fun PartyLookPicker(name: String, color: Int, icon: Int, onColor: (Int) -> Unit, onIcon: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PartyIcon.Group.entries.forEach { group ->
            Text(group.label, style = MaterialTheme.typography.labelLarge)
            PartyIcon.entries.filter { it.group == group }.chunked(PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { option ->
                        val chosen = option.code == icon
                        Box(
                            Modifier.size(40.dp)
                                .border(if (chosen) 3.dp else 0.dp, if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(3.dp)
                                .clickable { onIcon(option.code) }
                                .semantics { contentDescription = option.label; selected = chosen },
                            contentAlignment = Alignment.Center,
                        ) { PartyAvatar(name, color, option.code, 34.dp) }
                    }
                }
            }
        }
        Text("Colour", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        PartyPalette.PRESETS.toList().chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c ->
                    val chosen = c == color
                    Box(
                        Modifier.size(28.dp).background(Color(c), CircleShape)
                            .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { onColor(c) }
                            .semantics { selected = chosen },
                    )
                }
            }
        }
        ColourMixer(color, onColor)
    }
}

/** Any colour: hue and shade sliders (saturation kept strong, so the map marker stands out). */
@Composable
private fun ColourMixer(color: Int, onColor: (Int) -> Unit) {
    val hsl = remember(color) { hsl(color) }
    var hue by remember(color) { mutableFloatStateOf(hsl[0]) }
    var shade by remember(color) { mutableFloatStateOf(hsl[2].coerceIn(MIN_SHADE, MAX_SHADE)) }
    fun mix() = Color.hsl(hue, 0.75f, shade).toArgb()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hue", Modifier.size(width = 48.dp, height = 20.dp), style = MaterialTheme.typography.bodySmall)
        Slider(hue, { hue = it; onColor(mix()) }, valueRange = 0f..359f, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Shade", Modifier.size(width = 48.dp, height = 20.dp), style = MaterialTheme.typography.bodySmall)
        Slider(shade, { shade = it; onColor(mix()) }, valueRange = MIN_SHADE..MAX_SHADE, modifier = Modifier.weight(1f))
    }
}

private const val PER_ROW = 6
private const val MIN_SHADE = 0.2f
private const val MAX_SHADE = 0.7f

/** Hue (0–360), saturation and lightness (0–1) of an ARGB colour. */
internal fun hsl(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val l = (hi + lo) / 2
    val d = hi - lo
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val s = d / (1 - abs(2 * l - 1))
    val h = when (hi) {
        r -> 60 * (((g - b) / d) % 6)
        g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }
    return floatArrayOf((h + 360) % 360, s, l)
}
