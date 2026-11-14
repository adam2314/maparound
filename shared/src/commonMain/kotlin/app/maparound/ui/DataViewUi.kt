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

/**
 * The data view's controls: what colours the countries or, zoomed in on Israel, the localities;
 * the legend (a tap hides a class), what's loading, and the way out.
 */
@Composable
fun DataViewPanel(
    showLocalities: Boolean,
    countryIndicator: CountryIndicator,
    onCountryIndicator: (CountryIndicator) -> Unit,
    localityIndicator: LocalityIndicator,
    onLocalityIndicator: (LocalityIndicator) -> Unit,
    hidden: Set<Int>,
    onToggleClass: (Int) -> Unit,
    status: String,
    onGoTo: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showLocalities) T.stats.localities else T.stats.countries,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onGoTo) { Text(if (showLocalities) T.stats.countries else T.stats.localities) }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.stats.leaveDataView) }
            }
            if (showLocalities) {
                Picker(localityIndicator.title, LocalityIndicator.entries, { it.title }, onLocalityIndicator)
                Legend(localityIndicator.classes, hidden, onToggleClass)
                Text(T.stats.sizeIsPopulation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Picker(countryIndicator.title, CountryIndicator.entries, { it.title }, onCountryIndicator)
                Legend(countryIndicator.classes, hidden, onToggleClass)
                Text(T.stats.zoomInForLocalities, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun <E> Picker(current: String, options: List<E>, title: (E) -> String, onPick: (E) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(current)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) DropdownMenuItem(text = { Text(title(o)) }, onClick = { open = false; onPick(o) })
        }
    }
}

/** The classes' colours and labels, and "No data" (-1); a hidden one is faded. */
@Composable
private fun Legend(classes: List<StatClass>, hidden: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (classes.mapIndexed { i, c -> i to c } + (-1 to StatClass(T.stats.noData, NO_DATA))).forEach { (i, c) ->
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
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) }
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
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) }
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
