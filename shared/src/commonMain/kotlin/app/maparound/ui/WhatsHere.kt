package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.map.MapPoi
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.search.Wikipedia
import app.maparound.shared.ioDispatcher
import app.maparound.shared.util.deviceLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * "What's here?" for a point (a tapped point of interest, or a pin): its Wikipedia article, the
 * named places the map shows around it (offline), and Wikipedia articles about places nearby
 * (online), each opening in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsHereSheet(point: GeoPoint, poi: MapPoi?, around: List<MapPoi>, onShow: (MapPoi) -> Unit, onDismiss: () -> Unit) {
    val platform = LocalPlatform.current
    val userLang = remember { Wikipedia.language(deviceLanguage()) }
    var articles by remember(point) { mutableStateOf<List<Wikipedia.Article>?>(null) }
    var problem by remember(point) { mutableStateOf<String?>(null) }
    LaunchedEffect(point) {
        try {
            articles = withContext(ioDispatcher) { Wikipedia.nearby(point.lat, point.lon, deviceLanguage()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Wikipedia needs a connection."
        }
    }
    val own = poi?.let { Wikipedia.about(it, articles.orEmpty()) }
    val nearby = articles.orEmpty().filterNot { own != null && it.title == own.title }
    // The places around, the tapped one apart.
    val places = around.filterNot { poi != null && it.title == poi.title && Geo.distance(it.point, poi.point) < 30 }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(poi?.title ?: "What's here", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(poi?.details, Fmt.latLon(point.lat, point.lon)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            own?.let { a ->
                item {
                    ListItem(
                        modifier = Modifier.clickable { platform.openUrl(a.url) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("About ${a.title} on Wikipedia") },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open") },
                    )
                }
            }

            item { Header("Around here on the map") }
            if (places.isEmpty()) {
                item { Note("No other named places on the map here at this zoom.") }
            }
            items(places.take(MAX_PLACES), key = { "p-${it.lat}-${it.lon}-${it.title}" }) { p ->
                ListItem(
                    modifier = Modifier.clickable { onShow(p) },
                    leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                    headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(p.details, Fmt.distance(Geo.distance(point, p.point).toFloat())).joinToString(" · "))
                    },
                )
            }

            item { Header("On Wikipedia, nearby") }
            when {
                problem != null -> item { Note(problem!!) }
                articles == null -> item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking for articles about places around here…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                nearby.isEmpty() -> item { Note("No articles about places within ${Wikipedia.RADIUS_M / 1000} km.") }
                else -> items(nearby.take(MAX_ARTICLES), key = { "w-${it.lang}-${it.title}" }) { a ->
                    ListItem(
                        modifier = Modifier.clickable { platform.openUrl(a.url) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                        headlineContent = { Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(Fmt.distance(Geo.distance(point, GeoPoint(a.lat, a.lon)).toFloat()) + if (a.lang == userLang) "" else " · ${a.lang.uppercase()}")
                        },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open") },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private const val MAX_PLACES = 12
private const val MAX_ARTICLES = 25
