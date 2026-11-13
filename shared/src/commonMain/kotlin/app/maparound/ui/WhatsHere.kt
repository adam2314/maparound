package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.map.MapPoi
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.search.Commons
import app.maparound.search.Wikipedia
import app.maparound.shared.util.deviceLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "What's here?" for a point (a tapped point of interest, or a pin): its Wikipedia article and
 * picture, photos taken around it (Wikimedia Commons), the named places the map shows around it
 * (offline), and Wikipedia articles about places nearby (online), each opening in the browser.
 * [known]: the place's own article, when the pin already has it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsHereSheet(
    point: GeoPoint,
    poi: MapPoi?,
    around: List<MapPoi>,
    onShow: (MapPoi) -> Unit,
    onShowPoint: (GeoPoint, String) -> Unit,
    onDismiss: () -> Unit,
    known: Wikipedia.Article? = null,
) {
    val platform = LocalPlatform.current
    val userLang = remember { Wikipedia.language(deviceLanguage()) }
    var articles by remember(point) { mutableStateOf<List<Wikipedia.Article>?>(null) }
    var problem by remember(point) { mutableStateOf<String?>(null) }
    // The place's own article (its tags), in the user's language when there is one.
    var tagged by remember(point) { mutableStateOf(known) }
    var photos by remember(point) { mutableStateOf<List<Commons.Photo>?>(null) }
    var photosFailed by remember(point) { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Commons.Photo?>(null) }
    LaunchedEffect(point) {
        if (poi != null && poi.hasArticle && tagged?.picture == null) {
            launch { tagged = (tagged ?: articleOrNull(poi))?.let { Wikipedia.withPicture(it) } }
        }
        launch {
            try {
                photos = Commons.nearby(point.lat, point.lon)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                photosFailed = true
            }
        }
        try {
            articles = Wikipedia.nearby(point.lat, point.lon, deviceLanguage())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Wikipedia needs a connection."
        }
    }
    val own = tagged ?: poi?.let { Wikipedia.about(it, articles.orEmpty()) }
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
            own?.picture?.let { pic ->
                item {
                    RemoteImage(
                        pic.thumb,
                        contentDescription = own.title,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(200.dp)
                            .clip(RoundedCornerShape(12.dp)).clickable { viewing = pic },
                    )
                }
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

            item { Header("Photos around here") }
            item {
                val list = photos
                when {
                    photosFailed -> Note("Photos need a connection.")
                    list == null -> Loading("Looking for photos taken around here…")
                    list.isEmpty() -> Note("No photos taken within 10 km on Wikimedia Commons.")
                    else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.file }) { photo ->
                            RemoteImage(
                                photo.thumb,
                                contentDescription = photo.name,
                                modifier = Modifier.size(PHOTO_DP.dp).clip(RoundedCornerShape(8.dp)).clickable { viewing = photo },
                            )
                        }
                    }
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
                articles == null -> item { Loading("Looking for articles about places around here…") }
                nearby.isEmpty() -> item { Note("No articles about places within ${Wikipedia.RADIUS_M / 1000} km.") }
                else -> items(nearby.take(MAX_ARTICLES), key = { "w-${it.lang}-${it.title}" }) { a ->
                    ListItem(
                        modifier = Modifier.clickable { platform.openUrl(a.url) },
                        leadingContent = {
                            val pic = a.picture
                            if (pic != null) {
                                RemoteImage(pic.thumb, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                            } else {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(48.dp).padding(12.dp))
                            }
                        },
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

    viewing?.let { photo ->
        PhotoViewer(photo, onDismiss = { viewing = null }, onShowOnMap = { p, name ->
            viewing = null
            onShowPoint(p, name)
        })
    }
}

/** The place's own article, or null when it has none or Wikidata can't be asked. */
private suspend fun articleOrNull(poi: MapPoi): Wikipedia.Article? = try {
    Wikipedia.articleOf(poi, deviceLanguage())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
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

@Composable
private fun Loading(text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val MAX_PLACES = 12
private const val MAX_ARTICLES = 25

/** The photos' size in the row, square. */
private const val PHOTO_DP = 104
