package app.maparound.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import app.maparound.i18n.ltr
import app.maparound.shared.util.nowMillis
import app.maparound.stats.TimeZones
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
    /** What colours the countries; null: nothing (or the time zones do). */
    val country: CountryIndicator? = CountryIndicator.POPULATION,
    /** The time zones colour the world instead of a country figure. */
    val timeZones: Boolean = false,
    /** What colours Israel's localities; null: they're hidden. */
    val locality: LocalityIndicator? = LocalityIndicator.SIZE,
    val gpsTraces: Boolean = false,
)

/** A leaf of the explorer's tree: its title, whether it's on, the words that find it, and what a tap does. */
private class Leaf(val title: String, val on: Boolean, val words: String, val pick: () -> Unit)

private class Branch(val key: String, val title: String, val icon: ImageVector, val leaves: List<Leaf>)

/**
 * The data view's controls: the explorer (a tree of what can be shown, with a search box) or, once
 * something is picked, what colours the map now and its legend (a tap hides a class); what's
 * loading, the way to the other level, and the way out.
 */
@Composable
fun DataViewPanel(
    showLocalities: Boolean,
    layers: DataLayers,
    onLayers: (DataLayers) -> Unit,
    /** Whether there are time zones / GPS traces to offer (the app has the file; the source isn't off). */
    hasTimeZones: Boolean,
    hasGpsTraces: Boolean,
    hidden: Set<Int>,
    onToggleClass: (Int) -> Unit,
    status: String,
    onGoTo: () -> Unit,
    onClose: () -> Unit,
) {
    var exploring by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
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
            val (group, title) = when {
                exploring -> null to s.dataView
                showLocalities && locality != null -> s.localities to locality.title
                zones -> s.time to s.timeZones
                !showLocalities && country != null -> s.countries to country.title
                layers.gpsTraces -> s.movement to s.gpsTraces
                else -> null to s.dataView
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (group != null) Text(group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = { exploring = !exploring }) {
                    Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.layers)
                    Icon(if (exploring) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                Tip(s.leaveDataView) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = s.leaveDataView) } }
            }
            if (exploring) {
                Explorer(layers, hasTimeZones, hasGpsTraces, query, { query = it }) { onLayers(it); exploring = false; query = "" }
            } else {
                when {
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
                        Hint(s.zoomInForLocalities, s.localities, onGoTo)
                    }
                    else -> Hint(null, if (showLocalities) s.countries else s.localities, onGoTo)
                }
                if (layers.gpsTraces) Text(s.gpsTracesNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A branch this long or shorter starts open. */
private const val SHORT_BRANCH = 5

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

/**
 * The tree: the world's figures, Israel's, time, movement; a search box finds a layer by its name
 * or what it's about. Picking the layer that's on turns it off.
 */
@Composable
private fun Explorer(
    layers: DataLayers,
    hasTimeZones: Boolean,
    hasGpsTraces: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (DataLayers) -> Unit,
) {
    val s = T.stats
    val branches = buildList {
        add(Branch("world", s.world, Icons.Filled.Public, CountryIndicator.entries.map { i ->
            val on = layers.country == i && !layers.timeZones
            Leaf(i.title, on, s.countriesKeywords) { onPick(layers.copy(country = if (on) null else i, timeZones = false)) }
        }))
        add(Branch("israel", s.israel, Icons.Filled.LocationCity, LocalityIndicator.entries.map { i ->
            val on = layers.locality == i
            Leaf(i.title, on, s.localitiesKeywords) { onPick(layers.copy(locality = if (on) null else i)) }
        }))
        if (hasTimeZones) add(Branch("time", s.time, Icons.Filled.Schedule, listOf(
            Leaf(s.timeZones, layers.timeZones, s.timeZonesKeywords) {
                onPick(if (layers.timeZones) layers.copy(timeZones = false) else layers.copy(timeZones = true, country = null))
            },
        )))
        if (hasGpsTraces) add(Branch("movement", s.movement, Icons.Filled.Route, listOf(
            Leaf(s.gpsTraces, layers.gpsTraces, s.gpsKeywords) { onPick(layers.copy(gpsTraces = !layers.gpsTraces)) },
        )))
    }
    // Open: what the user opened; at first the short branches (a long one shows how many are on).
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp),
        singleLine = true,
        placeholder = { Text(s.findData) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) ({
            Tip(T.library.clear) { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = T.library.clear) } }
        }) else null,
    )
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
        var found = 0
        for (b in branches) {
            val leaves = if (words.isEmpty()) b.leaves else b.leaves.filter { l ->
                val text = "${l.title} ${b.title} ${l.words}".lowercase()
                words.all { it in text }
            }
            if (leaves.isEmpty()) continue
            found += leaves.size
            val expanded = words.isNotEmpty() || (open[b.key] ?: (b.leaves.size <= SHORT_BRANCH))
            Row(
                Modifier.fillMaxWidth().clickable { open[b.key] = !expanded }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Icon(b.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(b.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val on = b.leaves.count { it.on }
                if (on > 0 && !expanded) {
                    Text("$on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
                }
            }
            if (expanded) for (l in leaves) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = l.pick).padding(start = 30.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (l.on) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        l.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (l.on) FontWeight.Medium else null,
                        color = if (l.on) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                }
            }
        }
        if (found == 0) Text(s.nothingFound, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
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

/** A tapped country: its name and the figures loaded so far. */
@Composable
fun CountryCard(country: Country, figures: Map<CountryIndicator, Map<String, Figure>>, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (I18n.language == app.maparound.i18n.Language.HE && country.nameHe.isNotBlank()) country.nameHe else country.nameEn,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            for (i in CountryIndicator.entries) {
                val f = figures[i]?.get(country.iso) ?: continue
                Text("${i.title}: ${i.format(f.value)} ${T.stats.asOf(f.year)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

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
                l.altitudeM?.let(T.stats::altitudeOf),
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

/** A length of time in seconds as hours and minutes ("1 h", "5 h 30 min"). */
private fun span(seconds: Int): String = T.stats.hoursMinutes(seconds / 3600, seconds % 3600 / 60)
