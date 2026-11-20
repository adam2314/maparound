package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.map.MapFeature
import app.maparound.routing.GeoPoint
import app.maparound.shared.util.nowMillis
import app.maparound.tools.Astro
import app.maparound.tools.ToolsSession
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/**
 * Query mode's card for a tapped [point]: its ground (height and slope, the elevation data fetched
 * if it's missing), today's sun and moon there, and everything the map draws there ([features],
 * topmost first), each opening to all it says about itself.
 */
@Composable
fun QueryCard(point: GeoPoint, features: List<MapFeature>, onClose: () -> Unit) {
    // More elevation data may come while it's open.
    var terrainVersion by remember { mutableStateOf(ToolsSession.terrainVersion) }
    LaunchedEffect(point) {
        if (ToolsSession.elevationAt(point) == null) ToolsSession.fetchTerrainAt(point)
        while (true) {
            delay(1_000)
            terrainVersion = ToolsSession.terrainVersion
        }
    }
    val elevation = remember(point, terrainVersion) { ToolsSession.elevationAt(point) }
    val slope = remember(point, terrainVersion) { ToolsSession.slopeAt(point) }
    val missing = remember(point, terrainVersion) { ToolsSession.terrainMissingAt(point) }
    val now = remember(point) { nowMillis() }
    val sky = remember(point) { Sky.at(point, now) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(T.map.thisPoint, style = MaterialTheme.typography.titleMedium)
                    Text(Fmt.point(point.lat, point.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            Fact(T.map.elevation, elevation?.let { Fmt.height(it) } ?: if (missing) T.map.gettingTerrain else T.map.noTerrainHere)
            slope?.let { (degrees, facing) ->
                Fact(T.map.slope, if (degrees < 1) T.map.flat else T.map.slopeFacing(degrees.roundToInt(), Fmt.heading(facing.toFloat())))
            }
            Fact(T.map.localTime, Fmt.timeOfDay(now))
            Fact(T.map.firstLight, sky.day.firstLight?.let { Fmt.timeOfDay(it.timeMs) } ?: T.map.notToday)
            Fact(T.map.sunrise, sky.day.sunrise.event())
            Fact(T.map.sunset, sky.day.sunset.event())
            Fact(T.map.lastLight, sky.day.lastLight?.let { Fmt.timeOfDay(it.timeMs) } ?: T.map.notToday)
            Fact(T.map.moonrise, sky.day.moonrise.event())
            Fact(T.map.moonset, sky.day.moonset.event())
            val lit = (sky.moonLight.fraction * 100).roundToInt()
            Fact(
                T.map.moonNow,
                if (sky.moon.altitudeDeg > 0) T.map.moonUp(Fmt.heading(sky.moon.azimuthDeg.toFloat()), sky.moon.altitudeDeg.roundToInt(), lit)
                else T.map.moonDown(lit),
            )
            Fact(T.map.nextNewMoon, Fmt.date(sky.newMoonMs, now))
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp, end = 12.dp))
            Text(
                if (features.isEmpty()) T.map.nothingDrawnHere else T.map.drawnHere(features.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            features.forEach { FeatureRow(it) }
        }
    }
}

/** Today's sun and moon at a point, from its local midnight (the device's time zone). */
private class Sky(val day: Astro.Day, val moon: Astro.Sky, val moonLight: Astro.MoonLight, val newMoonMs: Long) {
    companion object {
        fun at(p: GeoPoint, nowMs: Long): Sky {
            val zone = TimeZone.currentSystemDefault()
            val midnight = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date.atStartOfDayIn(zone).toEpochMilliseconds()
            return Sky(Astro.day(midnight, p.lat, p.lon), Astro.moon(nowMs, p.lat, p.lon), Astro.moonLight(nowMs), Astro.nextNewMoon(nowMs))
        }
    }
}

/** A rise or a set: its time and the way to look ("8:32 · SE"), or that it doesn't happen today. */
private fun Astro.Event?.event(): String = this?.let { "${Fmt.timeOfDay(it.timeMs)} · ${Fmt.heading(it.azimuthDeg.toFloat())}" } ?: T.map.notToday

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the map draws here: its name or kind, then where it comes from; tapped, all its properties. */
@Composable
private fun FeatureRow(f: MapFeature) {
    var open by remember(f) { mutableStateOf(false) }
    val lang = I18n.contentLanguage
    val name = f.props["name:$lang"] ?: f.props["name"] ?: f.props["name_en"] ?: f.props["name:latin"]
    val kind = KIND_KEYS.firstNotNullOfOrNull { k -> f.props[k]?.takeIf { it.isNotBlank() }?.let { v -> if (k in VALUE_ALONE) v else "$k=$v" } }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 6.dp, bottom = 6.dp, end = 12.dp)) {
        Text(name ?: kind ?: f.layer, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(kind.takeIf { name != null }, f.sourceLayer.ifEmpty { f.source }.ifEmpty { null }, f.type.ifEmpty { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (open) {
            Text(
                (listOf("layer" to f.layer, "geometry" to f.geometry) + f.props.toList()).joinToString("\n") { (k, v) -> "$k: ${v.take(200)}" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The properties that say what a feature is, the first one it has. */
private val KIND_KEYS = listOf(
    "class", "subclass", "kind", "role", "highway", "natural", "amenity", "tourism", "shop", "leisure", "landuse",
    "waterway", "railway", "building", "place", "boundary", "admin_level", "type",
)

/** These say it by their value alone ("path"); the others as tag=value ("natural=peak"). */
private val VALUE_ALONE = setOf("class", "subclass", "kind", "role", "type")
