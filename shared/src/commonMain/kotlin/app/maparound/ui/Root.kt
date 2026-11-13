package app.maparound.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import app.maparound.map.MapSurface
import app.maparound.party.LocalParty
import app.maparound.party.PartyEvent
import app.maparound.share.Incoming
import kotlinx.coroutines.flow.Flow

enum class Tab(val label: String, val icon: ImageVector) {
    Map("Map", Icons.Filled.Map),
    Routes("Library", Icons.Filled.Bookmarks),
    Party("Party", Icons.Filled.Groups),
    Offline("Offline", Icons.Filled.DownloadForOffline),
    Sensors("Sensors", Icons.Filled.Explore),
}

/**
 * The app: tabs for the map, library, party, offline maps and sensors. The platform gives the
 * location permission state, its background messages, start-up work and its own views.
 */
@Composable
fun MapAroundRoot(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    /** Messages for the snackbar (downloads finished or failed…). */
    messages: Flow<String>,
    /** Runs once at start: finish downloads, recover an interrupted recording, resume sharing. */
    onStart: suspend () -> Unit,
    createMap: () -> MapSurface,
    mapView: @Composable (MapSurface, Modifier) -> Unit,
    view3d: @Composable (Scene3DViewArgs) -> Unit,
    /** The Satellites tab (Android only). */
    satellites: (@Composable (hasLocation: Boolean, requestLocation: () -> Unit) -> Unit)?,
    /** The tabs this platform has so far. */
    tabs: List<Tab> = Tab.entries,
    initialTab: Tab = Tab.Map,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    val snackbar = remember { SnackbarHostState() }
    val party = LocalParty.current
    // Messages from downloads and other background work.
    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }
    // Finish what was going on before the app was closed (downloads, recordings, party sharing).
    LaunchedEffect(Unit) { onStart() }
    val incoming by Incoming.pending.collectAsState()
    LaunchedEffect(incoming) {
        when (val i = incoming) {
            null -> Unit
            is Incoming.Item.Message -> {
                Incoming.consume()
                snackbar.showSnackbar(i.text)
            }
            else -> tab = Tab.Map // the map screen shows it
        }
    }
    // Party: open invites, announce joins and alerts.
    val invite by party.pendingInvite.collectAsState()
    LaunchedEffect(invite) { if (invite != null) tab = Tab.Party }
    LaunchedEffect(Unit) {
        party.events.collect { ev ->
            when (ev) {
                is PartyEvent.Joined -> snackbar.showSnackbar("${ev.member.displayName} joined the party")
                is PartyEvent.SosRaised -> snackbar.showSnackbar("SOS from ${ev.member.displayName}! ${ev.text}")
                is PartyEvent.SosCancelled -> snackbar.showSnackbar("${ev.member.displayName} cancelled the SOS")
                is PartyEvent.Left -> snackbar.showSnackbar("${ev.member.displayName} left the party")
                else -> Unit
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Map -> MapScreen(
                    hasLocation = hasLocation,
                    requestLocation = requestLocation,
                    // Platforms without the Offline tab yet (the web app) don't offer to go there.
                    onOpenOffline = if (Tab.Offline in tabs) ({ tab = Tab.Offline }) else null,
                    snackbar = snackbar,
                    createMap = createMap,
                    mapView = mapView,
                    view3d = view3d,
                )
                Tab.Routes -> LibraryScreen(hasLocation, requestLocation, snackbar, onShowMap = { tab = Tab.Map })
                Tab.Party -> PartyScreen(hasLocation, requestLocation, snackbar, onShowMap = { tab = Tab.Map })
                Tab.Offline -> OfflineMapsScreen(snackbar = snackbar, onShowOnMap = { tab = Tab.Map })
                Tab.Sensors -> SensorsScreen(hasLocation, requestLocation, satellites = satellites?.let { s -> { s(hasLocation, requestLocation) } })
            }
        }
    }
}
