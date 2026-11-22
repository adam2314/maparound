package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import app.maparound.i18n.ltr
import app.maparound.shared.util.nowMillis
import app.maparound.stats.TimeZones
import app.maparound.stats.BuildState
import app.maparound.stats.MapBuilds
import app.maparound.stats.MapBuildsData
import app.maparound.data.CatalogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import app.maparound.stats.Weather
import app.maparound.stats.WeatherLayer
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.stats.AGE_GROUPS
import app.maparound.stats.Country
import app.maparound.stats.CountryIndicator
import app.maparound.stats.Figure
import app.maparound.stats.Locality
import app.maparound.stats.LocalityIndicator
import app.maparound.stats.NO_DATA
import app.maparound.stats.StatClass

/** "#rrggbb" as a colour. */
internal fun hexColor(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))

/** What the data view shows: its layers, as the explorer's tree has them. */
data class DataLayers(
    /** What colours the countries; null: nothing (or the time zones do). Nothing on to start with. */
    val country: CountryIndicator? = null,
    /** The time zones colour the world instead of a country figure. */
    val timeZones: Boolean = false,
    /** What colours Israel's localities; null: they're hidden. */
    val locality: LocalityIndicator? = null,
    val gpsTraces: Boolean = false,
    val rainRadar: Boolean = false,
    /** What the weather's points show; null: none. */
    val weather: WeatherLayer? = null,
    /** What colours the weather map (NOAA's forecast); null: none. One of a kind with [weather]. */
    val weatherMap: app.maparound.stats.WeatherMapField? = null,
    /** The wind as moving particles. */
    val windAnimation: Boolean = false,
    val photos: Boolean = false,
    val buses: Boolean = false,
    val cameras: Boolean = false,
    /** How MapAround's maps stand, region by region, colour the world (instead of a figure or the time zones). */
    val mapBuilds: Boolean = false,
) {
    /**
     * One of the layers over the map (the weather, photos, buses) turned on: the colours under it
     * (countries, time zones, localities, the map builds) go, so the map shows through; they can be
     * picked again.
     */
    fun withOverlay(change: DataLayers.() -> DataLayers): DataLayers =
        change(this).copy(country = null, timeZones = false, locality = null, mapBuilds = false)

    /** Whether the weather maps are on (a field, or the wind's particles). */
    val weatherMaps: Boolean get() = weatherMap != null || windAnimation

    /** Whether anything colours the world or Israel's localities. */
    val coloured: Boolean get() = country != null || timeZones || locality != null || mapBuilds
}

/**
 * A data view to open from a link: the web app's `data=<name>` in its address (`data=maps`: how
 * MapAround's maps stand, as the support page embeds it). The map screen opens it once it's there.
 */
object DataViewLink {
    /** A `data=` to open ([MapAddress.data]), and what's tapped in it (a tap key), if anything. */
    val pending = MutableStateFlow<String?>(null)
    var pendingSelected: String? = null

    /** The layers a link's `data=` stands for, or null. */
    fun layers(name: String): DataLayers? = MapAddress.data(name)

    /** The data view as shown now, for the web app's address: its `data=` (null: off), and the tapped key. */
    val current = MutableStateFlow<String?>(null)
    val currentSelected = MutableStateFlow<String?>(null)

}

/** What the data view can offer: the layers there's data for, whose source isn't off. */
data class DataOffers(
    val timeZones: Boolean = false,
    val gpsTraces: Boolean = false,
    val radar: Boolean = false,
    val weather: Boolean = false,
    val weatherMaps: Boolean = false,
    val photos: Boolean = false,
    val buses: Boolean = false,
    val cameras: Boolean = false,
    val mapBuilds: Boolean = false,
)

/**
 * A leaf of the explorer's tree: its title, where its data comes from, whether it's on, whether it
 * goes instead of others ([exclusive]: one of a kind, a radio button; else a check box), the words
 * that find it, and what a tap does.
 */
private class Leaf(
    val title: String,
    val source: String,
    val on: Boolean,
    val exclusive: Boolean,
    val words: String,
    /** Not there yet: shown as coming soon, can't be picked. */
    val soon: Boolean = false,
    val pick: () -> Unit,
)

private class Branch(val key: String, val title: String, val icon: ImageVector, val leaves: List<Leaf>)

/**
 * The tree: the world's figures, Israel's, time, weather, looking around, movement. Picking the
 * layer that's on turns it off.
 */
private fun branches(layers: DataLayers, offers: DataOffers, onPick: (DataLayers) -> Unit): List<Branch> {
    val s = T.stats
    val l = T.live
    return buildList {
        add(Branch("world", s.world, Icons.Filled.Public, CountryIndicator.entries.map { i ->
            val on = layers.country == i && !layers.timeZones
            Leaf(i.title, s.countrySource(i), on, true, s.countriesKeywords) { onPick(layers.copy(country = if (on) null else i, timeZones = false, mapBuilds = false)) }
        }))
        add(Branch("israel", s.israel, Icons.Filled.LocationCity, LocalityIndicator.entries.map { i ->
            val on = layers.locality == i
            Leaf(i.title, s.localitySource(i), on, true, s.localitiesKeywords) { onPick(layers.copy(locality = if (on) null else i, mapBuilds = false)) }
        }))
        if (offers.timeZones) add(Branch("time", s.time, Icons.Filled.Schedule, listOf(
            Leaf(s.timeZones, s.timeZonesSource, layers.timeZones, true, s.timeZonesKeywords) {
                onPick(if (layers.timeZones) layers.copy(timeZones = false) else layers.copy(timeZones = true, country = null, mapBuilds = false))
            },
        )))
        if (offers.mapBuilds) add(Branch("maparound", s.mapAround, Icons.Filled.Map, listOf(
            Leaf(s.mapBuilds, s.mapBuildsSource, layers.mapBuilds, true, s.mapBuildsKeywords) {
                onPick(
                    if (layers.mapBuilds) layers.copy(mapBuilds = false)
                    else layers.copy(mapBuilds = true, country = null, timeZones = false, locality = null),
                )
            },
        )))
        val weather = buildList {
            if (offers.radar) add(Leaf(l.rainRadar, l.radarSource, layers.rainRadar, false, "${l.weatherKeywords} ${l.radarKeywords}") {
                onPick(if (layers.rainRadar) layers.copy(rainRadar = false) else layers.withOverlay { copy(rainRadar = true) })
            })
            if (offers.weatherMaps) {
                add(Leaf(l.windAnimation, l.weatherMapsSource, layers.windAnimation, false, "${l.weatherKeywords} ${l.windKeywords} ${l.weatherMapsKeywords}") {
                    onPick(if (layers.windAnimation) layers.copy(windAnimation = false) else layers.withOverlay { copy(windAnimation = true) })
                })
                for (f in app.maparound.stats.WeatherMapField.entries) add(Leaf(
                    f.title, l.weatherMapsSource, layers.weatherMap == f, true,
                    "${l.weatherKeywords} ${l.weatherMapsKeywords} " + when (f) {
                        app.maparound.stats.WeatherMapField.TEMPERATURE -> l.temperatureKeywords
                        app.maparound.stats.WeatherMapField.CLOUDS -> l.cloudsKeywords
                        app.maparound.stats.WeatherMapField.PRECIP -> l.radarKeywords
                    },
                ) {
                    // One colour field at a time: the map's or the points'.
                    onPick(if (layers.weatherMap == f) layers.copy(weatherMap = null) else layers.withOverlay { copy(weatherMap = f, weather = null) })
                })
            }
            if (offers.weather) for (w in WeatherLayer.entries) add(Leaf(
                w.title, l.weatherSource, layers.weather == w, true,
                "${l.weatherKeywords} ${if (w == WeatherLayer.TEMPERATURE) l.temperatureKeywords else l.windKeywords}",
            ) {
                onPick(if (layers.weather == w) layers.copy(weather = null) else layers.withOverlay { copy(weather = w, weatherMap = null) })
            })
        }
        if (weather.isNotEmpty()) add(Branch("weather", l.weather, Icons.Filled.Cloud, weather))
        if (offers.photos) add(Branch("look", l.lookAround, Icons.Filled.PhotoCamera, listOf(
            Leaf(l.streetPhotos, l.photosSource, layers.photos, false, l.photosKeywords) {
                onPick(if (layers.photos) layers.copy(photos = false) else layers.withOverlay { copy(photos = true) })
            },
        )))
        val movement = buildList {
            if (offers.gpsTraces) add(Leaf(s.gpsTraces, s.gpsTracesSource, layers.gpsTraces, false, s.gpsKeywords) {
                onPick(layers.copy(gpsTraces = !layers.gpsTraces))
            })
            if (offers.buses) add(Leaf(l.buses, l.busesSource, layers.buses, false, l.busesKeywords) {
                onPick(if (layers.buses) layers.copy(buses = false) else layers.withOverlay { copy(buses = true) })
            })
        }
        if (movement.isNotEmpty()) add(Branch("movement", s.movement, Icons.Filled.Route, movement))
        add(Branch("cameras", l.cameras, Icons.Filled.Videocam, buildList {
            if (offers.cameras) add(Leaf(l.camerasLayer, l.camerasSource, layers.cameras, false, l.camerasKeywords) {
                onPick(if (layers.cameras) layers.copy(cameras = false) else layers.withOverlay { copy(cameras = true) })
            })
            add(Leaf(l.webcams, l.webcamsSource, false, false, l.webcamsKeywords, soon = true) {})
        }))
    }
}

/** A branch this long or shorter starts open in the explorer (a longer one, Israel's, folded). */
private const val SHORT_BRANCH = 5

/** The explorer drawer's width, at most. */
val EXPLORER_WIDTH = 380.dp

/**
 * The data view's explorer, in a drawer of its own along the screen's start edge: a search box,
 * what's on the map now (each can be taken off, or all), and the tree of everything the view can
 * show, each layer with where its data comes from. On a narrow screen ([narrow]) it covers most of
 * the map, over a scrim that closes it; on a wide one the map stays in use beside it.
 *
 * It folds away ([onClose]) with the data staying on the map; while the data view is on ([inView])
 * and it's folded, a tab on the screen's edge brings it back ([onOpen]).
 */
/**
 * The data view's state, shared by the map (what it draws) and the explorer, which is in the side
 * panel on a big screen (like the tabs) and in a drawer over the map on a phone.
 */
object DataView {
    /** Whether it's on the map. */
    var on by mutableStateOf(false)

    /** What it shows: nothing to start with, until picked. */
    var layers by mutableStateOf(DataLayers())

    /** Whether its explorer is open. */
    var explorerOpen by mutableStateOf(false)

    /**
     * Layers picked in the explorer: the map screen sets this to its own (which also brings back
     * the classes hidden in the legend of what changed).
     */
    var pick: (DataLayers) -> Unit = { layers = it }

    /** On, with the explorer open. */
    fun open() {
        on = true
        explorerOpen = true
    }

    /** Off, the explorer closed. */
    fun leave() {
        on = false
        explorerOpen = false
    }
}

/** What the data view can offer here: what [stats] has, the sources that aren't off. */
fun dataOffers(stats: app.maparound.stats.StatsData): DataOffers = DataOffers(
    timeZones = stats.hasTimeZones,
    gpsTraces = app.maparound.map.TileSources.gpsTraces != null,
    radar = !app.maparound.sources.Sources.isOff(app.maparound.sources.SourceKind.RAIN_RADAR),
    weather = !app.maparound.sources.Sources.isOff(app.maparound.sources.SourceKind.WEATHER),
    photos = !app.maparound.sources.Sources.isOff(app.maparound.sources.SourceKind.STREET_PHOTOS),
    buses = stats.hasBuses,
    cameras = !app.maparound.sources.Sources.isOff(app.maparound.sources.SourceKind.CAMERAS),
    weatherMaps = !app.maparound.sources.Sources.isOff(app.maparound.sources.SourceKind.WEATHER_MAPS),
    mapBuilds = stats.hasMapBuilds,
)

/**
 * The explorer: what's on the map, the search, the tree of what the data view can show, and
 * leaving it at the bottom. [header]: its top row (the drawer's, or the side panel's with its ✕);
 * [bottomEnd]: after "Leave". [query] and [expanded] are kept by whoever keeps them across closing.
 */
@Composable
fun DataExplorer(
    layers: DataLayers,
    offers: DataOffers,
    onLayers: (DataLayers) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    query: MutableState<String> = rememberSaveable { mutableStateOf("") },
    expanded: MutableMap<String, Boolean> = remember { mutableStateMapOf() },
    header: @Composable RowScope.() -> Unit,
    bottomEnd: @Composable RowScope.() -> Unit = {},
) {
    val s = T.stats
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, content = header)
        OutlinedTextField(
            value = query.value,
            onValueChange = { query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text(s.findData) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.value.isNotEmpty()) ({
                Tip(T.library.clear) { IconButton(onClick = { query.value = "" }) { Icon(Icons.Filled.Close, contentDescription = T.library.clear) } }
            }) else null,
        )
        val tree = branches(layers, offers, onLayers)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            OnTheMap(tree, onClear = { onLayers(DataLayers()) })
            Tree(tree, query.value, expanded)
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLeave) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.leaveDataView)
            }
            Spacer(Modifier.weight(1f))
            bottomEnd()
        }
    }
}

/** The explorer's title: the layers' icon, "Data view" and what it's for. */
@Composable
fun RowScope.DataExplorerTitle() {
    Icon(Icons.Filled.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
    Column(Modifier.weight(1f)) {
        Text(T.stats.dataView, style = MaterialTheme.typography.titleMedium)
        Text(T.stats.explorerSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The explorer in a drawer over the map's side (a phone; on a big screen it's in the side panel). */
@Composable
fun DataExplorerDrawer(
    open: Boolean,
    narrow: Boolean,
    width: Dp,
    layers: DataLayers,
    offers: DataOffers,
    onLayers: (DataLayers) -> Unit,
    onClose: () -> Unit,
    onLeave: () -> Unit,
    inView: Boolean = open,
    onOpen: () -> Unit = {},
) {
    val s = T.stats
    // Kept while it's closed: what was being looked for, which branches are open.
    val query = rememberSaveable { mutableStateOf("") }
    val expandedBranches = remember { mutableStateMapOf<String, Boolean>() }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    Box(Modifier.fillMaxSize()) {
        // Folded away: its tab on the edge, the layers' icon and an arrow out.
        AnimatedVisibility(
            inView && !open,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { if (ltr) -it else it } + fadeIn(),
            exit = slideOutHorizontally { if (ltr) -it else it } + fadeOut(),
        ) {
            Tip(s.layers) {
                Surface(
                    onClick = onOpen,
                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(start = 6.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Layers, contentDescription = s.layers, tint = MaterialTheme.colorScheme.primary)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        AnimatedVisibility(open && narrow, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.TopStart),
            enter = slideInHorizontally { if (ltr) -it else it } + fadeIn(),
            exit = slideOutHorizontally { if (ltr) -it else it } + fadeOut(),
        ) {
            Surface(
                Modifier.width(width).fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
            ) {
                DataExplorer(
                    layers, offers, onLayers, onLeave,
                    query = query, expanded = expandedBranches,
                    header = {
                        DataExplorerTitle()
                        // Folds it away; the data stays on the map (leaving the data view is at the bottom).
                        Tip(s.showTheMap) {
                            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.showTheMap) }
                        }
                    },
                    bottomEnd = { if (narrow) Button(onClick = onClose) { Text(s.showTheMap) } },
                )
            }
        }
    }
}

/** What's on the map now: each layer with its branch, a tap on it (or its ✕) takes it off; and "Clear". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnTheMap(tree: List<Branch>, onClear: () -> Unit) {
    val on = tree.flatMap { b -> b.leaves.filter { it.on }.map { b to it } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(T.stats.onTheMap, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (on.size > 1) TextButton(onClick = onClear) { Text(T.stats.clearAll) }
    }
    if (on.isEmpty()) {
        Text(
            T.stats.nothingOnTheMap,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    } else {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((b, leaf) in on) {
                InputChip(
                    selected = true,
                    onClick = leaf.pick,
                    label = { Text(leaf.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(b.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = T.stats.takeOff, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/**
 * The tree's branches and leaves (or, searching, the leaves whose name, branch or words have every
 * word typed). A folded branch shows how many of its layers are on.
 */
@Composable
private fun Tree(tree: List<Branch>, query: String, expandedBranches: MutableMap<String, Boolean>) {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    var found = 0
    for (b in tree) {
        val leaves = if (words.isEmpty()) b.leaves else b.leaves.filter { l ->
            val text = "${l.title} ${b.title} ${l.words} ${l.source}".lowercase()
            words.all { it in text }
        }
        if (leaves.isEmpty()) continue
        found += leaves.size
        val expanded = words.isNotEmpty() || (expandedBranches[b.key] ?: (b.leaves.size <= SHORT_BRANCH))
        Row(
            Modifier.fillMaxWidth().clickable { expandedBranches[b.key] = !expanded }.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(b.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(b.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val on = b.leaves.count { it.on }
            if (on > 0 && !expanded) {
                Box(
                    Modifier.padding(end = 8.dp).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("$on", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) }
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        if (expanded) for (l in leaves) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !l.soon, onClick = l.pick)
                    .padding(start = 22.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // What it shows instead of the others (one of a kind), or besides them; or not yet.
                when {
                    // As wide as a check box.
                    l.soon -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    l.exclusive -> RadioButton(selected = l.on, onClick = null)
                    else -> Checkbox(checked = l.on, onCheckedChange = null)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp).alpha(if (l.soon) 0.6f else 1f)) {
                    Text(
                        l.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (l.on) FontWeight.Medium else null,
                    )
                    Text(l.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (l.soon) {
                    Text(
                        T.live.comingSoon,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
    if (found == 0) Text(T.stats.nothingFound, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    Spacer(Modifier.height(8.dp))
}

/**
 * The data view's panel over the map: what colours the map now and its legend (a tap hides a
 * class), the live layers' legends and notes, what's loading, the way to the other level, the
 * button to the explorer ([onExplore]), and the way out.
 */
@Composable
fun DataViewPanel(
    showLocalities: Boolean,
    layers: DataLayers,
    hidden: Set<Int>,
    onToggleClass: (Int) -> Unit,
    /** The weather's classes hidden, and a tap on one. */
    hiddenWeather: Set<Int>,
    onToggleWeather: (Int) -> Unit,
    /** The kinds of cameras hidden, and a tap on one. */
    hiddenCameras: Set<Int>,
    onToggleCameras: (Int) -> Unit,
    /** What the live layers say (when their data is from, what to do). */
    notes: List<String>,
    status: String,
    onGoTo: () -> Unit,
    onExplore: () -> Unit,
    onClose: () -> Unit,
    /** The weather map's time, when it's on. */
    weatherTime: @Composable () -> Unit = {},
) {
    val s = T.stats
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            // What's shown where the map is now: the localities zoomed in, else what colours the world.
            val zones = !showLocalities && layers.timeZones
            val locality = layers.locality
            val country = layers.country
            val l = T.live
            val (group, title) = when {
                layers.mapBuilds -> s.mapAround to s.mapBuilds
                showLocalities && locality != null -> s.localities to locality.title
                zones -> s.time to s.timeZones
                !showLocalities && country != null -> s.countries to country.title
                layers.weatherMap != null -> l.weather to layers.weatherMap.title
                layers.weather != null -> l.weather to layers.weather.title
                layers.windAnimation -> l.weather to l.windAnimation
                layers.rainRadar -> l.weather to l.rainRadar
                layers.buses -> s.movement to l.buses
                layers.cameras -> l.cameras to l.camerasLayer
                layers.photos -> l.lookAround to l.streetPhotos
                layers.gpsTraces -> s.movement to s.gpsTraces
                else -> null to s.dataView
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (group != null) Text(group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = onExplore) {
                    Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.layers)
                }
                Tip(s.leaveDataView) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = s.leaveDataView) } }
            }
            when {
                // At every zoom: a region's map, close up too.
                layers.mapBuilds -> Legend(MapBuilds.classes, hidden, onToggleClass, noData = false)
                showLocalities && locality != null -> {
                    Legend(locality.classes, hidden, onToggleClass)
                    Hint(s.sizeIsPopulation, s.countries, onGoTo)
                }
                zones -> {
                    Text(s.offsetsNow, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    Legend(TimeZones.classes, hidden, onToggleClass, noData = false)
                    Hint(null, s.localities, onGoTo)
                }
                !showLocalities && country != null -> {
                    Legend(country.classes, hidden, onToggleClass)
                    if (locality != null) Hint(s.zoomInForLocalities, s.localities, onGoTo)
                }
                layers.coloured -> Hint(null, if (showLocalities) s.countries else s.localities, onGoTo)
            }
            layers.weatherMap?.let { f -> Legend(app.maparound.stats.WeatherMap.legend(f), emptySet(), {}, noData = false) }
            if (layers.weatherMaps) weatherTime()
            layers.weather?.let { w ->
                if (layers.coloured) Text(w.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Legend(Weather.classes(w), hiddenWeather, onToggleWeather, noData = false)
            }
            if (layers.cameras) Legend(app.maparound.stats.Cameras.classes, hiddenCameras, onToggleCameras, noData = false)
            for (n in notes) Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (layers.gpsTraces) Text(s.gpsTracesNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (layers == DataLayers(country = null, locality = null)) {
                Text(s.nothingOnTheMap, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A note, and the button to the other level. */
@Composable
private fun Hint(note: String?, goTo: String, onGoTo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            note.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onGoTo) { Text(goTo) }
    }
}

/** The classes' colours and labels, and "No data" (-1); a hidden one is faded. */
@Composable
private fun Legend(classes: List<StatClass>, hidden: Set<Int>, onToggle: (Int) -> Unit, noData: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (classes.mapIndexed { i, c -> i to c } + listOfNotNull((-1 to StatClass(T.stats.noData, NO_DATA)).takeIf { noData })).forEach { (i, c) ->
            Row(
                Modifier.alpha(if (i in hidden) 0.35f else 1f).clickable { onToggle(i) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).background(hexColor(c.color), CircleShape).border(1.dp, Color.White, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(c.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A tapped country: its flag and name, its area, capital and languages, the figures loaded so far,
 * and its Wikipedia article.
 */
@Composable
fun CountryCard(country: Country, figures: Map<CountryIndicator, Map<String, Figure>>, flagUrl: String?, onClose: () -> Unit) {
    val platform = app.maparound.ui.LocalPlatform.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            val he = I18n.language == app.maparound.i18n.Language.HE
            Row(verticalAlignment = Alignment.CenterVertically) {
                val size = country.flag
                if (flagUrl != null && size != null) {
                    // As tall as the name, as wide as its own shape (Switzerland's square, Qatar's long), with a thin edge for white ones.
                    val w = (FLAG_HEIGHT.value * size.first / size.second).coerceIn(FLAG_HEIGHT.value * 0.8f, FLAG_HEIGHT.value * 2.6f)
                    RemoteImage(
                        flagUrl,
                        null,
                        Modifier.padding(end = 10.dp).height(FLAG_HEIGHT).width(w.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        androidx.compose.ui.layout.ContentScale.FillBounds,
                    )
                }
                Text(country.name(he), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            // What it is: its size (in the unit lengths are in), capital and languages.
            val facts = listOfNotNull(
                country.areaKm2?.let(T.stats::areaOf),
                country.capitals.takeIf { it.isNotEmpty() }?.let { cs -> T.stats.capitalsOf(cs.map { if (he) it.second else it.first }) },
                country.languages.takeIf { it.isNotEmpty() }?.let { ls ->
                    // The most spoken (worldwide, so not a ranking within the country), by name.
                    T.stats.languagesOf(ls.take(MAX_LANGUAGES).map { if (he) it.second else it.first }.sorted(), ls.size - minOf(ls.size, MAX_LANGUAGES))
                },
            )
            for (f in facts) Text(f, style = MaterialTheme.typography.bodyMedium)
            if (facts.isNotEmpty()) Spacer(Modifier.height(6.dp))
            for (i in CountryIndicator.entries) {
                val f = figures[i]?.get(country.iso) ?: continue
                Text("${i.title}: ${i.format(f.value)} ${T.stats.asOf(f.year)}", style = MaterialTheme.typography.bodyMedium)
            }
            country.wikipediaUrl(he)?.let { url ->
                TextButton(onClick = { platform.openUrl(url) }, modifier = Modifier.padding(top = 2.dp)) { Text(T.map.wikipedia) }
            }
        }
    }
}

/** A country's card names this many of its languages (the most spoken), then how many more. */
private const val MAX_LANGUAGES = 5

/** A country's flag on its card: as tall as this. */
private val FLAG_HEIGHT = 26.dp

/** A tapped locality: what it is, its people (by age), and a way to pin it (for What's here, Wikipedia…). */
@Composable
fun LocalityCard(l: Locality, onPin: () -> Unit, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    val he = I18n.language == app.maparound.i18n.Language.HE
                    Text(if (he) l.nameHe else l.nameEn.ifBlank { l.nameHe }, style = MaterialTheme.typography.titleMedium)
                    Text(if (he) l.nameEn else l.nameHe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            val kind = T.stats.localityTypes[LocalityIndicator.typeOf(l.form)]
            Text(listOfNotNull(kind, T.stats.district(l.district).takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(T.stats.residents(l.population), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            l.arabs?.let { (100.0 * it / l.population).toInt() }?.takeIf { it >= 1 }?.let { Text(T.stats.arabsShare(it), style = MaterialTheme.typography.bodySmall) }
            val facts = listOfNotNull(
                l.founded?.let(T.stats::foundedIn),
                l.altitudeM?.let { T.stats.altitudeOf(Fmt.height(it)) },
                l.cluster?.let(T.stats::clusterOf),
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            l.ages?.takeIf { it.size == AGE_GROUPS && l.agesTotal > 0 }?.let { AgeBars(it) }
            TextButton(onClick = onPin, modifier = Modifier.padding(top = 4.dp)) { Text(T.stats.pinIt) }
        }
    }
}

/** The residents' age groups as bars, with their shares. */
@Composable
private fun AgeBars(ages: List<Int>) {
    val total = ages.sum().toFloat()
    val most = ages.max().toFloat()
    Text(T.stats.byAge, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
    for (i in ages.indices) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
            Text(app.maparound.i18n.ltr(T.stats.ageGroups[i]), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
            Box(Modifier.weight(1f).height(10.dp)) {
                Box(
                    Modifier.fillMaxWidth(ages[i] / most).height(10.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }
            Text(
                app.maparound.i18n.ltr("${(100 * ages[i] / total).toInt()}%"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(40.dp).padding(start = 6.dp),
            )
        }
    }
}

/**
 * A tapped time zone: where it is, its time now (ticking), its offset and the difference from the
 * device's, and its next clock change. [offsetAt]: a zone's offset at a time (null: the device's).
 */
@Composable
fun ZoneCard(zone: String, offsetAt: (zone: String?, epochMillis: Long) -> Int?, onClose: () -> Unit) {
    val s = T.stats
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(zone) {
        while (true) {
            now = nowMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    // The next change once a minute: it takes a few hundred lookups.
    val minute = now / 60_000L
    val change = remember(zone, minute) { TimeZones.nextChange(now) { offsetAt(zone, it) } }
    val offset = offsetAt(zone, now)
    val mine = offsetAt(null, now)
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val place = TimeZones.place(zone)
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(place?.first ?: s.atSea, style = MaterialTheme.typography.titleMedium)
                    Text(
                        ltr(place?.second?.takeIf { it.isNotEmpty() }?.let { "$it · $zone" } ?: if (place == null) "${s.seaZone} · $zone" else zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            if (offset == null) {
                Text(s.noData, style = MaterialTheme.typography.bodyMedium)
            } else {
                val local = Instant.fromEpochMilliseconds(now + offset * 1000L).toLocalDateTime(TimeZone.UTC)
                Text(
                    s.localTime(Fmt.clock(local.hour, local.minute, local.second), Fmt.day(local.date, withYear = false)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(s.utcNow(TimeZones.offset(offset)), style = MaterialTheme.typography.bodyMedium)
                if (mine != null) {
                    val d = offset - mine
                    Text(
                        when {
                            d == 0 -> s.sameTime
                            d > 0 -> s.ahead(span(d))
                            else -> s.behind(span(-d))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    change?.let { c ->
                        // When it happens by the clocks there, before they change.
                        val at = Instant.fromEpochMilliseconds(c.atMillis + offset * 1000L).toLocalDateTime(TimeZone.UTC)
                        val day = Fmt.day(at.date, withYear = true)
                        val time = Fmt.clock(at.hour, at.minute, null)
                        if (c.offsetSeconds > offset) s.clocksForward(span(c.offsetSeconds - offset), day, time)
                        else s.clocksBack(span(offset - c.offsetSeconds), day, time)
                    } ?: s.noClockChanges,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * A tapped region of the map builds: its name, how its MapAround map stands (when it was built, its
 * size), how the country or group of states it's part of stands as one map, and, where one is too
 * big for the free build machines, the way to help fund a bigger one.
 */
@Composable
fun MapBuildCard(data: MapBuildsData, path: String, onClose: () -> Unit) {
    val platform = app.maparound.ui.LocalPlatform.current
    val region = data.region(path) ?: return
    val b = data.builds[path]
    val s = T.stats
    val wholes = data.wholesAround(region).mapNotNull { w -> data.builds[w.path]?.let { w to it } }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.padding(end = 10.dp).size(14.dp)
                        .background(hexColor(MapBuilds.colors[(b?.state ?: BuildState.MISSING).ordinal]), CircleShape)
                        .border(1.dp, Color.White, CircleShape),
                )
                Text(region.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            Text(
                when {
                    b == null -> s.notListed
                    b.state == BuildState.CURRENT -> b.builtAt?.let { s.builtOn(buildDay(it)) } ?: s.buildState(BuildState.CURRENT)
                    b.state == BuildState.OLD -> b.builtAt?.let { s.olderBuild(buildDay(it)) } ?: s.buildState(BuildState.OLD)
                    b.why == CatalogEntry.Unavailable.NEEDS_FUNDING -> s.needsFunding
                    b.why == CatalogEntry.Unavailable.NOT_BUILT -> s.buildFailed
                    else -> s.notListed
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            b?.sizeBytes?.takeIf { b.state != BuildState.MISSING }?.let { Text(s.mapSize(Fmt.fileSize(it)), style = MaterialTheme.typography.bodySmall) }
            for ((w, wb) in wholes) {
                Text(
                    s.asOneMap(w.title, s.buildShort(wb.state, wb.why)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val funding = (listOfNotNull(b) + wholes.map { it.second }).any { it.why == CatalogEntry.Unavailable.NEEDS_FUNDING }
            if (funding) TextButton(onClick = { platform.openUrl(s.supportUrl) }, modifier = Modifier.padding(top = 2.dp)) { Text(s.helpFund) }
        }
    }
}

/** A build's day ("15 Nov 2026", in the date order chosen). */
private fun buildDay(epochMillis: Long): String =
    Fmt.day(Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date, withYear = true)

/** A length of time in seconds as hours and minutes ("1 h", "5 h 30 min"). */
private fun span(seconds: Int): String = T.stats.hoursMinutes(seconds / 3600, seconds % 3600 / 60)
