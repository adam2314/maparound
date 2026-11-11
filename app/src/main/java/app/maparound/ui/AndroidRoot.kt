package app.maparound.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.maparound.AppGraph
import app.maparound.map.AndroidMapSurface
import app.maparound.party.PartySession
import app.maparound.sensors.hasLocationPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shared app frame with Android's location permission, background messages and views. */
@Composable
fun AndroidRoot() {
    val context = LocalContext.current

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

    MapAroundRoot(
        hasLocation = hasLocation,
        requestLocation = requestLocation,
        messages = remember { merge(AppGraph.downloader.events, AppGraph.terrain.events, AppGraph.trails.events, AppGraph.routing.events) },
        onStart = {
            coroutineScope {
                // Finish downloads that completed while the app was closed.
                launch { withContext(Dispatchers.IO) { AppGraph.downloader.poll() } }
                launch { withContext(Dispatchers.IO) { AppGraph.routing.poll() } }
                // Carry on sharing after a restart.
                launch { PartySession.resumeIfWasActive() }
                launch {
                    // Give a recording service that the system is restarting a moment to come up first.
                    delay(3000)
                    withContext(Dispatchers.IO) { AppGraph.tracks.recoverInterruptedRecording() }
                }
            }
        },
        createMap = { AndroidMapSurface(context) },
        mapView = { map, modifier -> AndroidView(factory = { (map as AndroidMapSurface).controller.mapView }, modifier = modifier) },
        view3d = { Scene3DAndroidView(it) },
        satellites = { hasLoc, request -> GnssScreen(hasLoc, request) },
    )
}
