package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalPrefs
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.places.LocalPlaces
import app.maparound.places.Place
import app.maparound.places.PlaceCategory
import app.maparound.places.Places
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.MapFocus
import app.maparound.sensors.GeoFix
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.tools.Geodesy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The category emoji in a tinted circle. */
@Composable
fun PlaceBadge(category: PlaceCategory, size: Int = 36) {
    Box(
        Modifier.size(size.dp).background(Color(category.argb).copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(category.emoji, fontSize = (size * 0.5).sp) }
}

/** "1.2 km NE" from the user's position, or null without one. */
private fun distanceFrom(me: GeoFix?, p: Place): String? = me?.let {
    val from = GeoPoint(it.lat, it.lon)
    T.library.distanceDirection(Fmt.distance(Geo.distance(from, p.point).toFloat()), Fmt.cardinal(Geodesy.bearing(from, p.point).toFloat()))
}

// ---- add / edit -------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceEditDialog(place: Place, isNew: Boolean, onDismiss: () -> Unit, onSave: (Place) -> Unit) {
    var name by remember { mutableStateOf(place.name) }
    var category by remember { mutableStateOf(place.category) }
    var note by remember { mutableStateOf(place.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) T.library.savePlace else T.library.editPlace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(T.library.placeName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlaceCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text("${c.emoji} ${c.label}") },
                        )
                    }
                }
                OutlinedTextField(note, { note = it.take(1000) }, label = { Text(T.library.placeNote) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Text(ltr(formatPoint(place.lat, place.lon, LocalPrefs.current.coordFormat.value)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(place.copy(name = name.trim().ifEmpty { category.label }, category = category, note = note.trim()))
            }) { Text(T.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.cancel) } },
    )
}

// ---- card on the map ----------------------------------------------------------------------------------------

@Composable
fun PlaceCard(
    place: Place,
    myLocation: GeoFix?,
    onClose: () -> Unit,
    onRoute: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onViewshed: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.widthIn(max = 360.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaceBadge(place.category)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(place.category.label, distanceFrom(myLocation, place), place.ele?.let { "${it.toInt()} ${T.common.unitM}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) }
            }
            if (place.note.isNotBlank()) {
                Text(place.note, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            }
            Text(ltr(formatPoint(place.lat, place.lon, LocalPrefs.current.coordFormat.value)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                IconButton(onClick = onRoute) { Icon(Icons.Filled.Route, contentDescription = T.library.routeHere) }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = T.library.sharePlace) }
                IconButton(onClick = onViewshed) { Icon(Icons.Filled.Visibility, contentDescription = T.library.whatCanBeSeenFromHere) }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = T.library.editPlace) }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = T.library.deletePlace) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(T.library.confirmDeletePlace(place.name)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(T.common.delete) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(T.common.cancel) } },
        )
    }
}

// ---- library: routes or places --------------------------------------------------------------------------------

/** The Library tab: routes & tracks, or saved places. */
@Composable
fun LibraryScreen(hasLocation: Boolean, requestLocation: () -> Unit, snackbar: SnackbarHostState, onShowMap: () -> Unit) {
    var places by rememberSaveable { mutableStateOf(false) }
    val header: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = !places, onClick = { places = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text(T.library.routes) }
            SegmentedButton(selected = places, onClick = { places = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text(T.library.places) }
        }
    }
    if (places) {
        PlacesScreen(hasLocation, snackbar, onShowMap, header)
    } else {
        RoutesScreen(hasLocation, requestLocation, snackbar, onShowMap, header)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(hasLocation: Boolean, snackbar: SnackbarHostState, onShowMap: () -> Unit, header: @Composable () -> Unit) {
    val platform = LocalPlatform.current
    val sensors = LocalDeviceSensors.current
    val prefs = LocalPrefs.current
    val scope = rememberCoroutineScope()
    val repo = LocalPlaces.current
    val all by repo.places.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<PlaceCategory?>(null) }
    var sort by rememberSaveable { mutableStateOf(Places.Sort.NEAREST) }
    val locations = remember(hasLocation) { if (hasLocation) sensors.locations(5_000) else flowOf(null) }
    val location by locations.collectAsStateWithLifecycle(initialValue = null)
    val me = location?.let { GeoPoint(it.lat, it.lon) }
    val shown = Places.filter(all, query, category, if (sort == Places.Sort.NEAREST && me == null) Places.Sort.NEWEST else sort, me)
    var editing by remember { mutableStateOf<Place?>(null) }
    var deleting by remember { mutableStateOf<Place?>(null) }

    val importer = rememberFilePicker(listOf("*/*")) { file ->
        if (file != null) {
            scope.launch {
                val result = withContext(Dispatchers.Default) { runCatching { repo.importGpx(file.bytes) } }
                snackbar.showSnackbar(
                    result.fold(
                        { n -> if (n == 0) T.library.noNewPlacesInFile else T.library.importedPlaces(n) },
                        { T.library.importFailed(it.message) },
                    ),
                )
            }
        }
    }

    fun show(p: Place) {
        prefs.setPlacesOnMap(true)
        repo.focus.value = p.id
        MapFocus.show(listOf(p.point))
        onShowMap()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = header,
            actions = {
                IconButton(onClick = { importer() }) { Icon(Icons.Filled.FileOpen, contentDescription = T.library.importPlacesFromGpx) }
                IconButton(onClick = { platform.sharePlaces(shown, T.library.sharedPlacesTitle) }, enabled = shown.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = T.library.shareListedPlaces)
                }
                SettingsButton()
            },
        )
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(T.library.searchNamesAndNotes) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = T.library.clear) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text(T.library.allCount(all.size)) }) }
            items(PlaceCategory.entries.filter { c -> all.any { it.category == c } }) { c ->
                FilterChip(
                    selected = category == c,
                    onClick = { category = if (category == c) null else c },
                    label = { Text("${c.emoji} ${c.label} ${all.count { it.category == c }}") },
                )
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp)) {
            Places.Sort.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = sort == s,
                    onClick = { sort = s },
                    shape = SegmentedButtonDefaults.itemShape(i, Places.Sort.entries.size),
                    icon = {},
                    enabled = s != Places.Sort.NEAREST || me != null,
                ) { Text(s.label) }
            }
        }
        if (all.isEmpty()) {
            MessageCard(
                Icons.Filled.Place,
                T.library.noSavedPlaces,
                T.library.noSavedPlacesHint,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { p ->
                PlaceRow(
                    p, location,
                    onClick = { show(p) },
                    onEdit = { editing = p },
                    onShare = { platform.sharePlaces(listOf(p), p.name) },
                    onDelete = { deleting = p },
                )
            }
        }
    }

    editing?.let { p -> PlaceEditDialog(p, isNew = false, onDismiss = { editing = null }, onSave = { repo.save(it); editing = null }) }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(T.library.confirmDeletePlace(p.name)) },
            confirmButton = { TextButton(onClick = { repo.delete(p.id); deleting = null }) { Text(T.common.delete) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(T.common.cancel) } },
        )
    }
}

@Composable
private fun PlaceRow(p: Place, me: GeoFix?, onClick: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { PlaceBadge(p.category, 40) },
        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(distanceFrom(me, p) ?: p.category.label, p.note.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = T.library.moreFor(p.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(T.common.edit) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(T.common.share) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(T.common.delete) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}
