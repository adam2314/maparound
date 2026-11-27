package app.maparound.ui

import app.maparound.tools.ToolsSession
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Navigation
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditRoad
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
import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.MapFocus
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.share.RouteText
import app.maparound.share.TrackLink
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    header: @Composable () -> Unit = { Text(T.library.routesAndTracks) },
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
                snackbar.showSnackbar(result.fold({ T.library.importedTrack(it.track.name) }, { T.library.importFailed(it.message) }))
            }
        }
    }

    fun showOnMap(t: SavedTrack) {
        repo.setVisible(t.id, true)
        MapFocus.show(t.track.points)
        onShowMap()
    }

    fun share(t: SavedTrack) = platform.shareRoute(t.track, repo.pathOf(t.id))

    /** A route saved with its stops, in the planner on the map, to change and save over itself. */
    fun editRoute(t: SavedTrack) {
        ToolsSession.close()
        PlannerSession.editSaved(t)
        MapFocus.show(t.track.points)
        onShowMap()
    }

    // Navigation along it (as from its card on the map).
    val navigate = RouteNavigation(hasLocation, requestLocation, snackbar, onShowMap)

    // The code that puts it on a map in a web page, copied to paste there.
    val clipboard = LocalClipboardManager.current
    fun embed(t: SavedTrack) {
        val code = TrackLink.embedCode(t.track) ?: return
        clipboard.setText(AnnotatedString(code))
        scope.launch { snackbar.showSnackbar(T.library.embedCodeCopied) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = header,
            navigationIcon = { ClosePanelButton() },
            actions = {
                Tip(T.library.importGpxFile) { IconButton(onClick = { importer() }) {
                    Icon(Icons.Filled.FileOpen, contentDescription = T.library.importGpxFile)
                } }
                SettingsButton()
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (recorder.recording) {
                            Text(T.library.recordingYourTrack, style = MaterialTheme.typography.titleMedium)
                            RecordingBar(recorder, onStop = { recording.stop() })
                            Text(
                                T.library.recordingKeepsGoing,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = startRecording) {
                                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFFF8A80))
                                    Spacer(Modifier.width(6.dp))
                                    Text(T.library.record)
                                }
                                FilledTonalButton(onClick = {
                                    PlannerSession.open()
                                    onShowMap()
                                }) {
                                    Icon(Icons.Filled.Route, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(T.library.planARoute)
                                }
                            }
                            Text(
                                T.library.planHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    if (tracks.isEmpty()) T.library.nothingSavedYet else T.library.savedCount(tracks.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        T.library.nothingSavedHint,
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
                    onEdit = { editRoute(t) },
                    onNavigate = { navigate(t) },
                    onShare = { share(t) },
                    onEmbed = { embed(t) },
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
                        T.library.highestLowest(Fmt.height(hi), t.track.stats.minEleM?.let { Fmt.height(it) }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.track.points.size >= 2) {
                        Button(onClick = {
                            detail = null
                            navigate(t)
                        }) {
                            Icon(Icons.Filled.Navigation, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(T.library.navigate)
                        }
                    }
                    OutlinedButton(onClick = {
                        detail = null
                        showOnMap(t)
                    }) { Text(T.common.showOnMap) }
                    TextButton(onClick = { share(t) }) { Text(T.common.share) }
                }
            }
        }
    }

    renaming?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.track.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(T.common.rename) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    repo.rename(t.id, name.trim().ifEmpty { t.track.name })
                    renaming = null
                }) { Text(T.common.save) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(T.common.cancel) } },
        )
    }

    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(T.library.confirmDeleteTrack(t.track.name)) },
            text = { Text(T.library.gpxFileRemoved) },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(t.id)
                    deleting = null
                }) { Text(T.common.delete) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(T.common.cancel) } },
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
    onEdit: () -> Unit,
    onNavigate: () -> Unit,
    onShare: () -> Unit,
    onEmbed: () -> Unit,
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
                Tip(if (shown) T.library.hideOnMap else T.common.showOnMap) { IconButton(onClick = onToggle) {
                    Icon(
                        if (shown) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (shown) T.library.hideOnMap else T.common.showOnMap,
                        tint = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } }
                Box {
                    Tip(T.common.more) { IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = T.common.more) } }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (t.track.points.size >= 2) {
                            DropdownMenuItem(text = { Text(T.library.navigate) }, leadingIcon = { Icon(Icons.Filled.Navigation, null) }, onClick = { menu = false; onNavigate() })
                        }
                        DropdownMenuItem(text = { Text(T.common.showOnMap) }, leadingIcon = { Icon(Icons.Filled.Map, null) }, onClick = { menu = false; onShowOnMap() })
                        // Saved with its stops (planned, drawn, or a GPX with them): in the planner.
                        if (t.track.waypoints.size >= 2) {
                            DropdownMenuItem(text = { Text(T.library.editRoute) }, leadingIcon = { Icon(Icons.Filled.EditRoad, null) }, onClick = { menu = false; onEdit() })
                        }
                        DropdownMenuItem(text = { Text(T.library.shareGpxAndLinks) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                        if (RouteText.hasLink(t.track)) {
                            DropdownMenuItem(text = { Text(T.library.shareLinkOnly) }, leadingIcon = { Icon(Icons.Filled.Link, null) }, onClick = { menu = false; platform.shareRouteLink(t.track) })
                        }
                        if (t.track.points.size >= 2) {
                            DropdownMenuItem(text = { Text(T.library.embedInWebsite) }, leadingIcon = { Icon(Icons.Filled.Code, null) }, onClick = { menu = false; onEmbed() })
                        }
                        DropdownMenuItem(text = { Text(T.common.rename) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(T.common.delete) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
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
internal fun durationOf(t: SavedTrack): Long? {
    val s = t.track.stats
    return when {
        t.track.kind == TrackKind.RECORDED -> s.elapsedS
        t.track.profile == null || t.track.profile == RouteProfile.HIKE || t.track.profile == RouteProfile.LINE ->
            Geo.hikingTimeSeconds(s.distanceM, s.ascentM, s.descentM)
        else -> null
    }
}

internal fun subtitle(t: SavedTrack): String {
    val s = t.track.stats
    val parts = mutableListOf(Fmt.distance(s.distanceM.toFloat()))
    s.ascentM?.let { parts += "↑ ${Fmt.height(it)}" }
    durationOf(t)?.let { parts += Fmt.duration(it) }
    t.track.profile?.takeIf { it != RouteProfile.LINE }?.let { parts += it.label.lowercase() }
    parts += Fmt.relativeTime(t.track.created)
    return parts.joinToString(" · ")
}

@Suppress("unused")
@Composable
private fun Busy() = LinearProgressIndicator(Modifier.fillMaxWidth())
