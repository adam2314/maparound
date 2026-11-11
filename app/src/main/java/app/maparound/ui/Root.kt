package app.maparound.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Groups
import app.maparound.party.PartyEvent
import app.maparound.party.PartySession
import app.maparound.share.IncomingShare
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.maparound.AppGraph
import app.maparound.sensors.hasLocationPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Tab(val label: String, val icon: ImageVector) {
    Map("Map", Icons.Filled.Map),
    Routes("Library", Icons.Filled.Bookmarks),
    Party("Party", Icons.Filled.Groups),
    Offline("Offline", Icons.Filled.DownloadForOffline),
    Sensors("Sensors", Icons.Filled.Explore),
}

@Composable
fun MapAroundRoot() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Map) }

    var hasLocation by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasLocation = context.hasLocationPermission() }
    val requestLocation: () -> Unit = {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasLocation && !askedOnce) {
            askedOnce = true
            requestLocation()
        }
    }
    // Permission may be changed from system settings while we're in the background.
    LifecycleResumeEffect(Unit) {
        hasLocation = context.hasLocationPermission()
        onPauseOrDispose { }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // Finish downloads that completed while the app was closed.
        withContext(Dispatchers.IO) { AppGraph.downloader.poll() }
        AppGraph.downloader.events.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        AppGraph.terrain.events.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        AppGraph.trails.events.collect { snackbar.showSnackbar(it) }
    }
    // Party: carry on sharing after a restart, open invites, announce joins and alerts.
    LaunchedEffect(Unit) { PartySession.resumeIfWasActive() }
    val incoming by IncomingShare.pending.collectAsState()
    LaunchedEffect(incoming) {
        when (val i = incoming) {
            null -> Unit
            is IncomingShare.Item.Message -> {
                IncomingShare.consume()
                snackbar.showSnackbar(i.text)
            }
            else -> tab = Tab.Map // the map screen shows it
        }
    }
    val invite by PartySession.pendingInvite.collectAsState()
    LaunchedEffect(invite) { if (invite != null) tab = Tab.Party }
    LaunchedEffect(Unit) {
        PartySession.events.collect { ev ->
            when (ev) {
                is PartyEvent.Joined -> snackbar.showSnackbar("${ev.member.displayName} joined the party")
                is PartyEvent.SosRaised -> snackbar.showSnackbar("SOS from ${ev.member.displayName}! ${ev.text}")
                is PartyEvent.SosCancelled -> snackbar.showSnackbar("${ev.member.displayName} cancelled the SOS")
                is PartyEvent.Left -> snackbar.showSnackbar("${ev.member.displayName} left the party")
                else -> Unit
            }
        }
    }
    LaunchedEffect(Unit) {
        // Give a recording service that the system is restarting a moment to come up first.
        delay(3000)
        withContext(Dispatchers.IO) { AppGraph.tracks.recoverInterruptedRecording() }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AppGraph.routing.poll() }
        AppGraph.routing.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
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
                    onOpenOffline = { tab = Tab.Offline },
                    snackbar = snackbar,
                )
                Tab.Routes -> LibraryScreen(hasLocation, requestLocation, snackbar, onShowMap = { tab = Tab.Map })
                Tab.Party -> PartyScreen(hasLocation, requestLocation, snackbar, onShowMap = { tab = Tab.Map })
                Tab.Offline -> OfflineMapsScreen(snackbar = snackbar, onShowOnMap = { tab = Tab.Map })
                Tab.Sensors -> SensorsScreen(hasLocation, requestLocation, satellites = { GnssScreen(hasLocation, requestLocation) })
            }
        }
    }
}
