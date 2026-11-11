package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.routing.Geo
import app.maparound.routing.MapFocus
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.share.RouteText
import app.maparound.tracks.LocalTracks
import app.maparound.tracks.SavedTrack
import app.maparound.tracks.TrackKind
import app.maparound.tracks.TrackRecorder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    snackbar: SnackbarHostState,
    onShowMap: () -> Unit,
    header: @Composable () -> Unit = { Text("Routes & tracks") },
) {
    val platform = LocalPlatform.current
    val recording = LocalRecordingControl.current
    val scope = rememberCoroutineScope()
    val repo = LocalTracks.current
    val tracks by repo.tracks.collectAsState()
    val visible by repo.visible.collectAsState()
    val recorder by TrackRecorder.state.collectAsState()
    val startRecording = recording.rememberStarter(hasLocation, requestLocation)
    var detail by remember { mutableStateOf<SavedTrack?>(null) }
    var renaming by remember { mutableStateOf<SavedTrack?>(null) }
    var deleting by remember { mutableStateOf<SavedTrack?>(null) }

    val importer = rememberFilePicker(listOf("*/*")) { file ->
        if (file != null) {
            scope.launch {
                val result = withContext(Dispatchers.Default) { repo.importGpx(file.bytes, file.name) }
                snackbar.showSnackbar(result.fold({ "Imported ${it.track.name}" }, { "Import failed: ${it.message}" }))
            }
        }
    }

    fun showOnMap(t: SavedTrack) {
        repo.setVisible(t.id, true)
        MapFocus.show(t.track.points)
        onShowMap()
    }

    fun share(t: SavedTrack) = platform.shareRoute(t.track, repo.pathOf(t.id))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = header,
            actions = {
                IconButton(onClick = { importer() }) {
                    Icon(Icons.Filled.FileOpen, contentDescription = "Import a GPX file")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (recorder.recording) {
                            Text("Recording your track", style = MaterialTheme.typography.titleMedium)
                            RecordingBar(recorder, onStop = { recording.stop() })
                            Text(
                                "Keeps recording with the screen off. Stop to save it here.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = startRecording) {
                                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFFF8A80))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Record")
                                }
                                FilledTonalButton(onClick = {
                                    PlannerSession.open()
                                    onShowMap()
                                }) {
                                    Icon(Icons.Filled.Route, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Plan a route")
                                }
                            }
                            Text(
                                "Plan: tap points on the map for a hiking, bike or car route, or choose Line to measure. " +
                                    "Routes are calculated on the phone where routing data is downloaded, otherwise online.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    if (tracks.isEmpty()) "Nothing saved yet" else "Saved (${tracks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        "Saved routes and recorded tracks appear here. You can also import GPX files from other apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(tracks, key = { it.id }) { t ->
                TrackRow(
                    t,
                    shown = t.id in visible,
                    onClick = { detail = t },
                    onToggle = { repo.setVisible(t.id, t.id !in visible) },
                    onShowOnMap = { showOnMap(t) },
                    onShare = { share(t) },
                    onRename = { renaming = t },
                    onDelete = { deleting = t },
                )
            }
        }
    }

    detail?.let { t ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.track.name, style = MaterialTheme.typography.titleLarge)
                Text(subtitle(t), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatsLine(t.track.stats, durationOf(t))
                ElevationProfile(t.track.points, Modifier.fillMaxWidth().height(140.dp))
                t.track.stats.maxEleM?.let { hi ->
                    Text(
                        "Highest ${hi.roundToInt()} m · lowest ${t.track.stats.minEleM?.roundToInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        detail = null
                        showOnMap(t)
                    }) { Text("Show on map") }
                    TextButton(onClick = { share(t) }) { Text("Share") }
                }
            }
        }
    }

    renaming?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.track.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    repo.rename(t.id, name.trim().ifEmpty { t.track.name })
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${t.track.name}?") },
            text = { Text("The GPX file is removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(t.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackRow(
    t: SavedTrack,
    shown: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onShowOnMap: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val platform = LocalPlatform.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(iconFor(t.track.kind), contentDescription = t.track.kind.label) },
        headlineContent = { Text(t.track.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle(t), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (shown) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (shown) "Hide on map" else "Show on map",
                        tint = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Show on map") }, leadingIcon = { Icon(Icons.Filled.Map, null) }, onClick = { menu = false; onShowOnMap() })
                        DropdownMenuItem(text = { Text("Share (GPX + links)") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                        if (RouteText.hasRouteLink(t.track)) {
                            DropdownMenuItem(text = { Text("Share link only") }, leadingIcon = { Icon(Icons.Filled.Link, null) }, onClick = { menu = false; platform.shareRouteLink(t.track) })
                        }
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}

private fun iconFor(kind: TrackKind): ImageVector = when (kind) {
    TrackKind.PLANNED -> Icons.Filled.Route
    TrackKind.DRAWN -> Icons.Filled.Draw
    TrackKind.RECORDED -> Icons.Filled.FiberManualRecord
    TrackKind.IMPORTED -> Icons.Filled.FileOpen
}

/** Recorded tracks show their real duration; routes their estimated walking/riding time. */
private fun durationOf(t: SavedTrack): Long? {
    val s = t.track.stats
    return when {
        t.track.kind == TrackKind.RECORDED -> s.elapsedS
        t.track.profile == null || t.track.profile == RouteProfile.HIKE || t.track.profile == RouteProfile.LINE ->
            Geo.hikingTimeSeconds(s.distanceM, s.ascentM, s.descentM)
        else -> null
    }
}

private fun subtitle(t: SavedTrack): String {
    val s = t.track.stats
    val parts = mutableListOf(Fmt.distance(s.distanceM.toFloat()))
    s.ascentM?.let { parts += "↑ ${it.roundToInt()} m" }
    durationOf(t)?.let { parts += Fmt.duration(it) }
    t.track.profile?.takeIf { it != RouteProfile.LINE }?.let { parts += it.label.lowercase() }
    parts += Fmt.relativeTime(t.track.created)
    return parts.joinToString(" · ")
}

@Suppress("unused")
@Composable
private fun Busy() = LinearProgressIndicator(Modifier.fillMaxWidth())
