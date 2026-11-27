package app.maparound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.routing.MapFocus
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.tools.ToolsSession
import app.maparound.tracks.LocalTracks
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * A route or track tapped on the map ([trackId]): its name, distance, climb and time, its
 * elevation (tapped or slid along, or pointed at with a mouse: the distance, height and time
 * there), and what to do with it: navigate along it, edit it (a route saved with its stops, in
 * the planner), hide it from the map. Closes by itself when it's no longer on the map.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteCard(
    trackId: String,
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    val repo = LocalTracks.current
    val tracks by repo.tracks.collectAsState()
    val visible by repo.visible.collectAsState()
    val saved = tracks.firstOrNull { it.id == trackId }?.takeIf { it.id in visible }
    LaunchedEffect(saved == null) { if (saved == null) onClose() }
    saved ?: return
    // Navigating from here: the map's already in view. The card stays while the route is
    // calculated (or how to follow a track is asked) and closes once navigation has started.
    val navigate = RouteNavigation(hasLocation, requestLocation, snackbar, onShowMap = {})
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(Unit) {
        NavigationSession.state.map { it.active }.distinctUntilChanged().drop(1).collect { if (it) close() }
    }
    val track = saved.track

    Card(Modifier.widthIn(max = 420.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // How it goes and when it was made (its numbers are just below).
                    Text(
                        listOfNotNull(track.profile?.takeIf { it != RouteProfile.LINE }?.label, Fmt.relativeTime(track.created)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatsLine(track.stats, durationOf(saved))
                ElevationProfile(track.points, Modifier.fillMaxWidth().height(110.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (track.points.size >= 2) {
                    Button(onClick = { navigate(saved) }) {
                        Icon(Icons.Filled.Navigation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(T.library.navigate)
                    }
                }
                // Only a route saved with its stops can be changed (a track is as it was walked).
                if (track.waypoints.size >= 2) {
                    OutlinedButton(onClick = {
                        onClose()
                        ToolsSession.close()
                        PlannerSession.editSaved(saved)
                        MapFocus.show(track.points)
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(T.common.edit)
                    }
                }
                TextButton(onClick = {
                    onClose()
                    repo.setVisible(saved.id, false)
                }) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(T.library.hideOnMap)
                }
            }
        }
    }
}
