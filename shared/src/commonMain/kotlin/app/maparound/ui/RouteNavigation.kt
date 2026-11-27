package app.maparound.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.maparound.i18n.T
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.tools.ToolsSession
import app.maparound.tracks.LocalTracks
import app.maparound.tracks.SavedTrack
import kotlinx.coroutines.launch

/**
 * Navigation along a saved route or track, from the library or its card on the map. A planned
 * route is calculated again along its stops (for its turns); a track, or a route that can't be,
 * is followed as it is, a track asking first how (on foot, by bike, by car). Without my location
 * it's asked for first. Gives what starts it; shows its "how" question when there is one.
 * [onShowMap]: the map comes into view (from the library).
 */
@Composable
fun RouteNavigation(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    snackbar: SnackbarHostState,
    onShowMap: () -> Unit,
): (SavedTrack) -> Unit {
    val scope = rememberCoroutineScope()
    val repo = LocalTracks.current
    val located by rememberUpdatedState(hasLocation)
    val askLocation by rememberUpdatedState(requestLocation)
    val showMap by rememberUpdatedState(onShowMap)
    var how by remember { mutableStateOf<SavedTrack?>(null) }

    fun follow(t: SavedTrack, profile: RouteProfile) {
        ToolsSession.close()
        NavigationSession.follow(t.track.points, profile)
        repo.setVisible(t.id, true)
        showMap()
    }

    // A track (no way of travelling of its own): on foot, by bike or by car.
    how?.let { t ->
        AlertDialog(
            onDismissRequest = { how = null },
            title = { Text(T.library.navigateHow) },
            text = {
                Column {
                    Text(T.library.navigateAlongNote, style = MaterialTheme.typography.bodySmall)
                    for (p in listOf(RouteProfile.HIKE, RouteProfile.BIKE, RouteProfile.CAR)) {
                        TextButton(onClick = {
                            how = null
                            follow(t, t.track.profile ?: p)
                        }) { Text(p.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { how = null }) { Text(T.common.cancel) } },
        )
    }

    return { t ->
        val track = t.track
        val profile = track.profile
        when {
            !located -> askLocation()
            profile == null || track.waypoints.size < 2 -> how = t
            else -> scope.launch {
                launch { snackbar.showSnackbar(T.library.calculatingRoute) }
                val route = runCatching { PlannerSession.router(track.waypoints, profile) }.getOrNull()
                snackbar.currentSnackbarData?.dismiss()
                if (route == null || route.points.size < 2) {
                    follow(t, profile)
                } else {
                    ToolsSession.close()
                    NavigationSession.start(route, track.waypoints, profile)
                    repo.setVisible(t.id, true)
                    showMap()
                }
            }
        }
    }
}
