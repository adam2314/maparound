package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.search.SearchResult
import app.maparound.sensors.GeoFix
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search over the map: your places, coordinates and the offline maps as you type; OpenStreetMap
 * online when you press search (the free servers ask not to be queried on every keystroke).
 */
@Composable
fun SearchOverlay(mapCenter: GeoPoint, myLocation: GeoFix?, onPick: (SearchResult) -> Unit, onClose: () -> Unit) {
    PlatformBackHandler(onBack = onClose)
    val searcher = LocalSearcher.current
    var query by rememberSaveable { mutableStateOf("") }
    var quick by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var offline by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var online by remember { mutableStateOf<List<SearchResult>?>(null) }
    var offlineBusy by remember { mutableStateOf(false) }
    var onlineBusy by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var onlineJob by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    val me = myLocation?.let { GeoPoint(it.lat, it.lon) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        online = null
        onlineError = null
        onlineJob?.cancel()
        quick = if (query.isBlank()) emptyList() else searcher.quick(query, mapCenter)
        offline = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        delay(350) // wait for typing to pause
        offlineBusy = true
        val ctx = coroutineContext
        offline = withContext(Dispatchers.Default) { searcher.offline(query, mapCenter, isActive = { ctx.isActive }) }
        offlineBusy = false
    }

    fun searchOnline() {
        if (query.trim().length < 2) return
        onlineJob?.cancel()
        onlineJob = scope.launch {
            onlineBusy = true
            onlineError = null
            val r = withContext(Dispatchers.Default) { runCatching { searcher.online(query, mapCenter) } }
            online = r.getOrNull()
            onlineError = r.exceptionOrNull()?.let { T.poi.onlineSearchFailed }
            onlineBusy = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = T.poi.closeSearch) }
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text(T.poi.searchHint) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = T.poi.clearSearch) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchOnline() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            if (offlineBusy || onlineBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                val sections = listOf(
                    SearchResult.Source.COORDINATES to quick.filter { it.source == SearchResult.Source.COORDINATES },
                    SearchResult.Source.PLACES to quick.filter { it.source == SearchResult.Source.PLACES }.sortedBy { it.score },
                    SearchResult.Source.MAP to offline,
                    SearchResult.Source.ONLINE to online.orEmpty(),
                )
                for ((source, list) in sections) {
                    if (list.isEmpty()) continue
                    item { Header(source.label) }
                    items(list) { r -> ResultRow(r, me ?: mapCenter, onPick) }
                }
                if (query.trim().length >= 2) {
                    if (!offlineBusy && offline.isEmpty() && quick.isEmpty()) {
                        item {
                            Text(
                                T.poi.nothingNearby,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (online == null && !onlineBusy) {
                        item {
                            ListItem(
                                modifier = Modifier.clickable { searchOnline() },
                                leadingContent = { Icon(Icons.Filled.Public, contentDescription = null) },
                                headlineContent = { Text(T.poi.searchOnlineFor(query.trim())) },
                                supportingContent = { Text(onlineError ?: T.poi.searchOnlineWhere) },
                            )
                        }
                    } else if (online?.isEmpty() == true) {
                        item { Text(T.poi.nothingFoundOnline, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(r: SearchResult, from: GeoPoint, onPick: (SearchResult) -> Unit) {
    val d = Geo.distance(from, r.point)
    ListItem(
        modifier = Modifier.clickable { onPick(r) },
        leadingContent = {
            Icon(
                when (r.source) {
                    SearchResult.Source.COORDINATES -> Icons.Filled.MyLocation
                    SearchResult.Source.PLACES -> Icons.Filled.Bookmark
                    SearchResult.Source.MAP -> Icons.Filled.Map
                    SearchResult.Source.ONLINE -> Icons.Filled.Search
                },
                contentDescription = null,
            )
        },
        headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOf(r.detail, Fmt.distance(d.toFloat())).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
    )
}
